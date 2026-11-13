package io.github.jrtutts.looseleaf.core.ui

import android.content.Context
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.jrtutts.looseleaf.AppViewModel
import io.github.jrtutts.looseleaf.R
import kotlinx.coroutines.delay
import io.github.jrtutts.looseleaf.core.module.AppHost

/** Top of the UI: decides between the home screen, the editor and Settings. */
@Composable
fun AppRoot(vm: AppViewModel) {
    LooseLeafTheme {
        val editor = vm.editor
        // Module screens opened from the home screen (a library, say) need a folder picker;
        // it is registered here so that it exists while such a screen is showing.
        var onFolder by remember { mutableStateOf<((Uri) -> Unit)?>(null) }
        val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val cb = onFolder
            onFolder = null
            if (uri != null) {
                EditorController.keepFolderAccess(vm.getApplication(), uri)
                cb?.invoke(uri)
            }
        }
        val host = remember(vm) {
            object : AppHost {
                override val context: Context = vm.getApplication()
                override fun open(uri: Uri) = vm.requestOpen(uri)
                override fun pickFolder(onPicked: (Uri) -> Unit) { onFolder = onPicked; folderPicker.launch(null) }
                override fun close() { vm.homeScreen = null }
            }
        }
        when {
            vm.showSettings -> {
                BackHandler { vm.showSettings = false }
                SettingsScreen(vm.settings, vm.modules, onBack = { vm.showSettings = false })
            }
            editor != null -> EditorScreen(editor, onClose = { vm.closeEditor() }, onSettings = { vm.showSettings = true })
            vm.homeScreen != null -> {
                BackHandler { vm.homeScreen = null }
                vm.homeScreen?.invoke()
            }
            else -> HomeScreen(vm, host)
        }

        if (vm.opening) {
            // Long documents take a while to read; say so instead of looking stuck.
            var slow by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { delay(2500); slow = true }
            BusyDialog(stringResource(if (slow) R.string.opening_slow else R.string.opening))
        }

        vm.error?.let { text ->
            AlertDialog(
                onDismissRequest = { vm.error = null },
                confirmButton = { TextButton(onClick = { vm.error = null }) { Text(stringResource(R.string.ok)) } },
                text = { Text(text) },
            )
        }

        vm.passwordRequest?.let { req ->
            var password by remember(req) { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { vm.cancelPassword() },
                title = { Text(stringResource(R.string.password_title)) },
                text = {
                    Column {
                        Text(stringResource(if (req.wrong) R.string.password_wrong else R.string.password_text, req.name))
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            singleLine = true,
                            label = { Text(stringResource(R.string.password_label)) },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        )
                    }
                },
                confirmButton = { TextButton(onClick = { vm.submitPassword(password) }) { Text(stringResource(R.string.open)) } },
                dismissButton = { TextButton(onClick = { vm.cancelPassword() }) { Text(stringResource(R.string.cancel)) } },
            )
        }

        vm.pendingOpen?.let { uri ->
            AlertDialog(
                onDismissRequest = { vm.pendingOpen = null },
                title = { Text(stringResource(R.string.replace_title)) },
                text = { Text(stringResource(R.string.replace_text)) },
                confirmButton = { TextButton(onClick = { vm.open(uri) }) { Text(stringResource(R.string.discard_and_open)) } },
                dismissButton = { TextButton(onClick = { vm.pendingOpen = null }) { Text(stringResource(R.string.keep_editing)) } },
            )
        }
    }
}

@Composable
fun HomeScreen(vm: AppViewModel, host: AppHost) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.open(uri)
    }
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.home_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(40.dp))
            Button(onClick = { picker.launch(arrayOf("application/pdf")) }, Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Icons.Filled.FolderOpen, null)
                Gap()
                Text(stringResource(R.string.open_pdf))
            }
            for (action in vm.settings.enabledModules.flatMap { it.homeActions }) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { vm.homeScreen = { action.screen(host) } }, Modifier.fillMaxWidth().height(56.dp)) {
                    Icon(action.icon, null)
                    Gap()
                    Text(action.label(host.context))
                }
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { vm.showSettings = true }) {
                Icon(Icons.Filled.Settings, null)
                Gap()
                Text(stringResource(R.string.settings))
            }
            vm.recoverable?.let { stored ->
                Spacer(Modifier.height(24.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.recover_title), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        val whenText = DateUtils.getRelativeTimeSpanString(
                            stored.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                        ).toString()
                        Text(
                            stringResource(R.string.recover_text, stored.name, whenText),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { vm.discardRecoverable() }) { Text(stringResource(R.string.discard)) }
                            TextButton(onClick = { vm.restoreRecoverable() }) { Text(stringResource(R.string.recover_restore)) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
            Text(
                stringResource(R.string.home_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun BusyDialog(text: String) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        text = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(40.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(text, textAlign = TextAlign.Center)
                }
            }
        },
    )
}
