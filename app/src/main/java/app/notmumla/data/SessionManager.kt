package app.notmumla.data

import android.os.Build
import app.notmumla.data.db.ServerDao
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
    private val identityStore: IdentityStore,
    private val serverDao: ServerDao,
) {
    private val scope = CoroutineScope(SupervisorJob())

    private val _state = MutableStateFlow(ServerState())
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private val _serverLabel = MutableStateFlow("")
    val serverLabel: StateFlow<String> = _serverLabel.asStateFlow()

    val events = MutableSharedFlow<MumbleClient.Event>(extraBufferCapacity = 32)

    private var client: MumbleClient? = null
    private var mirrorJob: Job? = null
    private var eventJob: Job? = null
    private var activeServerId: Long? = null

    val activeClient: MumbleClient? get() = client

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
        val id = activeServerId ?: return
        scope.launch {
            val fp = state.serverFingerprintSha256
            if (fp != null && server.pinnedSha256 == null) serverDao.setPin(id, fp)
            serverDao.markConnected(id, System.currentTimeMillis(), state.self?.channelId)
        }
    }

    fun joinChannel(channelId: Int) = client?.joinChannel(channelId)

    fun setSelfMuteDeaf(mute: Boolean, deaf: Boolean) = client?.setSelfMuteDeaf(mute, deaf)

    fun sendText(channelId: Int, message: String) = client?.sendText(channelId, message)

    fun disconnect() {
        mirrorJob?.cancel()
        eventJob?.cancel()
        client?.disconnect()
        client = null
        activeServerId = null
        _state.value = ServerState(connection = ConnectionState.DISCONNECTED)
    }
}
