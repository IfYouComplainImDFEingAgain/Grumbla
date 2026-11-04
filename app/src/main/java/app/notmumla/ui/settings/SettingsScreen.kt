package app.notmumla.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.notmumla.BuildConfig
import app.notmumla.audio.TransmissionMode
import app.notmumla.audio.routing.CallModeOverride
import app.notmumla.audio.routing.OutputRoute
import app.notmumla.data.AppSettings
import app.notmumla.data.NoiseSuppression
import app.notmumla.data.ThemeMode
import app.notmumla.ui.theme.MumbleTheme
import app.notmumla.vm.IdentityInfo

/** The settings hub (design 03A) and the sub-pages it opens; the page is local state, not a nav route. */
internal enum class SettingsPage(val title: String, val icon: ImageVector) {
    HUB("Settings", Icons.Filled.Info),
    ACCOUNT("Account", Icons.Filled.Person),
    PROFILE("Profile", Icons.Filled.Person),
    CONNECTION("Connection", Icons.Filled.Dns),
    APPEARANCE("Appearance", Icons.Filled.Palette),
    INPUT("Audio input", Icons.Filled.Mic),
    OUTPUT("Audio output", Icons.AutoMirrored.Filled.VolumeUp),
    CHAT("Chat", Icons.AutoMirrored.Filled.Chat),
    NOTIFICATIONS("Notifications", Icons.Filled.Notifications),
    PRIVACY("Privacy & permissions", Icons.Filled.Lock),
    ABOUT("About", Icons.Filled.Info),
}

/** Our standing on the connected server, for the Account page. */
data class AccountState(
    val connected: Boolean = false,
    val serverLabel: String? = null,
    /** Our name as the server knows it (null when offline). */
    val name: String? = null,
    /** Registered (bound to our certificate) on this server; meaningless when offline. */
    val registered: Boolean = false,
)

