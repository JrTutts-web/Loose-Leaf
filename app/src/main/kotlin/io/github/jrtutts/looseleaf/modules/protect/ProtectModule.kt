package io.github.jrtutts.looseleaf.modules.protect

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import io.github.jrtutts.looseleaf.R
import io.github.jrtutts.looseleaf.core.module.EditorHost
import io.github.jrtutts.looseleaf.core.module.EditorModule
import io.github.jrtutts.looseleaf.core.module.MenuAction

/**
 * Save a copy that needs a password to open (AES-256). Ordinary saves are
 * never protected, also when the PDF that was opened was.
 */
object ProtectModule : EditorModule {
    override val id = "protect"
    override val title = R.string.protect_title
    override val description = R.string.protect_description
    override val icon = Icons.Filled.Lock

    override val menuActions = listOf(
        MenuAction(R.string.protect_menu, Icons.Filled.Lock) { host ->
            host.showDialog { dismiss -> PasswordDialog(onCancel = dismiss) { password -> dismiss(); save(host, password) } }
        },
    )

    private fun save(host: EditorHost, password: String) {
        host.saveCopy(
            suggestedName = host.context.getString(R.string.protect_name, host.session.baseName),
            onSaved = { host.session.markSaved() },
        ) { out ->
            host.export(out) { doc ->
                val policy = StandardProtectionPolicy(password, password, AccessPermission())
                policy.encryptionKeyLength = 256
                doc.protect(policy)
            }
        }
    }

    @Composable
    private fun PasswordDialog(onCancel: () -> Unit, onDone: (String) -> Unit) {
        var first by remember { mutableStateOf("") }
        var second by remember { mutableStateOf("") }
        val mismatch = second.isNotEmpty() && first != second
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(stringResource(R.string.protect_menu)) },
            text = {
                Column {
                    Text(stringResource(R.string.protect_text))
                    Spacer(Modifier.height(12.dp))
                    PasswordField(first, stringResource(R.string.protect_password)) { first = it }
                    Spacer(Modifier.height(8.dp))
                    PasswordField(second, stringResource(R.string.protect_repeat), isError = mismatch) { second = it }
                    if (mismatch) {
                        Text(
                            stringResource(R.string.protect_mismatch),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onDone(first) }, enabled = first.isNotEmpty() && first == second) {
                    Text(stringResource(R.string.protect_save))
                }
            },
            dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
        )
    }

    @Composable
    private fun PasswordField(value: String, label: String, isError: Boolean = false, onChange: (String) -> Unit) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            isError = isError,
            label = { Text(label) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
    }
}
