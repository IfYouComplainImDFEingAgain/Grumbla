package app.notmumla.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.notmumla.data.SessionManager
import app.notmumla.data.db.ServerDao
import app.notmumla.data.db.ServerEntity
import app.notmumla.protocol.udp.ServerPing
import app.notmumla.protocol.udp.ServerPingInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Live status of a saved server in the list; [Unreachable] also covers servers with pings off. */
sealed interface ServerStatus {
    data object Pinging : ServerStatus
    data object Unreachable : ServerStatus
    data class Online(val info: ServerPingInfo) : ServerStatus
}

@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val serverDao: ServerDao,
    private val sessionManager: SessionManager,
) : ViewModel() {

    val savedServers = serverDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Per-server ping results keyed by (host, port), refreshed while the connect screen is visible.
     * Keyed by address rather than id so a re-saved server keeps its last result across the reload.
     */
    val serverStatus: StateFlow<Map<Pair<String, Int>, ServerStatus>> = channelFlow {
        val results = mutableMapOf<Pair<String, Int>, ServerStatus>()
        serverDao.observeAll()
            .map { list -> list.map { it.host to it.port }.distinct() }
            .distinctUntilChanged()
            .collectLatest { addrs ->
                results.keys.retainAll(addrs.toSet())
                addrs.forEach { results.putIfAbsent(it, ServerStatus.Pinging) }
                send(results.toMap())
                while (true) {
                    coroutineScope {
                        addrs.map { addr ->
                            async {
                                val info = ServerPing.ping(addr.first, addr.second)
                                addr to (info?.let(ServerStatus::Online) ?: ServerStatus.Unreachable)
                            }
                        }.awaitAll()
                    }.forEach { (addr, status) -> results[addr] = status }
                    send(results.toMap())
                    delay(PING_INTERVAL_MS)
                }
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Connect to a freshly entered server, saving/updating it first. */
    fun connectNew(host: String, port: Int, username: String, password: String?) {
        viewModelScope.launch {
            val label = host
            val id = serverDao.upsert(
                ServerEntity(label = label, host = host, port = port,
                    username = username, password = password?.ifBlank { null }),
            )
            val saved = serverDao.get(id) ?: return@launch
            sessionManager.connect(saved)
        }
    }

    fun connectSaved(server: ServerEntity) {
        sessionManager.connect(server)
    }

    fun delete(server: ServerEntity) {
        viewModelScope.launch { serverDao.delete(server) }
    }

    private companion object {
        const val PING_INTERVAL_MS = 10_000L
    }
}
