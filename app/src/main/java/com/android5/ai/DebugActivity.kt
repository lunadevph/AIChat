package com.android5.ai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android5.ai.ui.theme.AIChatTheme
import com.android5.ai.ui.theme.ExpressiveButtonShapes
import com.android5.ai.ui.theme.ExpressiveIconButtonShapes
import kotlinx.coroutines.launch
import java.io.IOException

class DebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settingsManager = SettingsManager(this)
        setTheme(settingsManager.activityThemeStyle())

        setContent {
            AIChatTheme(themeMode = settingsManager.themeMode) {
                DebugScreen(settingsManager = settingsManager, onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DebugScreen(settingsManager: SettingsManager, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val official = AppSigning.isOfficial(context)
    val fingerprint = AppSigning.shortFingerprint(context)
    val convCount = remember { ConversationStore(context).listConversations().size }
    val storageRoots = remember { DeviceStorage.roots(context) }
    val granted = remember { DeviceStorage.granted(context) }

    val releaseNotes = remember {
        try {
            context.assets.open("changelog.txt").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }

    var status by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = BackupManager.export(context, uri)
            status = if (ok) "Backup exported." else "Export failed."
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = BackupManager.import(context, uri)
            status = if (ok) "Backup imported. Restart the app to see everything." else "Import failed (wrong key or corrupt file)."
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onBack, shapes = ExpressiveIconButtonShapes) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Build status
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = if (official) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                        else MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (official) "Official build" else "Unofficial build",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (official) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        "Signing fingerprint: $fingerprint",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Official builds are signed with the LunaDevPH upload key.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Version
            DebugCard(title = "Version") {
                DebugRow("Version name", BuildConfig.VERSION_NAME)
                DebugRow("Version code", BuildConfig.VERSION_CODE.toString())
                DebugRow("Build date", BuildConfig.BUILD_DATE)
            }

            // Provider / model
            DebugCard(title = "AI") {
                DebugRow("Provider", settingsManager.selectedProvider)
                DebugRow("Model", settingsManager.selectedModel)
                DebugRow("API key present", if (settingsManager.hasApiKey(settingsManager.selectedProvider)) "yes" else "no")
                DebugRow("Streaming", if (settingsManager.streamingEnabled) "on" else "off")
                DebugRow("Tools", if (settingsManager.toolsEnabled) "on" else "off")
                DebugRow("Web search", if (settingsManager.webSearchEnabled) "on" else "off")
            }

            // Storage
            DebugCard(title = "Storage") {
                DebugRow("Granted", if (granted) "yes" else "no")
                DebugRow("Roots", if (storageRoots.isEmpty()) "(none)" else storageRoots.joinToString { it.absolutePath })
                DebugRow("Workspace", java.io.File(context.filesDir, "workspace").absolutePath)
                DebugRow("Conversations", convCount.toString())
            }

            // Backup
            DebugCard(title = "Backup & restore") {
                Text(
                    "Export everything (conversations, settings, keys) to an encrypted .ai file, or import one. " +
                        "The file is encrypted with a key derived from this app's signing certificate, so it only " +
                        "opens in an official build.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            exportLauncher.launch("aichat-backup-${System.currentTimeMillis()}.ai")
                        },
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.Upload, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Export")
                    }
                    OutlinedButton(
                        onClick = {
                            importLauncher.launch(arrayOf("*/*"))
                        },
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.Download, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Import")
                    }
                }
                if (status != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(status!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }

            // Release notes
            if (!releaseNotes.isNullOrBlank()) {
                DebugCard(title = "Release notes") {
                    Text(
                        releaseNotes,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Copy diagnostics
            val diagnostics = remember(official, fingerprint, settingsManager.selectedProvider, settingsManager.selectedModel) {
                buildString {
                    appendLine("AI Chat diagnostics")
                    appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine("Build date: ${BuildConfig.BUILD_DATE}")
                    appendLine("Official: $official")
                    appendLine("Fingerprint: $fingerprint")
                    appendLine("Provider: ${settingsManager.selectedProvider}")
                    appendLine("Model: ${settingsManager.selectedModel}")
                    appendLine("Key present: ${settingsManager.hasApiKey(settingsManager.selectedProvider)}")
                    appendLine("Storage granted: $granted")
                    appendLine("Storage roots: ${storageRoots.joinToString { it.absolutePath }}")
                    appendLine("Conversations: $convCount")
                    appendLine("Theme: ${settingsManager.themeMode}")
                }
            }
            OutlinedButton(
                onClick = {
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("diagnostics", diagnostics))
                    android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                },
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.ContentCopy, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Copy diagnostics")
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DebugCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun DebugRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}
