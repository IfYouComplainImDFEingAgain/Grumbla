package app.notmumla.data

import android.content.Context
import android.media.AudioManager
import android.os.Build
import app.notmumla.audio.AudioEngine
import app.notmumla.audio.MicLevelMonitor
import app.notmumla.audio.MicTestState
import app.notmumla.audio.MicTester
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
    private val tts: TtsManager,
    private val notifier: Notifier,
) {
    private val scope = CoroutineScope(SupervisorJob())

    @Volatile private var settings: AppSettings = AppSettings()

    private val micMonitor = MicLevelMonitor()
    private var previewJob: Job? = null
    private var inSettingsAudio = false

    private val micTester = MicTester()
    private var testJob: Job? = null
    private val _micTestState = MutableStateFlow(MicTestState.IDLE)
    /** State of the mic self-test (record → gate → playback). */
    val micTestState: StateFlow<MicTestState> = _micTestState.asStateFlow()

    /** Whether the mic self-test can run now (only when not in a call — the engine holds the mic). */
    val canTestMic: Boolean get() = engine == null

    init {
        scope.launch {
            settingsRepo.settings.collect { s ->
                settings = s
                applyAudioSettings(s)
                // Keep an active mic preview in sync with live setting changes (gain especially).
                micMonitor.micGain = Math.pow(10.0, s.micGainDb / 20.0).toFloat()
                micMonitor.noiseReductionMix = s.noiseReduction
            }
        }
    }

    /**
     * Start a live mic-level preview for the Settings meter while NOT in a call. No-op if a session
     * is active (the engine already publishes the level) or the preview is already running.
     */
    fun startMicPreview() {
        inSettingsAudio = true
        // Connected: keep the engine capturing (for the meter) but stop sending to the server while
        // Settings is open.
        engine?.let { it.suppressTransmit = true; return }
        if (previewJob != null) return
        micMonitor.micGain = Math.pow(10.0, settings.micGainDb / 20.0).toFloat()
        micMonitor.noiseSuppression = settings.noiseSuppression == NoiseSuppression.STANDARD
        micMonitor.aiNoiseSuppression = settings.noiseSuppression == NoiseSuppression.AI
        micMonitor.noiseReductionMix = settings.noiseReduction
        micMonitor.autoGain = settings.autoGain
        micMonitor.start()
        previewJob = scope.launch { micMonitor.level.collect { _inputLevel.value = it } }
    }

    fun stopMicPreview() {
        inSettingsAudio = false
        engine?.suppressTransmit = false
        previewJob?.cancel()
        previewJob = null
        micMonitor.stop()
        if (engine == null) _inputLevel.value = 0f
    }

    private val _vadCalibrating = MutableStateFlow(false)
    /** True while auto-calibration is sampling the mic. */
    val vadCalibrating: StateFlow<Boolean> = _vadCalibrating.asStateFlow()
    private var calibrateJob: Job? = null

    /**
     * Auto-set the VAD threshold: sample the live input level for a few seconds while the user talks
     * normally, then place the threshold between the noise floor (quiet pauses) and speech level.
     */
    fun calibrateVad() {
        if (_vadCalibrating.value) return
        calibrateJob = scope.launch {
            _vadCalibrating.value = true
            if (engine == null) startMicPreview() // ensure we're capturing
            val samples = ArrayList<Float>(200)
            val end = System.currentTimeMillis() + CALIBRATION_MS
            while (System.currentTimeMillis() < end) {
                samples.add(_inputLevel.value)
                kotlinx.coroutines.delay(30)
            }
            computeVadThreshold(samples)?.let { settingsRepo.setVadSensitivity(it) }
            _vadCalibrating.value = false
        }
    }

    /**
     * Record a few seconds of mic audio, gate it through the current VAD settings, and play back only
     * what would transmit — so the user can hear whether beginnings/ends of sentences get clipped.
     * Only runs when not in a call (the engine otherwise holds the mic).
     */
    fun testMic() {
        if (engine != null || testJob != null) return
        testJob = scope.launch {
            // Free the mic from the level preview for the duration of the test.
            previewJob?.cancel(); previewJob = null; micMonitor.stop()
            micTester.micGain = Math.pow(10.0, settings.micGainDb / 20.0).toFloat()
            micTester.vadThreshold = settings.vadSensitivity
            micTester.autoSensitivity = settings.autoSensitivity
            micTester.run { _micTestState.value = it }
            testJob = null
            if (inSettingsAudio && engine == null) startMicPreview() // resume the meter
        }
    }

    /** Returns a threshold from sampled levels, or null if no real input was captured. */
    private fun computeVadThreshold(samples: List<Float>): Float? {
        val valid = samples.filter { it > 0.00002f }.sorted()
        if (valid.size < 10) return null // nothing captured (no permission / silence)
        fun pct(p: Double) = valid[(valid.size * p).toInt().coerceIn(0, valid.size - 1)]
        val noise = pct(0.2)   // quiet moments / pauses
        val speech = pct(0.85) // active speech
        val noiseDb = 20f * kotlin.math.log10(maxOf(noise, 1e-5f))
        val speechDb = 20f * kotlin.math.log10(maxOf(speech, 1e-5f))
        // Sit ~1/3 above the noise floor toward speech; if the user barely spoke, stay 6 dB above noise.
        val threshDb = if (speechDb - noiseDb < 6f) noiseDb + 6f
        else noiseDb + (speechDb - noiseDb) * 0.35f
        return Math.pow(10.0, (threshDb / 20f).toDouble()).toFloat().coerceIn(0.0005f, 0.15f)
    }

    private fun applyAudioSettings(s: AppSettings) {
        engine?.let { eng ->
            eng.mode = s.transmissionMode
            eng.applyAudioSettings(
                micGain = Math.pow(10.0, s.micGainDb / 20.0).toFloat(),
                vadThreshold = s.vadSensitivity,
                bitrate = s.audioBitrate,
                noiseSuppression = s.noiseSuppression == NoiseSuppression.STANDARD,
                aiNoiseSuppression = s.noiseSuppression == NoiseSuppression.AI,
                noiseReductionMix = s.noiseReduction,
                echoCancellation = s.echoCancellation,
                autoGain = s.autoGain,
                autoSensitivity = s.autoSensitivity,
                rawMic = s.rawMic,
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

    private val _inputLevel = MutableStateFlow(0f)
    /** Live mic input level (0..1) for VAD calibration in Settings. */
    val inputLevel: StateFlow<Float> = _inputLevel.asStateFlow()

    private val _debugStats = MutableStateFlow(app.notmumla.protocol.model.AudioDebugStats())
    /** Live audio packet stats for the debug overlay. */
    val debugStats: StateFlow<app.notmumla.protocol.model.AudioDebugStats> = _debugStats.asStateFlow()

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
        // A fresh user-initiated connection supersedes any pending auto-reconnect to a prior server.
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempts = 0
        lastKnownChannelId = null
        doConnect(server)
    }

    private fun doConnect(server: ServerEntity) {
        stopMicPreview() // release the mic so the capture engine can take it
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
        scope.launch { eng.inputLevel.collect { _inputLevel.value = it } }
        scope.launch { mc.stats.collect { _debugStats.value = it } }

        mirrorJob = scope.launch {
            mc.state.collect { s ->
                s.self?.channelId?.let { lastKnownChannelId = it }
                if (s.connection == ConnectionState.FAILED &&
                    s.certMismatchFingerprint == null && !s.fatal && shouldReconnect()
                ) {
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
            // Only reconnect if this is still the intended server (guards against a stale job
            // firing after the user switched servers).
            if (!userInitiatedDisconnect && lastServer === server) doConnect(server)
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
    fun setMicMuted(muted: Boolean) { engine?.muted = muted }

    val autoReconnectEnabled: Boolean get() = settings.autoReconnect

    /** Accept the server's changed certificate: update the pin and reconnect. */
    fun trustNewCertificate() {
        val server = lastServer ?: return
        val newFp = _state.value.certMismatchFingerprint ?: return
        reconnectAttempts = 0
        activeServerId?.let { id -> scope.launch { serverDao.setPin(id, newFp) } }
        doConnect(server.copy(pinnedSha256 = newFp))
    }

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

    /** Load, compress and send [uri] as an inline image to [channelId]. */
    fun sendImage(channelId: Int, uri: android.net.Uri) {
        scope.launch {
            val bytes = ImageUtil.loadCompressed(context, uri, _state.value.imageMessageLength)
                ?: return@launch
            client?.sendText(channelId, ImageUtil.toImageHtml(bytes))
            appendChat(
                ChatLine(chatId++, "You", "", System.currentTimeMillis(), isMe = true, imageBytes = bytes),
            )
            markChatRead()
        }
    }

    private fun onIncomingText(text: app.notmumla.protocol.IncomingText) {
        val selfSession = _state.value.sessionId
        if (text.actorSession != null && text.actorSession == selfSession) return // our own echo, if any
        val name = text.actorSession?.let { _state.value.users[it]?.name } ?: "Server"
        val image = ImageUtil.extractImage(text.message)
        val clean = stripHtml(if (image != null) ImageUtil.stripImageTags(text.message) else text.message)
        appendChat(
            ChatLine(
                id = chatId++,
                senderName = name,
                text = clean,
                timeMillis = System.currentTimeMillis(),
                isSystem = text.actorSession == null,
                imageBytes = image,
            ),
        )
        _unread.value += 1

        val myName = _state.value.self?.name ?: lastServer?.username
        val mention = myName != null && clean.contains(myName, ignoreCase = true)
        val spoken = if (clean.isBlank() && image != null) "$name sent an image" else "$name says $clean"
        if (settings.ttsReadAloud && (clean.isNotBlank() || image != null)) tts.speak(spoken)
        // Notify on mentions (plain channel messages would be too noisy).
        if (mention) notifier.postMessage(sender = name, text = clean, mention = true, sound = settings.mentionSound)
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
        _inputLevel.value = 0f
        _debugStats.value = app.notmumla.protocol.model.AudioDebugStats()
        _state.value = ServerState(connection = toState)
        app.notmumla.service.VoiceService.stop(context)
    }

    private companion object {
        const val MAX_RECONNECT_ATTEMPTS = 8
        const val CALIBRATION_MS = 5000L
    }
}
