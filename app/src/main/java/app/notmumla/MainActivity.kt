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
import androidx.compose.runtime.DisposableEffect
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

private fun AudioTransmissionMode.toUiMode(): UiTransmissionMode = when (this) {
    AudioTransmissionMode.PTT -> UiTransmissionMode.PTT
    AudioTransmissionMode.VAD -> UiTransmissionMode.VAD
}

/**
 * Ask the OS to exempt the app from battery optimization so the foreground voice service is not
 * killed in the background. No-op if already exempted. Important on aggressive OEMs (e.g. Unihertz).
 */
private fun android.content.Context.requestUnrestrictedBackground() {
    val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
    if (!pm.isIgnoringBatteryOptimizations(packageName)) {
        runCatching {
            startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$packageName"),
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
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
private fun CertChangedDialog(fingerprint: String, onTrust: () -> Unit, onCancel: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        title = { androidx.compose.material3.Text("Server certificate changed") },
        text = {
            androidx.compose.material3.Text(
                "This server's security certificate is different from the one you trusted before. " +
                    "This is normal if the server was reinstalled — but could also mean someone is " +
                    "intercepting the connection.\n\nNew fingerprint (SHA-256):\n$fingerprint",
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onTrust) {
                androidx.compose.material3.Text("Trust & reconnect")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onCancel) {
                androidx.compose.material3.Text("Cancel")
            }
        },
    )
}

@Composable
private fun AppNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.CONNECT) {
        composable(Routes.CONNECT) {
            val vm: ConnectViewModel = hiltViewModel()
            val servers by vm.savedServers.collectAsState()
            val ctx = LocalContext.current
            ConnectScreen(
                savedServers = servers,
                onConnectNew = { host, port, user, pass ->
                    ctx.requestUnrestrictedBackground()
                    vm.connectNew(host, port, user, pass)
                    nav.navigate(Routes.CHANNELS)
                },
                onConnectSaved = {
                    ctx.requestUnrestrictedBackground()
                    vm.connectSaved(it)
                    nav.navigate(Routes.CHANNELS)
                },
                onDelete = vm::delete,
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
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
            val settingsVm: SettingsViewModel = hiltViewModel()
            val appSettings by settingsVm.settings.collectAsState()
            val debugStats by vm.debugStats.collectAsState()

            // Request microphone access; start the audio engine once granted.
            val ctx = LocalContext.current
            val micPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted -> if (granted) vm.onAudioPermissionGranted() }
            // Android 13+ requires runtime POST_NOTIFICATIONS, or the foreground-service
            // notification (with mute/deafen/disconnect) is silently hidden from the shade.
            val notifPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { }
            LaunchedEffect(Unit) {
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) vm.onAudioPermissionGranted() else micPermission.launch(Manifest.permission.RECORD_AUDIO)

                if (android.os.Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            // Leave the session screen if the connection is rejected/fails — unless it's a cert
            // change, which we surface as a trust prompt instead.
            LaunchedEffect(state.connection, state.certMismatchFingerprint) {
                if (state.connection == ConnectionState.FAILED && state.certMismatchFingerprint == null) {
                    nav.popBackStack(Routes.CONNECT, inclusive = false)
                }
            }

            state.certMismatchFingerprint?.let { fp ->
                CertChangedDialog(
                    fingerprint = fp,
                    onTrust = { vm.trustNewCertificate() },
                    onCancel = {
                        vm.disconnect()
                        nav.popBackStack(Routes.CONNECT, inclusive = false)
                    },
                )
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
                transmissionMode = appSettings.transmissionMode.toUiMode(),
                debugStats = if (appSettings.debugOverlay) debugStats else null,
                onJoinChannel = vm::joinChannel,
                onPttHeld = vm::setPttHeld,
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
            val inputLevel by session.inputLevel.collectAsState()
            val vadCalibrating by session.vadCalibrating.collectAsState()
            val micTestState by session.micTestState.collectAsState()
            val identity by settingsVm.identity.collectAsState()
            // Run a live mic-level preview while Settings is open (no-op if already in a call).
            DisposableEffect(Unit) {
                session.startMicPreview()
                onDispose { session.stopMicPreview() }
            }
            SettingsScreen(
                onBack = { nav.popBackStack() },
                settings = settings,
                inputLevel = inputLevel,
                vadCalibrating = vadCalibrating,
                onCalibrateVad = session::calibrateVad,
                micTestLabel = when (micTestState) {
                    app.notmumla.audio.MicTestState.RECORDING -> "Recording…"
                    app.notmumla.audio.MicTestState.PLAYING -> "Playing…"
                    else -> "Test mic"
                },
                micTestEnabled = session.canTestMic && micTestState == app.notmumla.audio.MicTestState.IDLE && !vadCalibrating,
                onTestMic = session::testMic,
                identity = identity,
                availableRoutes = routes,
                currentRoute = current,
                onSelectRoute = session::selectRoute,
                onSetTheme = settingsVm::setTheme,
                onSetTransmission = settingsVm::setTransmissionMode,
                onSetVad = settingsVm::setVadSensitivity,
                onToggleAutoSensitivity = settingsVm::setAutoSensitivity,
                onSetMicGainDb = settingsVm::setMicGainDb,
                onToggleAutoGain = settingsVm::setAutoGain,
                onSetNoiseSuppression = settingsVm::setNoiseSuppression,
                onSetNoiseReduction = settingsVm::setNoiseReduction,
                onToggleEchoCancellation = settingsVm::setEchoCancellation,
                onSetBitrate = settingsVm::setAudioBitrate,
                onToggleAvatars = settingsVm::setShowAvatars,
                onToggleKeepAwake = settingsVm::setKeepScreenAwake,
                onToggleAutoReconnect = settingsVm::setAutoReconnect,
                onToggleTts = settingsVm::setTtsReadAloud,
                onToggleMentionSound = settingsVm::setMentionSound,
                onToggleDebugOverlay = settingsVm::setDebugOverlay,
                onRegenerateIdentity = { settingsVm.regenerateIdentity() },
                onExportIdentity = settingsVm::exportIdentity,
                onImportIdentity = settingsVm::importIdentity,
                onOpenLicenses = { nav.navigate(Routes.LICENSES) },
            )
        }
        composable(Routes.LICENSES) {
            app.notmumla.ui.settings.LicensesScreen(onBack = { nav.popBackStack() })
        }
    }
}
