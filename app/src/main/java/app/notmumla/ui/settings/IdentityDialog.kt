package app.notmumla.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.notmumla.ui.theme.MumbleTheme
import app.notmumla.vm.IdentityInfo
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun IdentityDialog(
    info: IdentityInfo?,
    onRegenerate: () -> Unit,
    onExport: suspend (password: String) -> ByteArray?,
    onImport: suspend (bytes: ByteArray, password: String) -> Boolean,
    onDismiss: () -> Unit,
) {
    val c = MumbleTheme.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    fun toast(msg: String) = android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()

    var confirmRegen by remember { mutableStateOf(false) }
    var pendingExportPw by remember { mutableStateOf<String?>(null) }
    var pwMode by remember { mutableStateOf<String?>(null) } // "export" | "import"
    var importUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-pkcs12"),
    ) { uri ->
        val pw = pendingExportPw
        pendingExportPw = null
        if (uri != null && pw != null) scope.launch {
            val bytes = onExport(pw)
            val ok = bytes != null && withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }.isSuccess
            }
            toast(if (ok) "Identity exported" else "Export failed")
        }
    }
    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) { importUri = uri; pwMode = "import" } }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surfContainer,
        title = { Text("Identity certificate", fontWeight = FontWeight.Bold, color = c.onSurface) },
        text = {
            if (info == null) {
                Text("Generating…", color = c.onSurfaceVar)
            } else {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        IdentityField("Name", info.commonName, mono = false)
                        IdentityField("Valid", "${info.issued} – ${info.expires}", mono = false)
                        IdentityField("SHA-256 fingerprint", info.sha256, mono = true)
                        IdentityField("SHA-1 (server identifies you by this)", info.sha1, mono = true)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = { importPicker.launch(arrayOf("*/*")) }) { Text("Import") }
                TextButton(onClick = { pwMode = "export" }) { Text("Export") }
                TextButton(onClick = { confirmRegen = true }) { Text("Regenerate") }
            }
        },
    )

    if (confirmRegen) {
        AlertDialog(
            onDismissRequest = { confirmRegen = false },
            containerColor = c.surfContainer,
            title = { Text("Regenerate identity?", fontWeight = FontWeight.Bold, color = c.onSurface) },
            text = {
                Text(
                    "This creates a brand-new certificate. Servers that recognised your old identity " +
                        "(registrations, permissions) will see you as a new user.",
                    color = c.onSurfaceVar,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmRegen = false; onRegenerate(); toast("New identity generated") }) {
                    Text("Regenerate", color = c.afk)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRegen = false }) { Text("Cancel") } },
        )
    }

    pwMode?.let { mode ->
        PasswordDialog(
            title = if (mode == "export") "Set a password for the file" else "Enter the file's password",
            onConfirm = { pw ->
                pwMode = null
                if (mode == "export") {
                    pendingExportPw = pw
                    exportPicker.launch("identity.p12")
                } else {
                    val uri = importUri
                    importUri = null
                    if (uri != null) scope.launch {
                        val bytes = withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                        }
                        val ok = bytes != null && onImport(bytes, pw)
                        toast(if (ok) "Identity imported" else "Import failed — wrong password or file")
                    }
                }
            },
            onDismiss = { pwMode = null; importUri = null },
        )
    }
}

@Composable
private fun PasswordDialog(title: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val c = MumbleTheme.colors
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surfContainer,
        title = { Text(title, fontWeight = FontWeight.Bold, color = c.onSurface) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = pw, onValueChange = { pw = it }, singleLine = true,
                label = { Text("Password") },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                ),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(pw) }, enabled = pw.isNotEmpty()) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun IdentityField(label: String, value: String, mono: Boolean) {
    val c = MumbleTheme.colors
    Column {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.onSurfaceVar)
        Text(
            value, fontSize = 13.sp, color = c.onSurface,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        )
    }
}
