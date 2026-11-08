@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.android5.ai

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android5.ai.ui.theme.ExpressiveButtonShapes

@Composable
internal fun ChatGptConnectionCard(onConnected: (() -> Unit)? = null) {
    val context = LocalContext.current
    val auth = remember { ChatGptAuth.get(context) }
    val revision by auth.revision.collectAsState()
    val busy by auth.busy.collectAsState()
    val error by auth.error.collectAsState()
    val profiles = remember(revision) { auth.profiles }
    LaunchedEffect(revision) { if (auth.usable && SettingsManager(context).selectedProvider == ChatGptOAuth.PROVIDER) onConnected?.invoke() }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProviderIcon("OpenAI", Modifier.size(30.dp), null)
                Text(stringResource(R.string.chatgpt_connection_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Text(stringResource(R.string.chatgpt_connection_body), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            profiles.forEachIndexed { index, profile ->
                HorizontalDivider()
                Text(stringResource(R.string.chatgpt_account_label, profile.email.ifBlank { stringResource(R.string.chatgpt_connection_title) }, index + 1),
                    style = MaterialTheme.typography.titleSmall)
                Text(stringResource(when {
                    !profile.connected -> R.string.chatgpt_signed_out
                    !profile.planEnabled -> R.string.chatgpt_plan_permission
                    auth.activeClientId == profile.clientId -> R.string.chatgpt_connected
                    else -> R.string.chatgpt_saved_account
                }), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy, onClick = {
                        if (!profile.connected || !profile.planEnabled) auth.signIn(profile.clientId, requestPlanConsent = profile.connected && !profile.planEnabled)
                        else auth.select(profile.clientId)
                    }) { Text(stringResource(if (profile.connected && profile.planEnabled) R.string.chatgpt_use_account else R.string.chatgpt_continue)) }
                    if (profile.connected) TextButton(enabled = !busy, onClick = { auth.signIn(profile.clientId) }) {
                        Text(stringResource(R.string.chatgpt_reconnect))
                    }
                    if (profile.connected) TextButton(enabled = !busy, onClick = { auth.signOut(profile.clientId) }) {
                        Text(stringResource(R.string.chatgpt_sign_out))
                    }
                }
            }
            Button(shapes = ExpressiveButtonShapes, enabled = !busy, onClick = { auth.signIn() }, modifier = Modifier.fillMaxWidth()) {
                Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_provider_openai), null, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(if (profiles.isEmpty()) R.string.chatgpt_continue else R.string.chatgpt_add_account))
            }
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.chatgpt_waiting), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { auth.cancelSignIn() }) { Text(stringResource(android.R.string.cancel)) }
            }
            error?.let { Text(stringResource(R.string.chatgpt_signin_detail, it), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/settings/usage"))) } }) {
                Text(stringResource(R.string.chatgpt_manage_access))
            }
        }
    }
}
