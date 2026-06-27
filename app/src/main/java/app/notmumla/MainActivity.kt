package app.notmumla

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.notmumla.ui.channels.ChannelsScreen
import app.notmumla.ui.connect.ConnectScreen
import app.notmumla.ui.settings.SettingsScreen
import app.notmumla.ui.theme.MumbleTheme
import app.notmumla.ui.theme.NotMumlaTheme
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

@androidx.compose.runtime.Composable
private fun AppNav(onToggleTheme: () -> Unit) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.CONNECT) {
        composable(Routes.CONNECT) {
            ConnectScreen(onConnect = { nav.navigate(Routes.CHANNELS) })
        }
        composable(Routes.CHANNELS) {
            ChannelsScreen(
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onDisconnect = { nav.popBackStack(Routes.CONNECT, inclusive = false) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}
