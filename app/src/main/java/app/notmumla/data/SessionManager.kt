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
) {
    private val scope = CoroutineScope(SupervisorJob())

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

    /** Connect to a saved server, generating the identity on first use. */
    fun connect(server: ServerEntity) {
        disconnect()
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
        eng.applyRoute(router.configFor(router.current.value))
        mc.voiceSink = eng
        engine = eng
        scope.launch { eng.speakingSessions.collect { _speaking.value = it } }
        scope.launch { eng.transmitting.collect { _localTransmitting.value = it } }

        mirrorJob = scope.launch {
            mc.state.collect { s ->
                _state.value = s
                if (s.connection == ConnectionState.CONNECTED) onConnected(server, s)
            }
        }
        eventJob = scope.launch { mc.events.collect { events.tryEmit(it) } }

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
        val id = activeServerId ?: return
        scope.launch {
            val fp = state.serverFingerprintSha256
            if (fp != null && server.pinnedSha256 == null) serverDao.setPin(id, fp)
            serverDao.markConnected(id, System.currentTimeMillis(), state.self?.channelId)
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

    /** Switch the audio output route (phone / wired / Bluetooth HQ / Bluetooth headset). */
    fun selectRoute(route: OutputRoute) {
        val config = router.select(route)
        engine?.applyRoute(config)
    }

    fun joinChannel(channelId: Int) = client?.joinChannel(channelId)

    fun setSelfMuteDeaf(mute: Boolean, deaf: Boolean) = client?.setSelfMuteDeaf(mute, deaf)

    fun sendText(channelId: Int, message: String) = client?.sendText(channelId, message)

    fun disconnect() {
        mirrorJob?.cancel()
        eventJob?.cancel()
        engine?.stop()
        engine = null
        audioStarted = false
        client?.disconnect()
        client = null
        activeServerId = null
        _speaking.value = emptySet()
        _localTransmitting.value = false
        _state.value = ServerState(connection = ConnectionState.DISCONNECTED)
        app.notmumla.service.VoiceService.stop(context)
    }
}
