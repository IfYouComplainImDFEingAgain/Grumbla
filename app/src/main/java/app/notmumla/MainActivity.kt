package app.notmumla

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.notmumla.protocol.model.ConnectionState
import app.notmumla.ui.channels.ChannelsScreen
import app.notmumla.ui.channels.initialsFor
import app.notmumla.ui.channels.toUiChannels
import app.notmumla.ui.connect.ConnectScreen
import app.notmumla.ui.settings.SettingsScreen
import app.notmumla.ui.theme.MumbleTheme
import app.notmumla.ui.theme.NotMumlaTheme
import app.notmumla.vm.ConnectViewModel
import app.notmumla.vm.SessionViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val systemDark = isSystemInDarkTheme()
            var dark by remember { mutableStateOf(systemDark) }
            NotMumlaTheme(dark = dark) {
                Surface(
                    Modifier.fillMaxSize().systemBarsPadding(),
                    color = MumbleTheme.colors.surface,
                ) {
                    AppNav(onToggleTheme = { dark = !dark })
                }
            }
        }
    }
}

private object Routes {
    const val CONNECT = "connect"
    const val CHANNELS = "channels"
    const val SETTINGS = "settings"
}

@Composable
private fun AppNav(onToggleTheme: () -> Unit) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.CONNECT) {
        composable(Routes.CONNECT) {
            val vm: ConnectViewModel = hiltViewModel()
            val servers by vm.savedServers.collectAsState()
            ConnectScreen(
                savedServers = servers,
                onConnectNew = { host, port, user, pass ->
                    vm.connectNew(host, port, user, pass)
                    nav.navigate(Routes.CHANNELS)
                },
                onConnectSaved = {
                    vm.connectSaved(it)
                    nav.navigate(Routes.CHANNELS)
                },
                onDelete = vm::delete,
            )
        }
        composable(Routes.CHANNELS) {
            val vm: SessionViewModel = hiltViewModel()
            val state by vm.state.collectAsState()
            val serverLabel by vm.serverLabel.collectAsState()

            // Leave the session screen if the connection is rejected/fails.
            LaunchedEffect(state.connection) {
                if (state.connection == ConnectionState.FAILED) {
                    nav.popBackStack(Routes.CONNECT, inclusive = false)
                }
            }

            val self = state.self
            val connectionLabel = when (state.connection) {
                ConnectionState.CONNECTING -> "Connecting…"
                ConnectionState.HANDSHAKING -> "Authenticating…"
                ConnectionState.CONNECTED -> "Connected"
                ConnectionState.FAILED -> state.error ?: "Connection failed"
                ConnectionState.DISCONNECTED -> "Disconnected"
            }
            val serverName = serverLabel.ifBlank { "Mumble server" }

            ChannelsScreen(
                serverName = serverName,
                serverInitial = initialsFor(serverName).take(1),
                connectionLabel = connectionLabel,
                channels = state.toUiChannels(),
                selfMuted = self?.selfMute ?: false,
                selfDeafened = self?.selfDeaf ?: false,
                unreadCount = 0,
                onJoinChannel = vm::joinChannel,
                onToggleMute = {
                    val s = state.self
                    vm.setSelfMuteDeaf(mute = !(s?.selfMute ?: false), deaf = s?.selfDeaf ?: false)
                },
                onToggleDeafen = {
                    val s = state.self
                    val newDeaf = !(s?.selfDeaf ?: false)
                    // Deafening implies muting, per Mumble semantics.
                    vm.setSelfMuteDeaf(mute = newDeaf || (s?.selfMute ?: false), deaf = newDeaf)
                },
                onSendText = { msg -> self?.channelId?.let { vm.sendText(it, msg) } },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onDisconnect = {
                    vm.disconnect()
                    nav.popBackStack(Routes.CONNECT, inclusive = false)
                },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}