internal data class ProfileInfo(val name: String, val online: Boolean, val avatar: ByteArray?, val status: String) {
    val initials: String get() = name.filter { it.isLetterOrDigit() }.take(2).uppercase().ifEmpty { "?" }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    settings: AppSettings,
    account: AccountState,
    comment: String,
    avatar: ByteArray?,
    inputLevel: Float,
    vadCalibrating: Boolean,
    onCalibrateVad: () -> Unit,
    micTestLabel: String,
    micTestEnabled: Boolean,
    onTestMic: () -> Unit,
    identity: IdentityInfo?,
    availableRoutes: List<OutputRoute>,
    currentRoute: OutputRoute,
    hasEarpiece: Boolean,
    inCall: Boolean,
    onSelectRoute: (OutputRoute) -> Unit,
    onSetRoutePriority: (List<OutputRoute>) -> Unit,
    onToggleRememberRoute: (Boolean) -> Unit,
    onToggleAutoSwitchBluetooth: (Boolean) -> Unit,
    onSetTheme: (ThemeMode) -> Unit,
    onSetTransmission: (TransmissionMode) -> Unit,
    onSetVad: (Float) -> Unit,
    onToggleAutoSensitivity: (Boolean) -> Unit,
    onSetMicGainDb: (Float) -> Unit,
    onToggleAutoGain: (Boolean) -> Unit,
    onSetNoiseSuppression: (NoiseSuppression) -> Unit,
    onSetNoiseReduction: (Float) -> Unit,
    onToggleEchoCancellation: (Boolean) -> Unit,
    onToggleRawMic: (Boolean) -> Unit,
    onToggleShareMic: (Boolean) -> Unit,
    onToggleAudioLeveling: (Boolean) -> Unit,
    onToggleMuteOutputWhilePtt: (Boolean) -> Unit,
    onToggleMuteOutputLoudspeakerOnly: (Boolean) -> Unit,
    onSetCallMode: (CallModeOverride) -> Unit,
    onSetPhoneEarpiece: (Boolean) -> Unit,
    onSetBitrate: (Int) -> Unit,
    onToggleAvatars: (Boolean) -> Unit,
    onToggleKeepAwake: (Boolean) -> Unit,
    onToggleAutoReconnect: (Boolean) -> Unit,
    onToggleTts: (Boolean) -> Unit,
    onToggleMentionSound: (Boolean) -> Unit,
    onToggleAllowNudges: (Boolean) -> Unit,
    onToggleJoinAlerts: (Boolean) -> Unit,
    onToggleRichComposer: (Boolean) -> Unit,
    onToggleDebugOverlay: (Boolean) -> Unit,
    onSetGamesUnlocked: (Boolean) -> Unit,
    onRegenerateIdentity: () -> Unit,
    onExportIdentity: suspend (password: String) -> ByteArray?,
    onImportIdentity: suspend (bytes: ByteArray, password: String) -> Boolean,
    onSaveComment: (String) -> Unit,
    onPickAvatar: suspend (android.net.Uri) -> Boolean,
    onRemoveAvatar: () -> Unit,
    onSetDefaultUsername: (String) -> Unit,
    onRegister: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    // Saveable so a trip to Licenses (a real nav route) returns to the same sub-page.
    var page by rememberSaveable { mutableStateOf(SettingsPage.HUB) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var inputAdvanced by rememberSaveable { mutableStateOf(false) }
    var outputAdvanced by rememberSaveable { mutableStateOf(false) }
    val hubScroll = rememberScrollState()

    // System back steps out of a sub-page (to the search results if it was opened from there), then
    // out of search, and only then leaves Settings.
    BackHandler(enabled = page != SettingsPage.HUB || searching) {
        page = when {
            page == SettingsPage.PROFILE -> SettingsPage.ACCOUNT
            page != SettingsPage.HUB -> SettingsPage.HUB
            else -> { searching = false; query = ""; SettingsPage.HUB }
        }
    }
    val toHub = { page = SettingsPage.HUB }

    val profile = ProfileInfo(
        name = account.name ?: settings.defaultUsername.trim().ifEmpty { identity?.commonName ?: "You" },
        online = account.connected,
        avatar = avatar,
        status = when {
            !account.connected -> "Not connected"
            account.registered -> "Registered on ${account.serverLabel}"
            else -> "Guest on ${account.serverLabel}"
        },
    )
    val (perms, _) = rememberPermissionStatus()

    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val forward = targetState != SettingsPage.HUB
            (slideInHorizontally { w -> if (forward) w / 4 else -w / 4 } + fadeIn()) togetherWith
                (slideOutHorizontally { w -> if (forward) -w / 4 else w / 4 } + fadeOut())
        },
        label = "settings-page",
    ) { p ->
        when (p) {
            SettingsPage.HUB ->
                if (searching) {
                    SettingsSearch(
                        query = query,
                        onQueryChange = { query = it },
                        perms = perms,
                        onClose = { searching = false; query = "" },
                        onOpen = { entry ->
                            if (entry.advanced) {
                                if (entry.page == SettingsPage.INPUT) inputAdvanced = true
                                if (entry.page == SettingsPage.OUTPUT) outputAdvanced = true
                            }
                            page = entry.page
                        },
                    )
                } else {
                    SettingsHub(
                        onBack = onBack,
                        profile = profile,
                        perms = perms,
                        scroll = hubScroll,
                        onSearch = { searching = true },
                        onOpen = { page = it },
                    )
                }
            SettingsPage.ACCOUNT -> AccountPage(toHub, profile, account, identity,
                onOpenProfile = { page = SettingsPage.PROFILE }, onRegister = onRegister,
                onRegenerateIdentity = onRegenerateIdentity, onExportIdentity = onExportIdentity,
                onImportIdentity = onImportIdentity)
            SettingsPage.PROFILE -> ProfilePage(
                onBack = { page = SettingsPage.ACCOUNT }, profile = profile, comment = comment,
                defaultUsername = settings.defaultUsername, onSaveComment = onSaveComment,
                onPickAvatar = onPickAvatar, onRemoveAvatar = onRemoveAvatar,
                onSetDefaultUsername = onSetDefaultUsername,
            )
            SettingsPage.CONNECTION -> ConnectionPage(toHub, settings, onToggleAutoReconnect, onSetBitrate)
            SettingsPage.APPEARANCE -> AppearancePage(toHub, settings, onSetTheme, onToggleAvatars, onToggleKeepAwake)
            SettingsPage.INPUT -> AudioInputPage(
                onBack = toHub, settings = settings,
                advancedExpanded = inputAdvanced, onAdvancedExpandedChange = { inputAdvanced = it },
                inputLevel = inputLevel, vadCalibrating = vadCalibrating, onCalibrateVad = onCalibrateVad,
                micTestLabel = micTestLabel, micTestEnabled = micTestEnabled, onTestMic = onTestMic,
                onSetTransmission = onSetTransmission, onSetVad = onSetVad,
                onToggleAutoSensitivity = onToggleAutoSensitivity, onSetMicGainDb = onSetMicGainDb,
                onToggleAutoGain = onToggleAutoGain, onSetNoiseSuppression = onSetNoiseSuppression,
                onSetNoiseReduction = onSetNoiseReduction, onToggleEchoCancellation = onToggleEchoCancellation,
                onToggleRawMic = onToggleRawMic, onToggleShareMic = onToggleShareMic,
                onToggleMuteOutputWhilePtt = onToggleMuteOutputWhilePtt,
                onToggleMuteOutputLoudspeakerOnly = onToggleMuteOutputLoudspeakerOnly,
            )
            SettingsPage.OUTPUT -> AudioOutputPage(
                onBack = toHub, settings = settings,
                advancedExpanded = outputAdvanced, onAdvancedExpandedChange = { outputAdvanced = it },
                availableRoutes = availableRoutes, currentRoute = currentRoute, hasEarpiece = hasEarpiece,
                inCall = inCall, onSelectRoute = onSelectRoute, onSetRoutePriority = onSetRoutePriority,
                onToggleRememberRoute = onToggleRememberRoute,
                onToggleAutoSwitchBluetooth = onToggleAutoSwitchBluetooth,
                onToggleAudioLeveling = onToggleAudioLeveling, onSetCallMode = onSetCallMode,
                onSetPhoneEarpiece = onSetPhoneEarpiece,
            )
            SettingsPage.CHAT -> ChatPage(toHub, settings, onToggleRichComposer)
            SettingsPage.NOTIFICATIONS -> NotificationsPage(toHub, settings, onToggleTts, onToggleMentionSound,
                onToggleAllowNudges, onToggleJoinAlerts)
            SettingsPage.PRIVACY -> PrivacyPage(toHub)
            SettingsPage.ABOUT -> AboutPage(toHub, settings, onSetGamesUnlocked, onToggleDebugOverlay, onOpenLicenses)
        }
    }
}

