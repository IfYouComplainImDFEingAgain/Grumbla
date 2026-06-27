package app.notmumla.protocol

import app.notmumla.protocol.identity.Identity
import app.notmumla.protocol.identity.IdentityCertificate
import app.notmumla.protocol.model.Channel
import app.notmumla.protocol.model.ConnectionState
import app.notmumla.protocol.model.ServerState
import app.notmumla.protocol.model.User
import app.notmumla.protocol.net.ControlChannel
import app.notmumla.protocol.net.TofuTrustManager
import app.notmumla.protocol.proto.MessageType
import MumbleUDP.Audio as UdpAudio
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
import MumbleProto.ChannelState
import MumbleProto.Ping
import MumbleProto.Reject
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

    /** Set by the audio engine to receive inbound voice frames. */
    @Volatile var voiceSink: VoiceSink? = null

    private var audioSequence = 0L

    fun connect(config: ConnectConfig) {
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
                readLoop(control)
            } catch (t: Throwable) {
                if (isActive) {
                    _state.update {
                        it.copy(connection = ConnectionState.FAILED, error = t.message)
                    }
                    events.tryEmit(Event.Disconnected(t.message))
                }
            } finally {
                pingJob?.cancel()
            }
        }
    }

    private fun sendHandshake(control: ControlChannel, config: ConnectConfig) {
        control.send(
            PVersion(
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
            MessageType.VERSION -> Unit // server version; ignored for now
            MessageType.CHANNEL_STATE -> onChannelState(ChannelState.ADAPTER.decode(frame.payload))
            MessageType.CHANNEL_REMOVE -> onChannelRemove(ChannelRemove.ADAPTER.decode(frame.payload))
            MessageType.USER_STATE -> onUserState(UserState.ADAPTER.decode(frame.payload))
            MessageType.USER_REMOVE -> onUserRemove(UserRemove.ADAPTER.decode(frame.payload))
            MessageType.SERVER_SYNC -> onServerSync(ServerSync.ADAPTER.decode(frame.payload))
            MessageType.TEXT_MESSAGE -> onText(TextMessage.ADAPTER.decode(frame.payload))
            MessageType.REJECT -> onReject(Reject.ADAPTER.decode(frame.payload))
            // The UDPTunnel message body is the raw UDP audio packet, not a protobuf wrapper.
            MessageType.UDP_TUNNEL -> onAudioPacket(frame.payload)
            MessageType.PING -> Unit
            else -> Unit // unhandled types (ACL, stats, codec, crypt-setup) — wired up in later milestones
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

    /** Parse a raw inbound UDP audio packet (`[type][MumbleUDP.Audio]`) and surface it. */
    private fun onAudioPacket(packet: ByteArray) {
        if (packet.isEmpty() || packet[0].toInt() != UDP_TYPE_AUDIO) return // 0 = Audio, 1 = Ping
        val audio = runCatching { UdpAudio.ADAPTER.decode(packet.copyOfRange(1, packet.size)) }
            .getOrNull() ?: return
        voiceSink?.onIncomingAudio(
            session = audio.sender_session,
            sequence = audio.frame_number,
            opus = audio.opus_data.toByteArray(),
            terminator = audio.is_terminator,
        )
    }

    /**
     * Send one encoded Opus frame to the server. Uses the Mumble 1.5 protobuf audio format
     * (`[0x00 type][MumbleUDP.Audio]`) sent as the raw body of an UDPTunnel control message; the
     * UDP+OCB2 path is added later as an optimization. target 0 = normal talking.
     */
    fun sendAudio(opus: ByteArray, terminator: Boolean, target: Int = 0) {
        val audio = UdpAudio(
            target = target,
            frame_number = audioSequence,
            opus_data = opus.toByteString(),
            is_terminator = terminator,
        )
        audioSequence += 1
        if (terminator) audioSequence = 0
        val body = UdpAudio.ADAPTER.encode(audio)
        val packet = ByteArray(body.size + 1).also {
            it[0] = UDP_TYPE_AUDIO.toByte()
            body.copyInto(it, 1)
        }
        runCatching { channel?.sendRaw(MessageType.UDP_TUNNEL.id, packet) }
    }

    private fun onReject(msg: Reject) {
        val reason = msg.reason ?: msg.type?.name ?: "Connection rejected"
        _state.update { it.copy(connection = ConnectionState.FAILED, error = reason) }
        events.tryEmit(Event.Rejected(reason))
    }

    /** Move our user to [channelId]. */
    fun joinChannel(channelId: Int) {
        val session = _state.value.sessionId ?: return
        runCatching {
            channel?.send(UserState(session = session, channel_id = channelId))
        }
    }

    /** Set our self-mute / self-deafen flags. */
    fun setSelfMuteDeaf(selfMute: Boolean, selfDeaf: Boolean) {
        val session = _state.value.sessionId ?: return
        runCatching {
            channel?.send(UserState(session = session, self_mute = selfMute, self_deaf = selfDeaf))
        }
    }

    /** Send a text message to [channelId]. Dispatched off the caller (UI) thread. */
    fun sendText(channelId: Int, message: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                channel?.send(TextMessage(message = message, channel_id = listOf(channelId)))
            }
        }
    }

    fun disconnect() {
        readJob?.cancel()
        pingJob?.cancel()
        channel?.close()
        channel = null
        _state.update { ServerState(connection = ConnectionState.DISCONNECTED) }
    }

    companion object {
        /** UDP message type prefix byte for the protobuf audio format (0 = Audio, 1 = Ping). */
        private const val UDP_TYPE_AUDIO = 0

        /** Send a keep-alive only after this much idle time on the control channel. */
        private const val IDLE_PING_MS = 6_000L

        /** Pack a Mumble v2 version: 16 bits each for major/minor/patch in the high 48 bits. */
        fun encodeVersion(major: Int, minor: Int, patch: Int): Long =
            (major.toLong() shl 48) or (minor.toLong() shl 32) or (patch.toLong() shl 16)
    }
}
