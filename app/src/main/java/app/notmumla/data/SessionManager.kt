package app.notmumla.data

import android.content.Context
import android.media.AudioManager
import android.os.Build
import app.notmumla.audio.AudioEngine
import app.notmumla.audio.TransmissionMode
import app.notmumla.audio.routing.AudioRouter
import app.notmumla.audio.routing.OutputRoute
import app.notmumla.data.db.ServerDao
import dagger.hilt.android.qualifiers.ApplicationContext
import app.notmumla.data.db.ServerEntity
import app.notmumla.protocol.ConnectConfig
import app.notmumla.protocol.MumbleClient
import app.notmumla.protocol.model.ConnectionState
import app.notmumla.protocol.model.ServerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Application-scoped owner of the live Mumble session. Holds at most one [MumbleClient], mirrors
 * its [ServerState] into [state], and persists connection results (fingerprint pin, last channel).
 */
@Singleton
class SessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identityStore: IdentityStore,
    private val serverDao: ServerDao,
    private val settingsRepo: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob())

    @Volatile private var settings: AppSettings = AppSettings()

    init {
        scope.launch {
            settingsRepo.settings.collect { s ->
                settings = s
                applyAudioSettings(s)
            }
        }
    }

    private fun applyAudioSettings(s: AppSettings) {
        engine?.let { eng ->
            eng.mode = s.transmissionMode
            eng.applyAudioSettings(
                micGain = Math.pow(10.0, s.micGainDb / 20.0).toFloat(),
                vadThreshold = s.vadSensitivity,
                bitrate = s.audioBitrate,
                noiseSuppression = s.noiseSuppression,
                echoCancellation = s.echoCancellation,
            )
        }
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val router = AudioRouter(context).also { it.startObserving() }

    /** Output routes selectable given current hardware (incl. the two Bluetooth modes). */
    val availableRoutes: StateFlow<List<OutputRoute>> = router.available
    val currentRoute: StateFlow<OutputRoute> = router.current

    private val _state = MutableStateFlow(ServerState())
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private val _serverLabel = MutableStateFlow("")
    val serverLabel: StateFlow<String> = _serverLabel.asStateFlow()

    val events = MutableSharedFlow<MumbleClient.Event>(extraBufferCapacity = 32)

    private val _chat = MutableStateFlow<List<ChatLine>>(emptyList())
    val chat: StateFlow<List<ChatLine>> = _chat.asStateFlow()

    private val _unread = MutableStateFlow(0)
    val unreadChat: StateFlow<Int> = _unread.asStateFlow()

    private var chatId = 0L

    private var client: MumbleClient? = null
    private var engine: AudioEngine? = null
    private var mirrorJob: Job? = null
    private var eventJob: Job? = null
    private var audioStarted = false
    private var activeServerId: Long? = null

    val activeClient: MumbleClient? get() = client

    /** Remote sessions currently transmitting (drives speaking indicators). */
    private val _speaking = MutableStateFlow<Set<Int>>(emptySet())
    val speakingSessions: StateFlow<Set<Int>> = _speaking.asStateFlow()

    private val _localTransmitting = MutableStateFlow(false)
    val localTransmitting: StateFlow<Boolean> = _localTransmitting.asStateFlow()

    /** True once RECORD_AUDIO is granted and the engine has been started. */
    var audioPermissionGranted = false
        private set

    private var lastServer: ServerEntity? = null
    private var userInitiatedDisconnect = false
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    private var lastKnownChannelId: Int? = null

    /** Connect to a saved server, generating the identity on first use. */
    fun connect(server: ServerEntity) {
        reconnectAttempts = 0
        lastKnownChannelId = null
        doConnect(server)
    }

    private fun doConnect(server: ServerEntity) {
        teardown(toState = ConnectionState.CONNECTING)
        userInitiatedDisconnect = false
        lastServer = server
        activeServerId = server.id.takeIf { it != 0L }
        _serverLabel.value = server.label.ifBlank { server.host }

        val identity = identityStore.getOrCreate(commonName = server.username)
        val mc = MumbleClient(
            identity = identity,
            scope = scope,
            osVersion = Build.VERSION.RELEASE ?: "",
        )
        client = mc

        val eng = AudioEngine(audioManager) { opus, terminator -> mc.sendAudio(opus, terminator) }
        mc.voiceSink = eng
        engine = eng
        applyAudioSettings(settings)
        // Apply the current route (configures AudioManager mode/device + engine) before audio starts.
        selectRoute(router.current.value)
        scope.launch { eng.speakingSessions.collect { _speaking.value = it } }
        scope.launch { eng.transmitting.collect { _localTransmitting.value = it } }

        mirrorJob = scope.launch {
            mc.state.collect { s ->
                s.self?.channelId?.let { lastKnownChannelId = it }
                if (s.connection == ConnectionState.FAILED && shouldReconnect()) {
                    // Suppress the failure from the UI and retry instead of dropping to Connect.
                    _state.value = s.copy(connection = ConnectionState.CONNECTING, error = null)
                    scheduleReconnect(server)
                } else {
                    _state.value = s
                    if (s.connection == ConnectionState.CONNECTED) onConnected(server, s)
                }
            }
        }
        eventJob = scope.launch {
            mc.events.collect { event ->
                if (event is MumbleClient.Event.Text) onIncomingText(event.text)
                events.tryEmit(event)
            }
        }

        mc.connect(
            ConnectConfig(
                host = server.host,
                port = server.port,
                username = server.username,
                password = server.password,
                pinnedServerSha256 = server.pinnedSha256,
            ),
        )
    }

    private fun onConnected(server: ServerEntity, state: ServerState) {
        if (audioPermissionGranted && !audioStarted) startAudio()
        // After a reconnect, return to the channel we were in before the drop.
        val target = lastKnownChannelId
        if (reconnectAttempts > 0 && target != null && target != state.self?.channelId) {
            client?.joinChannel(target)
        }
        reconnectAttempts = 0
        val id = activeServerId ?: return
        scope.launch {
            val fp = state.serverFingerprintSha256
            if (fp != null && server.pinnedSha256 == null) serverDao.setPin(id, fp)
            serverDao.markConnected(id, System.currentTimeMillis(), state.self?.channelId)
        }
    }

    private fun shouldReconnect(): Boolean =
        settings.autoReconnect && !userInitiatedDisconnect && lastServer != null &&
            reconnectAttempts < MAX_RECONNECT_ATTEMPTS

    private fun scheduleReconnect(server: ServerEntity) {
        reconnectAttempts += 1
        val backoffMs = (1000L * (1 shl (reconnectAttempts - 1).coerceAtMost(4))).coerceAtMost(15_000)
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            kotlinx.coroutines.delay(backoffMs)
            if (!userInitiatedDisconnect && lastServer != null) doConnect(server)
        }
    }

    /** Called by the UI once RECORD_AUDIO is granted; (re)starts the capture/playback engine. */
    fun onAudioPermissionGranted() {
        audioPermissionGranted = true
        if (!audioStarted && _state.value.connection == ConnectionState.CONNECTED) startAudio()
    }

    private fun startAudio() {
        engine?.start()
        audioStarted = true
        // Start the foreground service now that RECORD_AUDIO is granted (FGS microphone type).
        app.notmumla.service.VoiceService.start(context)
    }

    // Engine controls surfaced to the UI.
    fun setPttHeld(held: Boolean) { engine?.setPttHeld(held) }
    fun setTransmissionMode(mode: TransmissionMode) { engine?.mode = mode }
    fun setMicMuted(muted: Boolean) { engine?.muted = muted }

    val autoReconnectEnabled: Boolean get() = settings.autoReconnect

    /** Switch the audio output route (phone / wired / Bluetooth HQ / Bluetooth headset). */
    fun selectRoute(route: OutputRoute) {
        val config = router.select(route)
        engine?.applyRoute(config)
    }

    fun joinChannel(channelId: Int) = client?.joinChannel(channelId)

    fun setSelfMuteDeaf(mute: Boolean, deaf: Boolean) = client?.setSelfMuteDeaf(mute, deaf)

    fun sendText(channelId: Int, message: String) {
        client?.sendText(channelId, message)
        // The server does not echo our own messages back, so add it locally.
        appendChat(ChatLine(chatId++, "You", message, System.currentTimeMillis(), isMe = true))
        markChatRead()
    }

    private fun onIncomingText(text: app.notmumla.protocol.IncomingText) {
        val selfSession = _state.value.sessionId
        if (text.actorSession != null && text.actorSession == selfSession) return // our own echo, if any
        val name = text.actorSession?.let { _state.value.users[it]?.name } ?: "Server"
        val clean = stripHtml(text.message)
        appendChat(
            ChatLine(
                id = chatId++,
                senderName = name,
                text = clean,
                timeMillis = System.currentTimeMillis(),
                isSystem = text.actorSession == null,
            ),
        )
        _unread.value += 1
    }

    private fun appendChat(line: ChatLine) {
        _chat.value = (_chat.value + line).takeLast(500)
    }

    /** Mark the chat as read (call when the Chat tab is shown). */
    fun markChatRead() { _unread.value = 0 }

    /** Strip server-permitted HTML to plain text for display. */
    private fun stripHtml(html: String): String =
        html.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
            .trim()

    /** User-initiated disconnect: tears down and suppresses auto-reconnect. */
    fun disconnect() {
        userInitiatedDisconnect = true
        lastServer = null
        reconnectJob?.cancel()
        teardown(ConnectionState.DISCONNECTED)
        _chat.value = emptyList()
        _unread.value = 0
    }

    /** Cleanup shared by user disconnect and reconnect; [toState] is the resulting connection state. */
    private fun teardown(toState: ConnectionState) {
        mirrorJob?.cancel()
        eventJob?.cancel()
        engine?.stop()
        engine = null
        audioStarted = false
        router.reset()
        client?.disconnect()
        client = null
        activeServerId = null
        _speaking.value = emptySet()
        _localTransmitting.value = false
        _state.value = ServerState(connection = toState)
        app.notmumla.service.VoiceService.stop(context)
    }

    private companion object {
        const val MAX_RECONNECT_ATTEMPTS = 8
    }
}
