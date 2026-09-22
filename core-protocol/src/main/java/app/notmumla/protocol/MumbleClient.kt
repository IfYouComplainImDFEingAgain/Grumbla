package app.notmumla.protocol

import app.notmumla.protocol.identity.Identity
import app.notmumla.protocol.identity.IdentityCertificate
import app.notmumla.protocol.model.AudioDebugStats
import app.notmumla.protocol.model.Channel
import app.notmumla.protocol.model.ConnectionState
import app.notmumla.protocol.model.ServerState
import app.notmumla.protocol.model.User
import app.notmumla.protocol.net.ControlChannel
import app.notmumla.protocol.net.TofuTrustManager
import app.notmumla.protocol.proto.MessageType
import app.notmumla.protocol.udp.CryptStateOCB2
import app.notmumla.protocol.udp.LegacyAudio
import app.notmumla.protocol.udp.OpusPacket
import app.notmumla.protocol.udp.UdpTransport
import MumbleUDP.Audio as UdpAudio
import com.squareup.wire.Message
import okio.ByteString.Companion.toByteString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import MumbleProto.Authenticate
import MumbleProto.ChannelRemove
import MumbleProto.CryptSetup
import MumbleProto.ChannelState
import MumbleProto.Ping
import MumbleProto.Reject
import MumbleProto.ServerConfig
import MumbleProto.ServerSync
import MumbleProto.TextMessage
import MumbleProto.UserRemove
import MumbleProto.UserState
import MumbleProto.Version as PVersion

/** Receives decoded incoming voice frames from the active session. */
interface VoiceSink {
    fun onIncomingAudio(session: Int, sequence: Long, opus: ByteArray, terminator: Boolean)
}

/** Parameters for a single connection attempt. */
data class ConnectConfig(
    val host: String,
    val port: Int = Mumble.DEFAULT_PORT,
    val username: String,
    val password: String? = null,
    val tokens: List<String> = emptyList(),
    /** Pinned server fingerprint (SHA-256). Null on first connection (trust-on-first-use). */
    val pinnedServerSha256: String? = null,
)

/** A chat message received from the server. */
data class IncomingText(val actorSession: Int?, val message: String)

/**
 * Drives one Mumble session: TLS connect, the login handshake (Version → Authenticate → …
 * → ServerSync), live channel/user state, keep-alive pings, and inbound text messages.
 *
 * State is exposed as [state]; transient events (text, rejections) via [events].
 */