/* ---------------- 03A hub ---------------- */

@Composable
private fun SettingsHub(
    onBack: () -> Unit,
    profile: ProfileInfo,
    perms: List<PermStatus>,
    scroll: androidx.compose.foundation.ScrollState,
    onSearch: () -> Unit,
    onOpen: (SettingsPage) -> Unit,
) {
    val c = MumbleTheme.colors
    SettingsPageScaffold("Settings", onBack, scroll) {
        Row(
            Modifier.padding(top = 4.dp, bottom = 12.dp).fillMaxWidth().height(52.dp)
                .clip(RoundedCornerShape(26.dp)).background(c.surfHigh).clickable(onClick = onSearch)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.Search, null, tint = c.onSurfaceVar, modifier = Modifier.size(22.dp))
            Text("Search settings", fontSize = 15.sp, color = c.onSurfaceVar)
        }

        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surfContainer)
                .clickable { onOpen(SettingsPage.ACCOUNT) }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ProfileAvatar(profile.initials, 54.dp, profile.online, ringColor = c.surfContainer, image = profile.avatar)
            Column(Modifier.weight(1f)) {
                Text(profile.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = c.onSurface)
                Text("Profile, registration & identity certificate", fontSize = 13.sp, color = c.onSurfaceVar)
            }
            Chevron()
        }

        val missing = perms.filterNot { it.granted }
        val privacySummary = when (missing.size) {
            0 -> "Microphone, notifications, Bluetooth"
            1 -> missing[0].let {
                if (it.permission == null) "${it.label} is off" else "${it.label} permission is off"
            }
            else -> "${missing.size} permissions are off"
        }

        HubGroup {
            HubRow(SettingsPage.CONNECTION, "Auto-reconnect, audio quality", onOpen = onOpen)
        }
        HubGroup {
            HubRow(SettingsPage.APPEARANCE, "Theme, avatars, screen awake", onOpen = onOpen)
            Divider()
            HubRow(SettingsPage.INPUT, "Transmission, sensitivity, noise suppression", onOpen = onOpen)
            Divider()
            HubRow(SettingsPage.OUTPUT, "Output device, Bluetooth, leveling", onOpen = onOpen)
        }
        HubGroup {
            HubRow(SettingsPage.CHAT, "Formatting toolbar", onOpen = onOpen)
            Divider()
            HubRow(SettingsPage.NOTIFICATIONS, "Text-to-speech, mention sound, nudges, join alerts", onOpen = onOpen)
        }
        HubGroup {
            HubRow(SettingsPage.PRIVACY, privacySummary, alert = missing.isNotEmpty(), onOpen = onOpen)
            Divider()
            HubRow(SettingsPage.ABOUT, "Version ${BuildConfig.VERSION_NAME} · licenses · developer", onOpen = onOpen)
        }
    }
}

