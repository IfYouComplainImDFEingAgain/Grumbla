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
import app.notmumla.audio.routing.MicContentionMonitor
import app.notmumla.audio.routing.OutputRoute
import app.notmumla.data.db.ServerDao
import dagger.hilt.android.qualifiers.ApplicationContext
import app.notmumla.data.db.ServerEntity
import app.notmumla.protocol.ConnectConfig
import app.notmumla.protocol.MumbleClient
import app.notmumla.protocol.model.ConnectionState
import app.notmumla.protocol.model.ServerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    private val _userVolumes = MutableStateFlow<Map<String, Float>>(emptyMap())
    /** Local per-user volume adjustments (username -> gain in dB). */
    val userVolumes: StateFlow<Map<String, Float>> = _userVolumes.asStateFlow()

    init {
        scope.launch {
            settingsRepo.settings.collect { s ->
                val mediaChanged = router.mediaVolume != s.mediaVolume
                settings = s
                router.mediaVolume = s.mediaVolume
                if (mediaChanged) scope.launch(Dispatchers.Main) { if (engine != null) applyRoute(router.current.value) }
                applyAudioSettings(s)
                updateMicSharing()
                // Keep an active mic preview in sync with live setting changes (gain especially).
                micMonitor.micGain = Math.pow(10.0, s.micGainDb / 20.0).toFloat()
                micMonitor.noiseReductionMix = s.noiseReduction
            }
        }
        scope.launch {
            settingsRepo.userVolumes.collect { _userVolumes.value = it; applyUserVolumes() }
        }
    }

    /** Push each connected user's saved volume into the mixer (by session). No-op if not connected. */
    private fun applyUserVolumes() {
        val eng = engine ?: return
        val vols = _userVolumes.value
        _state.value.users.values.forEach { u ->
            val db = vols[u.name] ?: 0f
            eng.setUserVolume(u.session, Math.pow(10.0, db / 20.0).toFloat())
        }
    }

    /** Set a user's local volume in dB (0 = default); persisted by name and applied live. */
    fun setUserVolume(name: String, db: Float) {
        scope.launch { settingsRepo.setUserVolume(name, db) }
        val mult = Math.pow(10.0, db / 20.0).toFloat()
        engine?.let { eng ->
            _state.value.users.values.filter { it.name == name }.forEach { eng.setUserVolume(it.session, mult) }
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
                audioLeveling = s.audioLeveling,
            )
        }
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val router = AudioRouter(context).also { it.startObserving() }

    /** Output routes selectable given current hardware (incl. the two Bluetooth modes). */
    val availableRoutes: StateFlow<List<OutputRoute>> = router.available
    val currentRoute: StateFlow<OutputRoute> = router.current

    init {
        // Route changes restart the engine's audio threads, so serialize them on the main thread
        // alongside UI-initiated selections.
        scope.launch(Dispatchers.Main) {
            var prev = router.available.value
            router.available.collect { now ->
                onRoutesChanged(prev, now)
                prev = now
            }
        }
        scope.launch(Dispatchers.Main) {
            settingsRepo.settings.collect { s ->
                // Out of a call, keep the displayed route in step with what the next connect will use.
                if (engine == null) router.markCurrent(startupRoute(s))
            }
        }
    }

    /** Route a fresh connection starts on: the remembered one, else the first available by priority. */
    private fun startupRoute(s: AppSettings = settings): OutputRoute {
        val available = router.available.value
        if (s.rememberLastRoute) s.lastRoute?.takeIf { it in available }?.let { return it }
        return bestByPriority(available, s)
    }

    private fun bestByPriority(available: List<OutputRoute>, s: AppSettings = settings): OutputRoute =
        s.routePriority.firstOrNull { it in available } ?: OutputRoute.PHONE_SPEAKER

    private fun onRoutesChanged(prev: List<OutputRoute>, now: List<OutputRoute>) {
        val current = router.current.value
        val btAppeared = now.any { it.isBluetooth && it !in prev }
        val target = when {
            // A Bluetooth device just connected: move to its best route (A2DP and SCO often appear
            // in separate callbacks, so this may step from one to the higher-priority other).
            btAppeared && settings.autoSwitchBluetooth && engine != null ->
                settings.routePriority.firstOrNull { it.isBluetooth && it in now }
            // The active route's hardware went away: walk down the priority list.
            current !in now -> bestByPriority(now)
            // Not in a call: just track what the next connect would pick.
            engine == null -> startupRoute()
            else -> null
        }
        if (target != null && target != current) applyRoute(target)
    }

    private val OutputRoute.isBluetooth
        get() = this == OutputRoute.BT_A2DP_HQ || this == OutputRoute.BT_HEADSET_SCO

    /** Switch routes; only touches AudioManager/engine while a call is live. */
    private fun applyRoute(route: OutputRoute) {
        val eng = engine
        if (eng == null) {
            router.markCurrent(route)
        } else {
            val yielded = _micYielded.value
            eng.applyRoute(router.select(route, shareMic = yielded), captureEnabled = !yielded)
        }
    }

    private val micContention = MicContentionMonitor(audioManager) { engine?.isOwnSession(it) == true }

    private val _micYielded = MutableStateFlow(false)
    /** True while another app is recording and we've handed it the mic (playback continues). */
    val micYielded: StateFlow<Boolean> = _micYielded.asStateFlow()

    init {
        scope.launch(Dispatchers.Main) {
            micContention.otherAppRecording.collect { updateMicSharing() }
        }
    }

    /**
     * Release or retake the mic. Another app's capture is silenced (not refused) while we hold call
     * mode or a privacy-sensitive capture, so yielding means stopping capture *and* dropping call mode.
     */
    private fun updateMicSharing() {
        scope.launch(Dispatchers.Main) {
            val want = engine != null && settings.shareMic && micContention.otherAppRecording.value
            if (want == _micYielded.value) return@launch
            _micYielded.value = want
            if (engine != null) applyRoute(router.current.value)
        }
    }

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
    private var lastKnownListening: Set<Int> = emptySet()

    /** Connect to a saved server, generating the identity on first use. */
    fun connect(server: ServerEntity) {
        // A fresh user-initiated connection supersedes any pending auto-reconnect to a prior server.
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempts = 0
        lastKnownChannelId = null
        lastKnownListening = emptySet()
        // Auto-reconnects keep whatever route is active; only a fresh connect re-picks it.
        router.markCurrent(startupRoute())
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

        var spurtTarget: Int? = null
        val eng = AudioEngine(audioManager) { opus, terminator ->
            // Lock the target for the whole talk spurt: toggling whisper mid-sentence must not split
            // one spurt across two targets (the first would never get its terminator).
            val target = spurtTarget ?: (if (whisperTo != null) WHISPER_TARGET_ID else 0).also { spurtTarget = it }
            mc.sendAudio(opus, terminator, target)
            if (terminator) spurtTarget = null
        }
        mc.voiceSink = eng
        engine = eng
        applyAudioSettings(settings)
        // Apply the current route (configures AudioManager mode/device + engine) before audio starts.
        applyRoute(router.current.value)
        scope.launch { eng.speakingSessions.collect { _speaking.value = it } }
        scope.launch { eng.transmitting.collect { _localTransmitting.value = it } }
        scope.launch { eng.inputLevel.collect { _inputLevel.value = it } }
        scope.launch { mc.stats.collect { _debugStats.value = it } }

        mirrorJob = scope.launch {
            mc.state.collect { s ->
                s.self?.let {
                    lastKnownChannelId = it.channelId
                    lastKnownListening = it.listeningChannels
                }
                if (s.connection == ConnectionState.FAILED &&
                    s.certMismatchFingerprint == null && !s.fatal && shouldReconnect()
                ) {
                    // Suppress the failure from the UI and retry instead of dropping to Connect.
                    _state.value = s.copy(connection = ConnectionState.CONNECTING, error = null)
                    scheduleReconnect(server)
                } else {
                    _state.value = s
                    applyUserVolumes() // new/moved users pick up their saved volume
                    _whisper.value?.let { w -> if (w.session !in s.users) stopWhisper() }
                    _privateChat.value?.let { p ->
                        if (p.session !in s.users) {
                            stopPrivateChat()
                            appendChat(ChatLine(chatId++, "", "${p.name} left — private chat closed",
                                System.currentTimeMillis(), isSystem = true))
                        }
                    }
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
        // Unregistered users' listeners die with the session; restore them too.
        if (reconnectAttempts > 0) {
            (lastKnownListening - state.self?.listeningChannels.orEmpty())
                .forEach { client?.setListening(it, true) }
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
        micContention.start()
        engine?.start()
        audioStarted = true
        // Start the foreground service now that RECORD_AUDIO is granted (FGS microphone type).
        app.notmumla.service.VoiceService.start(context)
    }

    private val _whisper = MutableStateFlow<UserRef?>(null)
    /** The user we're currently whispering to (all our outgoing voice goes only to them), or null. */
    val whisper: StateFlow<UserRef?> = _whisper.asStateFlow()
    @Volatile private var whisperTo: UserRef? = null

    /** Route our voice privately to [session] until [stopWhisper] (or they leave / we disconnect). */
    fun startWhisper(session: Int, name: String) {
        val mc = client ?: return
        mc.setWhisperTarget(WHISPER_TARGET_ID, listOf(session))
        UserRef(session, name).let { whisperTo = it; _whisper.value = it }
    }

    fun stopWhisper() {
        whisperTo = null
        _whisper.value = null
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

    /** User picked an output route (phone / wired / Bluetooth HQ / Bluetooth headset). */
    fun selectRoute(route: OutputRoute) {
        applyRoute(route)
        scope.launch { settingsRepo.setLastRoute(route) }
    }

    fun joinChannel(channelId: Int) = client?.joinChannel(channelId)
    fun setListening(channelId: Int, listen: Boolean) = client?.setListening(channelId, listen)

    fun setSelfMuteDeaf(mute: Boolean, deaf: Boolean) = client?.setSelfMuteDeaf(mute, deaf)

    private val _privateChat = MutableStateFlow<UserRef?>(null)
    /** While set, the chat composer sends private messages to this user instead of the channel. */
    val privateChat: StateFlow<UserRef?> = _privateChat.asStateFlow()

    /**
     * Open a private chat with [name]. Session ids are reassigned on reconnect, so an old message's
     * [session] may be stale or now belong to someone else: resolve by name when it doesn't match.
     */
    fun startPrivateChat(session: Int, name: String) {
        val users = _state.value.users
        val user = users[session]?.takeIf { it.name == name } ?: users.values.firstOrNull { it.name == name }
        if (user == null) {
            appendChat(ChatLine(chatId++, "", "$name is not connected", System.currentTimeMillis(), isSystem = true))
            return
        }
        _privateChat.value = UserRef(user.session, user.name)
    }
    fun stopPrivateChat() { _privateChat.value = null }

    /** Send [message] to the open private chat if there is one, else to [channelId]. */
    /** Send [message] (Markdown), or the rich composer's ready-made [html] with [message] as its plain text. */
    fun sendText(channelId: Int, message: String, html: String? = null) {
        val mc = client ?: return
        val to = _privateChat.value
        // Chat is HTML on the wire; like the desktop client, the composer's text is Markdown.
        val html = html ?: ChatMarkdown.toHtml(message)
        if (to != null) mc.sendPrivateText(to.session, html) else mc.sendText(channelId, html)
        // The server does not echo our own messages back, so add it locally.
        appendChat(
            ChatLine(chatId++, "You", message, System.currentTimeMillis(), isMe = true,
                html = html, privatePeer = to),
        )
        markChatRead()
    }

    /** Load, compress and send [uri] as an inline image (to the open private chat, else [channelId]). */
    fun sendImage(channelId: Int, uri: android.net.Uri) {
        // Capture the target now: the user may close the private chat while we compress.
        val to = _privateChat.value
        scope.launch {
            val bytes = ImageUtil.loadCompressed(context, uri, _state.value.imageMessageLength)
                ?: return@launch
            val mc = client ?: return@launch
            val html = ImageUtil.toImageHtml(bytes)
            if (to != null) mc.sendPrivateText(to.session, html) else mc.sendText(channelId, html)
            appendChat(
                ChatLine(chatId++, "You", "", System.currentTimeMillis(), isMe = true, imageBytes = bytes,
                    privatePeer = to),
            )
            markChatRead()
        }
    }

    private fun onIncomingText(text: app.notmumla.protocol.IncomingText) {
        val selfSession = _state.value.sessionId
        if (text.actorSession != null && text.actorSession == selfSession) return // our own echo, if any
        val name = text.actorSession?.let { _state.value.users[it]?.name } ?: "Server"
        val image = ImageUtil.extractImage(text.message)
        val html = if (image != null) ImageUtil.stripImageTags(text.message) else text.message
        val clean = HtmlText.strip(html)
        appendChat(
            ChatLine(
                id = chatId++,
                senderName = name,
                text = clean,
                html = html,
                timeMillis = System.currentTimeMillis(),
                isSystem = text.actorSession == null,
                imageBytes = image,
                privatePeer = text.actorSession?.takeIf { text.isPrivate }?.let { UserRef(it, name) },
            ),
        )
        _unread.value += 1

        val myName = _state.value.self?.name ?: lastServer?.username
        val mention = myName != null && clean.contains(myName, ignoreCase = true)
        val says = if (text.isPrivate) "privately says" else "says"
        val spoken = if (clean.isBlank() && image != null) "$name sent an image" else "$name $says $clean"
        if (settings.ttsReadAloud && (clean.isNotBlank() || image != null)) tts.speak(spoken)
        // Notify on private messages and mentions (plain channel messages would be too noisy).
        val preview = clean.ifBlank { if (image != null) "Sent an image" else "" }
        if (text.isPrivate) {
            notifier.postMessage(sender = name, text = preview, mention = false, isPrivate = true, sound = settings.mentionSound)
        } else if (mention) {
            notifier.postMessage(sender = name, text = clean, mention = true, sound = settings.mentionSound)
        }
    }

    private fun appendChat(line: ChatLine) {
        _chat.value = (_chat.value + line).takeLast(500)
    }

    /** Remove a message from our own history only; Mumble has no way to retract a sent message. */
    fun deleteChat(id: Long) {
        _chat.value = _chat.value.filterNot { it.id == id }
    }

    /** Drop our whole local chat history (again local only; others keep theirs). */
    fun clearChat() {
        _chat.value = emptyList()
        _unread.value = 0
    }

    /** Mark the chat as read (call when the Chat tab is shown). */
    fun markChatRead() { _unread.value = 0 }

    /** User-initiated disconnect: tears down and suppresses auto-reconnect. */
    fun disconnect() {
        userInitiatedDisconnect = true
        lastServer = null
        reconnectJob?.cancel()
        teardown(ConnectionState.DISCONNECTED)
        router.markCurrent(startupRoute())
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
        micContention.stop()
        _micYielded.value = false
        router.reset()
        client?.disconnect()
        client = null
        activeServerId = null
        _speaking.value = emptySet()
        stopWhisper() // session ids don't survive a reconnect
        stopPrivateChat()
        _localTransmitting.value = false
        _inputLevel.value = 0f
        _debugStats.value = app.notmumla.protocol.model.AudioDebugStats()
        _state.value = ServerState(connection = toState)
        // Keep the FGS alive across reconnects: a microphone FGS restarted while the screen is off
        // is denied mic access (while-in-use rule), so reconnecting would leave us silently muted.
        if (toState == ConnectionState.DISCONNECTED) app.notmumla.service.VoiceService.stop(context)
    }

    private companion object {
        const val MAX_RECONNECT_ATTEMPTS = 8
        /** Voice target slot we register for whispers (0 = normal talk, 31 = server loopback). */
        const val WHISPER_TARGET_ID = 1
        const val CALIBRATION_MS = 5000L
    }
}

/** A user by session id (whisper / private-chat peer); [name] is kept for display. */
data class UserRef(val session: Int, val name: String)
