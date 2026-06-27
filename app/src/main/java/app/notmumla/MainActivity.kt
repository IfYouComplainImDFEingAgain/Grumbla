package app.notmumla

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import androidx.compose.ui.platform.LocalContext
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
import app.notmumla.vm.SettingsViewModel
import app.notmumla.ui.TransmissionMode as UiTransmissionMode
import app.notmumla.audio.TransmissionMode as AudioTransmissionMode
import dagger.hilt.android.AndroidEntryPoint

private fun UiTransmissionMode.toAudioMode(): AudioTransmissionMode = when (this) {
    UiTransmissionMode.PTT -> AudioTransmissionMode.PTT
    UiTransmissionMode.VAD -> AudioTransmissionMode.VAD
}

private val chatTimeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())

private fun app.notmumla.data.ChatLine.toUiMessage(): app.notmumla.ui.UiMessage = app.notmumla.ui.UiMessage(
    id = id.toInt(),
    kind = when {
        isSystem -> app.notmumla.ui.ChatKind.SYSTEM
        isMe -> app.notmumla.ui.ChatKind.ME
        else -> app.notmumla.ui.ChatKind.OTHER
    },
    name = senderName,
    initials = app.notmumla.ui.channels.initialsFor(senderName),
    avatar = app.notmumla.ui.channels.avatarColorFor(senderName),
    time = chatTimeFmt.format(java.util.Date(timeMillis)),
    text = text,
    imageBytes = imageBytes,
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settingsVm: SettingsViewModel = hiltViewModel()
            val settings by settingsVm.settings.collectAsState()
            val dark = when (settings.theme) {
                app.notmumla.data.ThemeMode.DARK -> true
                app.notmumla.data.ThemeMode.LIGHT -> false
                app.notmumla.data.ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            LaunchedEffect(settings.keepScreenAwake) {
                if (settings.keepScreenAwake) {
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            NotMumlaTheme(dark = dark) {
                Surface(
                    Modifier.fillMaxSize().systemBarsPadding(),
                    color = MumbleTheme.colors.surface,
                ) {
                    AppNav()
                }
            }
        }
    }
}

private object Routes {
    const val CONNECT = "connect"
    const val CHANNELS = "channels"
    const val SETTINGS = "settings"
    const val LICENSES = "licenses"
}

@Composable
private fun AppNav() {
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
            val speaking by vm.speakingSessions.collectAsState()
            val transmitting by vm.localTransmitting.collectAsState()
            val chat by vm.chat.collectAsState()
            val unread by vm.unreadChat.collectAsState()

            // Request microphone access; start the audio engine once granted.
            val ctx = LocalContext.current
            val micPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted -> if (granted) vm.onAudioPermissionGranted() }
            LaunchedEffect(Unit) {
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) vm.onAudioPermissionGranted() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }

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
            val currentChannelName = self?.channelId?.let { state.channels[it]?.name } ?: "chat"

            ChannelsScreen(
                serverName = serverName,
                serverInitial = initialsFor(serverName).take(1),
                connectionLabel = connectionLabel,
                channels = state.toUiChannels(speaking),
                chatMessages = chat.map { it.toUiMessage() },
                selfMuted = self?.selfMute ?: false,
                selfDeafened = self?.selfDeaf ?: false,
                transmitting = transmitting,
                unreadCount = unread,
                currentChannelName = currentChannelName,
                onJoinChannel = vm::joinChannel,
                onPttHeld = vm::setPttHeld,
                onModeChange = { vm.setTransmissionMode(it.toAudioMode()) },
                onToggleMute = {
                    val s = state.self
                    vm.setMuted(muted = !(s?.selfMute ?: false), deaf = s?.selfDeaf ?: false)
                },
                onToggleDeafen = {
                    val s = state.self
                    val newDeaf = !(s?.selfDeaf ?: false)
                    // Deafening implies muting, per Mumble semantics.
                    vm.setMuted(muted = newDeaf || (s?.selfMute ?: false), deaf = newDeaf)
                },
                onSendText = { msg -> self?.channelId?.let { vm.sendText(it, msg) } },
                onSendImage = { uri -> self?.channelId?.let { vm.sendImage(it, uri) } },
                onChatRead = vm::markChatRead,
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onDisconnect = {
                    vm.disconnect()
                    nav.popBackStack(Routes.CONNECT, inclusive = false)
                },
            )
        }
        composable(Routes.SETTINGS) {
            val session: SessionViewModel = hiltViewModel()
            val settingsVm: SettingsViewModel = hiltViewModel()
            val routes by session.availableRoutes.collectAsState()
            val current by session.currentRoute.collectAsState()
            val settings by settingsVm.settings.collectAsState()
            SettingsScreen(
                onBack = { nav.popBackStack() },
                settings = settings,
                availableRoutes = routes,
                currentRoute = current,
                onSelectRoute = session::selectRoute,
                onSetTheme = settingsVm::setTheme,
                onSetTransmission = settingsVm::setTransmissionMode,
                onSetVad = settingsVm::setVadSensitivity,
                onSetMicGainDb = settingsVm::setMicGainDb,
                onToggleNoiseSuppression = settingsVm::setNoiseSuppression,
                onToggleEchoCancellation = settingsVm::setEchoCancellation,
                onSetBitrate = settingsVm::setAudioBitrate,
                onToggleAvatars = settingsVm::setShowAvatars,
                onToggleKeepAwake = settingsVm::setKeepScreenAwake,
                onToggleAutoReconnect = settingsVm::setAutoReconnect,
                onToggleTts = settingsVm::setTtsReadAloud,
                onToggleMentionSound = settingsVm::setMentionSound,
                onOpenLicenses = { nav.navigate(Routes.LICENSES) },
            )
        }
        composable(Routes.LICENSES) {
            app.notmumla.ui.settings.LicensesScreen(onBack = { nav.popBackStack() })
        }
    }
}