@Composable
private fun HubGroup(content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(12.dp))
    SettingsGroup(content)
}

@Composable
private fun HubRow(page: SettingsPage, summary: String, alert: Boolean = false, onOpen: (SettingsPage) -> Unit) {
    val c = MumbleTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable { onOpen(page) }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(40.dp)) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(c.primaryContainer),
                contentAlignment = Alignment.Center) {
                Icon(page.icon, null, tint = c.onPrimaryContainer, modifier = Modifier.size(21.dp))
            }
            if (alert) {
                Box(
                    Modifier.align(Alignment.TopEnd).size(11.dp).clip(CircleShape).background(c.surfContainer)
                        .padding(2.dp).clip(CircleShape).background(c.muted),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(page.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.onSurface)
            Text(summary, fontSize = 13.sp,
                color = if (alert) c.muted else c.onSurfaceVar,
                fontWeight = if (alert) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

/* ---------------- 03C search ---------------- */

@Composable
private fun SettingsSearch(
    query: String,
    onQueryChange: (String) -> Unit,
    perms: List<PermStatus>,
    onClose: () -> Unit,
    onOpen: (SearchEntry) -> Unit,
) {
    val c = MumbleTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val results = remember(query) { searchSettings(query) }

    Column(Modifier.fillMaxSize().background(c.surface)) {
        Box(Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 10.dp)) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(c.surfHigh)
                    .padding(start = 4.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(42.dp).clip(CircleShape).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search", tint = c.onSurface)
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text("Search settings", fontSize = 16.sp, color = c.onSurfaceVar)
                    BasicTextField(
                        value = query, onValueChange = onQueryChange, singleLine = true,
                        textStyle = TextStyle(fontSize = 16.sp, color = c.onSurface),
                        cursorBrush = SolidColor(c.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                if (query.isNotEmpty()) {
                    Box(Modifier.size(42.dp).clip(CircleShape).clickable { onQueryChange("") },
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Close, "Clear", tint = c.onSurfaceVar)
                    }
                }
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 14.dp, bottom = 18.dp),
        ) {
            if (query.isBlank()) return@Column
            Text(
                when (results.size) { 0 -> "No settings match"; 1 -> "1 result"; else -> "${results.size} results" },
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.onSurfaceVar,
                modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 10.dp),
            )
            if (results.isEmpty()) return@Column
            SettingsGroup {
                results.forEachIndexed { i, e ->
                    if (i > 0) Divider()
                    val subtitle = e.permissionLabel?.let { label ->
                        perms.firstOrNull { it.label == label }?.let { if (it.granted) "Allowed" else "Off · tap to allow" }
                    } ?: e.subtitle
                    SearchResultRow(e, subtitle, query) { onOpen(e) }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(e: SearchEntry, subtitle: String?, query: String, onClick: () -> Unit) {
    val c = MumbleTheme.colors
    val highlight = c.primary.copy(alpha = 0.24f)
    val title = remember(e.title, query, highlight) {
        buildAnnotatedString {
            val q = query.trim()
            var at = 0
            while (q.isNotEmpty()) {
                val hit = e.title.indexOf(q, at, ignoreCase = true)
                if (hit < 0) break
                append(e.title.substring(at, hit))
                withStyle(SpanStyle(background = highlight)) { append(e.title.substring(hit, hit + q.length)) }
                at = hit + q.length
            }
            append(e.title.substring(at))
        }
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(e.icon, null, tint = c.onSurfaceVar, modifier = Modifier.offset(y = 1.dp).size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.onSurface)
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = c.onSurfaceVar)
            Text(e.breadcrumb, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.primary,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}
