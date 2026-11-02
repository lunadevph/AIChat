package com.android5.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation

@Composable
fun BackupPasswordDialog(exporting: Boolean, onDismiss: () -> Unit, onConfirm: (CharArray) -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_password_title)) },
        text = {
            Column {
                Text(stringResource(if (exporting) R.string.backup_password_export_help else R.string.backup_password_import_help))
                OutlinedTextField(password, { password = it }, singleLine = true,
                    label = { Text(stringResource(R.string.backup_password_title)) },
                    visualTransformation = PasswordVisualTransformation())
                if (exporting) OutlinedTextField(confirmation, { confirmation = it }, singleLine = true,
                    label = { Text(stringResource(R.string.backup_password_confirm)) },
                    visualTransformation = PasswordVisualTransformation())
            }
        },
        confirmButton = {
            TextButton(enabled = if (exporting) password.length >= 8 && password == confirmation else password.isEmpty() || password.length >= 8,
                onClick = { val secret = password.toCharArray(); password = ""; confirmation = ""; onConfirm(secret) }) {
                Text(stringResource(R.string.backup_continue))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } }
    )
}
