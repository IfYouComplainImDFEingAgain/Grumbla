package app.notmumla.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.notmumla.ui.theme.MumbleTheme

private data class License(val name: String, val license: String, val use: String)

private val LICENSES = listOf(
    License("libopus", "BSD-3-Clause", "Opus audio codec (native)"),
    License("Bouncy Castle", "MIT-style (BC)", "Identity certificate generation"),
    License("Square Wire / Okio", "Apache-2.0", "Protobuf runtime & I/O"),
    License("Kotlin & Coroutines", "Apache-2.0", "Language & concurrency"),
    License("Jetpack Compose", "Apache-2.0", "UI toolkit"),
    License("AndroidX (Core, Lifecycle, Navigation, Room, DataStore, Security)", "Apache-2.0", "Platform libraries"),
    License("Dagger Hilt", "Apache-2.0", "Dependency injection"),
    License("Mumble protocol", "BSD-3-Clause", "Protocol definitions (.proto)"),
)

@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val c = MumbleTheme.colors
    Column(Modifier.fillMaxSize().background(c.surface)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(42.dp).clip(CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = c.onSurface)
            }
            Text("Open-source licenses", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.onSurface)
        }
        LazyColumn(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(LICENSES) { lib ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfContainer)
                        .padding(16.dp),
                ) {
                    Text(lib.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.onSurface)
                    Text(lib.use, fontSize = 13.sp, color = c.onSurfaceVar,
                        modifier = Modifier.padding(top = 2.dp))
                    Text(lib.license, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.primary,
                        modifier = Modifier.padding(top = 6.dp))
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}