class MumbleClient(
    private val identity: Identity,
    private val scope: CoroutineScope,
    private val clientVersion: Long = encodeVersion(1, 5, 0),
    private val clientName: String = "not-mumla",
    private val osName: String = "Android",
    private val osVersion: String = "",
) {
    private val keyStorePassword = "in-memory".toCharArray()

    private val _state = MutableStateFlow(ServerState())
    val state: StateFlow<ServerState> = _state.asStateFlow()

    val events = MutableSharedFlow<Event>(extraBufferCapacity = 32)

    sealed interface Event {
        data class Text(val text: IncomingText) : Event
        data class Rejected(val reason: String) : Event
        data class Disconnected(val cause: String?) : Event
    }

    private var channel: ControlChannel? = null
    private var readJob: Job? = null
    private var pingJob: Job? = null
    private var statsJob: Job? = null

    // Direct UDP voice path (OCB2-encrypted). Falls back to the TCP tunnel until UDP is confirmed.
    private val crypt = CryptStateOCB2()
    @Volatile private var udp: UdpTransport? = null
    @Volatile private var udpActive = false
    @Volatile private var connectConfig: ConnectConfig? = null

    // Live audio packet stats (debug overlay).
    private val _stats = MutableStateFlow(AudioDebugStats())
    val stats: StateFlow<AudioDebugStats> = _stats.asStateFlow()
    @Volatile private var txCount = 0L
    @Volatile private var rxCount = 0L
    @Volatile private var rxLost = 0L
    /** Per session: the frame number the next packet should carry (null = start of a talk-spurt). */
    private val expectedSeqBySession = HashMap<Int, Long>()

    /** Set by the audio engine to receive inbound voice frames. */
    @Volatile var voiceSink: VoiceSink? = null

    private var audioSequence = 0L

    /** Serial lane for UI-initiated control sends (preserves mute→deafen / join ordering). */
    private val sendDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** The server's protocol version, learned from its Version message during the handshake. */
    @Volatile private var serverVersion: Long = Long.MAX_VALUE
    /**
     * Use the legacy audio format when either side predates protobuf (1.5.0): old servers only know
     * legacy, and a ≥1.5 server decodes our audio according to *our* advertised version.
     */
    private val useLegacyAudio: Boolean
        get() = serverVersion < PROTOBUF_VERSION || clientVersion < PROTOBUF_VERSION

    fun connect(config: ConnectConfig) {
        connectConfig = config
        _state.update { ServerState(connection = ConnectionState.CONNECTING) }
        readJob = scope.launch(Dispatchers.IO) {
            val trust = TofuTrustManager(config.pinnedServerSha256)
            val control = ControlChannel(trust)
            channel = control
            try {
                control.connect(
                    host = config.host,
                    port = config.port,
                    keyStore = IdentityCertificate.toKeyStore(identity, keyStorePassword),
                    keyStorePassword = keyStorePassword,
                )
                _state.update {
                    it.copy(
                        connection = ConnectionState.HANDSHAKING,
                        serverFingerprintSha256 = trust.observedSha256,
                    )
                }
                sendHandshake(control, config)
                startPing()
                startStats()
                readLoop(control)
            } catch (t: Throwable) {
                val mismatch = generateSequence(t) { it.cause }
                    .any { it.message?.contains("fingerprint mismatch", ignoreCase = true) == true }
                if (isActive) {
                    _state.update {
                        it.copy(
                            connection = ConnectionState.FAILED,
                            error = if (mismatch) "Server certificate changed" else t.message,
                            certMismatchFingerprint = if (mismatch) trust.observedSha256 else null,
                        )
                    }
                    events.tryEmit(Event.Disconnected(t.message))
                }
            } finally {
                pingJob?.cancel()
                statsJob?.cancel()
                udp?.stop()
                udp = null
                udpActive = false
            }
        }
    }

    /** Emit a packet-stats snapshot ~twice a second, computing per-second rates from the deltas. */
    private fun startStats() {
        txCount = 0; rxCount = 0; rxLost = 0
        expectedSeqBySession.clear()
        _stats.value = AudioDebugStats()
        statsJob = scope.launch(Dispatchers.IO) {
            var lastTx = 0L
            var lastRx = 0L
            while (isActive) {
                delay(500)
                val tx = txCount
                val rx = rxCount
                _stats.value = AudioDebugStats(
                    sent = tx, received = rx, lost = rxLost,
                    sentPerSec = ((tx - lastTx) * 2).toInt(),
                    recvPerSec = ((rx - lastRx) * 2).toInt(),
                    udp = udpActive,
                )
                lastTx = tx
                lastRx = rx
            }
        }
    }

    /**
     * Track an inbound audio packet for the stats: count it and estimate lost *packets* from gaps.
     * `frame_number` counts 10 ms units, so each packet advances it by its own duration (2 for a
     * 20 ms packet, etc.) — compare against that, not +1. The sender's counter also keeps running
     * through silence, so the packet after a terminator starts a new spurt and is never a "gap".
     */
    private fun recordRx(session: Int, sequence: Long, opus: ByteArray, terminator: Boolean) {
        rxCount++
        val units = OpusPacket.tenMsUnits(opus)
        val expected = expectedSeqBySession[session]
        if (expected != null && sequence > expected && sequence - expected < 100) {
            rxLost += (sequence - expected + units - 1) / units
        }
        if (terminator) expectedSeqBySession.remove(session)
        // A late (reordered) packet mustn't pull the expectation backwards; a big backwards jump is
        // the sender's counter resetting, so resync.
        else if (expected == null || sequence + units > expected || expected - sequence > 100)
            expectedSeqBySession[session] = sequence + units
    }

    private fun sendHandshake(control: ControlChannel, config: ConnectConfig) {
        control.send(
            PVersion(
                version_v1 = legacyVersionOf(clientVersion), // old servers read the legacy field
                version_v2 = clientVersion,
                release = clientName,
                os = osName,
                os_version = osVersion,
            ),
        )
        control.send(
            Authenticate(
                username = config.username,
                password = config.password,
                opus = true,
                client_type = 0,
                tokens = config.tokens,
            ),
        )
    }

    private fun startPing() {
        pingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                // Only ping when the link has actually been idle ~6 s. During a call, audio/voice
                // traffic keeps it warm so no extra pings are sent (saves battery); when idle, the
                // 6 s keep-alive prevents the radio dropping into power-save and delaying the next
                // message. The 2 s tick is CPU-only and does not wake the radio.
                delay(2_000)
                val ch = channel ?: continue
                if (ch.idleMs() >= IDLE_PING_MS) {
                    runCatching { ch.send(Ping(timestamp = System.nanoTime() / 1000)) }
                }
            }
        }
    }

    private suspend fun readLoop(control: ControlChannel) {
        withContext(Dispatchers.IO) {
            while (isActive) {
                val frame = control.readFrame()
                handleFrame(frame)
            }
        }
    }

    private fun handleFrame(frame: ControlChannel.Frame) {
        when (MessageType.fromId(frame.typeId)) {
            MessageType.VERSION -> onServerVersion(PVersion.ADAPTER.decode(frame.payload))
            MessageType.CHANNEL_STATE -> onChannelState(ChannelState.ADAPTER.decode(frame.payload))
            MessageType.CHANNEL_REMOVE -> onChannelRemove(ChannelRemove.ADAPTER.decode(frame.payload))
            MessageType.USER_STATE -> onUserState(UserState.ADAPTER.decode(frame.payload))
            MessageType.USER_REMOVE -> onUserRemove(UserRemove.ADAPTER.decode(frame.payload))
            MessageType.SERVER_SYNC -> onServerSync(ServerSync.ADAPTER.decode(frame.payload))
            MessageType.TEXT_MESSAGE -> onText(TextMessage.ADAPTER.decode(frame.payload))
            MessageType.REJECT -> onReject(Reject.ADAPTER.decode(frame.payload))
            MessageType.SERVER_CONFIG -> onServerConfig(ServerConfig.ADAPTER.decode(frame.payload))
            MessageType.CRYPT_SETUP -> onCryptSetup(CryptSetup.ADAPTER.decode(frame.payload))
            // The UDPTunnel message body is the raw UDP audio packet, not a protobuf wrapper.
            MessageType.UDP_TUNNEL -> onAudioPacket(frame.payload)
            MessageType.PING -> Unit
            else -> Unit // unhandled types (ACL, stats, codec) — wired up in later milestones
        }
    }

    private fun onChannelState(msg: ChannelState) {
        val id = msg.channel_id ?: return
        _state.update { s ->
            val existing = s.channels[id]
            val updated = Channel(
                id = id,
                parent = msg.parent ?: existing?.parent,
                name = msg.name ?: existing?.name ?: "",
                position = msg.position ?: existing?.position ?: 0,
                temporary = msg.temporary ?: existing?.temporary ?: false,
                description = msg.description ?: existing?.description,
            )
            s.copy(channels = s.channels + (id to updated))
        }
    }

    private fun onChannelRemove(msg: ChannelRemove) {
        _state.update { it.copy(channels = it.channels - msg.channel_id) }
    }

    private fun onUserState(msg: UserState) {
        val session = msg.session ?: return
        _state.update { s ->
            val existing = s.users[session]
            val updated = (existing ?: User(session = session, name = msg.name ?: "")).copy(
                name = msg.name ?: existing?.name ?: "",
                channelId = msg.channel_id ?: existing?.channelId ?: 0,
                mute = msg.mute ?: existing?.mute ?: false,
                deaf = msg.deaf ?: existing?.deaf ?: false,
                suppress = msg.suppress ?: existing?.suppress ?: false,
                selfMute = msg.self_mute ?: existing?.selfMute ?: false,
                selfDeaf = msg.self_deaf ?: existing?.selfDeaf ?: false,
                prioritySpeaker = msg.priority_speaker ?: existing?.prioritySpeaker ?: false,
                comment = msg.comment ?: existing?.comment,
                certHash = msg.hash ?: existing?.certHash,
            )
            s.copy(users = s.users + (session to updated))
        }
    }

    private fun onUserRemove(msg: UserRemove) {
        // If the removed session is *us*, the server kicked or banned us — surface why instead of
        // letting the socket close into a generic "connection lost" a moment later.
        if (msg.session == _state.value.sessionId) {
            val banned = msg.ban == true
            val reason = msg.reason?.takeIf { it.isNotBlank() }
            val text = when {
                banned && reason != null -> "Banned: $reason"
                banned -> "Banned from the server"
                reason != null -> "Kicked: $reason"
                else -> "Removed from the server"
            }
            _state.update { it.copy(connection = ConnectionState.FAILED, error = text, fatal = true) }
            events.tryEmit(Event.Disconnected(text))
            return
        }
        _state.update { it.copy(users = it.users - msg.session) }
    }

    private fun onServerSync(msg: ServerSync) {
        _state.update {
            it.copy(
                connection = ConnectionState.CONNECTED,
                sessionId = msg.session,
                welcomeText = msg.welcome_text,
            )
        }
    }

    private fun onText(msg: TextMessage) {
        events.tryEmit(Event.Text(IncomingText(msg.actor, msg.message)))
    }

    private fun onServerVersion(msg: PVersion) {
        val v = msg.version_v2 ?: msg.version_v1?.let { legacyToFull(it) }
        if (v != null) serverVersion = v
    }

    private fun onServerConfig(msg: ServerConfig) {
        _state.update {
            it.copy(
                imageMessageLength = msg.image_message_length ?: it.imageMessageLength,
                allowHtml = msg.allow_html ?: it.allowHtml,
            )
        }
    }

    /**
     * Handle the server's CryptSetup for the UDP voice channel. A full setup (key + both nonces)
     * initializes OCB2 and starts the direct UDP path; a lone server_nonce is a decrypt-IV resync;
     * an empty message is the server asking for our encrypt IV, which we echo back.
     */
    private fun onCryptSetup(msg: CryptSetup) {
        val key = msg.key?.toByteArray()
        val clientNonce = msg.client_nonce?.toByteArray()
        val serverNonce = msg.server_nonce?.toByteArray()
        when {
            key != null && clientNonce != null && serverNonce != null -> {
                if (crypt.setKey(key, clientNonce, serverNonce)) startUdp()
            }
            serverNonce != null -> crypt.setDecryptIv(serverNonce)
            else -> runCatching {
                channel?.send(CryptSetup(client_nonce = crypt.encryptIvCopy().toByteString()))
            }
        }
    }

    /** Bring up the UDP transport (idempotent). Failure is silent — audio stays on the TCP tunnel. */
    private fun startUdp() {
        if (udp != null) return
        val config = connectConfig ?: return
        val transport = UdpTransport(
            host = config.host,
            port = config.port,
            crypt = crypt,
            scope = scope,
            legacy = useLegacyAudio,
            onAudio = { onAudioPacket(it) },
            onActiveChanged = { udpActive = it },
        )
        udp = transport
        runCatching { transport.start() }.onFailure { udp = null }
    }

    /**
     * Parse a raw inbound UDP audio packet and surface it. Auto-detects the format by the header
     * byte: protobuf Audio = `0x00`; legacy Opus = top 3 bits == 4 (`0x80`..`0x9F`).
     */
    private fun onAudioPacket(packet: ByteArray) {
        if (packet.isEmpty()) return
        when {
            packet[0].toInt() == UDP_TYPE_AUDIO -> { // protobuf
                val audio = runCatching { UdpAudio.ADAPTER.decode(packet.copyOfRange(1, packet.size)) }
                    .getOrNull() ?: return
                val opus = audio.opus_data.toByteArray()
                recordRx(audio.sender_session, audio.frame_number, opus, audio.is_terminator)
                voiceSink?.onIncomingAudio(
                    session = audio.sender_session,
                    sequence = audio.frame_number,
                    opus = opus,
                    terminator = audio.is_terminator,
                )
            }
            LegacyAudio.isLegacyOpus(packet) -> {
                val a = LegacyAudio.decodeIncoming(packet) ?: return
                recordRx(a.session, a.sequence, a.opus, a.terminator)
                voiceSink?.onIncomingAudio(a.session, a.sequence, a.opus, a.terminator)
            }
            // else: ping packets (protobuf type 1, legacy 0x20) — ignored
        }
    }

    /**
     * Send one encoded Opus frame to the server, as the raw body of an UDPTunnel control message.
     * Uses the Mumble 1.5 protobuf audio format for ≥1.5.0 servers and the legacy packet format for
     * older servers (which predate protobuf). target 0 = normal talking.
     */
    fun sendAudio(opus: ByteArray, terminator: Boolean, target: Int = 0) {
        val packet = if (useLegacyAudio) {
            LegacyAudio.encodeOutgoing(audioSequence, opus, terminator, target)
        } else {
            val body = UdpAudio.ADAPTER.encode(
                UdpAudio(
                    target = target,
                    frame_number = audioSequence,
                    opus_data = opus.toByteString(),
                    is_terminator = terminator,
                ),
            )
            ByteArray(body.size + 1).also {
                it[0] = UDP_TYPE_AUDIO.toByte()
                body.copyInto(it, 1)
            }
        }
        audioSequence += 1
        if (terminator) audioSequence = 0
        txCount++
        // Prefer the direct UDP path once it's confirmed; otherwise tunnel over the TCP control channel.
        val transport = udp
        if (transport != null && udpActive) {
            transport.sendAudio(packet)
        } else {
            runCatching { channel?.sendRaw(MessageType.UDP_TUNNEL.id, packet) }
        }
    }

    private fun onReject(msg: Reject) {
        val reason = msg.reason ?: msg.type?.name ?: "Connection rejected"
        _state.update { it.copy(connection = ConnectionState.FAILED, error = reason, fatal = true) }
        events.tryEmit(Event.Rejected(reason))
    }

    /**
     * Send a control message from any thread (typically the UI). A socket write on the main thread
     * throws NetworkOnMainThreadException *after* the frame is buffered, so it was silently swallowed
     * and only went out with the next keep-alive ping seconds later. One serial lane keeps ordering.
     */
    private fun sendAsync(message: Message<*, *>) {
        scope.launch(sendDispatcher) { runCatching { channel?.send(message) } }
    }

    /** Move our user to [channelId]. */
    fun joinChannel(channelId: Int) {
        val session = _state.value.sessionId ?: return
        sendAsync(UserState(session = session, channel_id = channelId))
    }

    /** Set our self-mute / self-deafen flags. */
    fun setSelfMuteDeaf(selfMute: Boolean, selfDeaf: Boolean) {
        val session = _state.value.sessionId ?: return
        sendAsync(UserState(session = session, self_mute = selfMute, self_deaf = selfDeaf))
    }

    /** Send a text message to [channelId]. */
    fun sendText(channelId: Int, message: String) {
        sendAsync(TextMessage(message = message, channel_id = listOf(channelId)))
    }

    fun disconnect() {
        readJob?.cancel()
        pingJob?.cancel()
        udp?.stop()
        udp = null
        udpActive = false
        channel?.close()
        channel = null
        _state.update { ServerState(connection = ConnectionState.DISCONNECTED) }
    }

    companion object {
        /** UDP message type prefix byte for the protobuf audio format (0 = Audio, 1 = Ping). */
        private const val UDP_TYPE_AUDIO = 0

        /** Send a keep-alive only after this much idle time on the control channel. */
        private const val IDLE_PING_MS = 6_000L

        /** Audio protobuf format was introduced in Mumble 1.5.0; older servers use the legacy one. */
        private val PROTOBUF_VERSION = encodeVersion(1, 5, 0)

        /** Pack a Mumble v2 version: 16 bits each for major/minor/patch in the high 48 bits. */
        fun encodeVersion(major: Int, minor: Int, patch: Int): Long =
            (major.toLong() shl 48) or (minor.toLong() shl 32) or (patch.toLong() shl 16)

        /** Legacy v1 version: major in bits 16-31, minor 8-15, patch 0-7. */
        private fun legacyVersionOf(v2: Long): Int {
            val major = (v2 ushr 48 and 0xFFFF).toInt()
            val minor = (v2 ushr 32 and 0xFFFF).toInt()
            val patch = (v2 ushr 16 and 0xFFFF).toInt()
            return (major shl 16) or (minor shl 8) or (patch and 0xFF)
        }

        /** Expand a legacy v1 version into the full 64-bit form for comparison. */
        private fun legacyToFull(v1: Int): Long =
            encodeVersion((v1 ushr 16) and 0xFFFF, (v1 ushr 8) and 0xFF, v1 and 0xFF)
    }
}
