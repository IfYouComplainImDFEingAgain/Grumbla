package app.notmumla.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.notmumla.data.SessionManager
import app.notmumla.data.db.ServerDao
import app.notmumla.data.db.ServerEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val serverDao: ServerDao,
    private val sessionManager: SessionManager,
) : ViewModel() {

    val savedServers = serverDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
}
