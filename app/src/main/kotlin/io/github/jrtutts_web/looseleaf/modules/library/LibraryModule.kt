package io.github.jrtutts_web.looseleaf.modules.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.jrtutts_web.looseleaf.R
import io.github.jrtutts_web.looseleaf.core.model.DocumentModel
import io.github.jrtutts_web.looseleaf.core.module.EditorHost
import io.github.jrtutts_web.looseleaf.core.module.EditorModule
import io.github.jrtutts_web.looseleaf.core.module.HomeAction

/**
 * A library of saved PDFs. With this module on, "Save a copy" offers to put
 * the copy into the library folder (every copy, or only PDFs that were signed;
 * the user's choice), and the home screen gets a button that opens the
 * library, where the files can be sorted into folders, renamed, moved, shared
 * and opened again. Saving anywhere else stays one tap away.
 */
object LibraryModule : EditorModule {
    override val id = "library"
    override val title = R.string.library_title
    override val description = R.string.library_description
    override val icon = Icons.Filled.FolderSpecial
    override val enabledByDefault = false

    /**
     * Elements placed by the Signatures module carry this owner id. Only the
     * string is shared; with Signatures switched off nothing is ever "signed".
     */
    private const val SIGNATURE_OWNER = "signatures"

    private fun isSigned(model: DocumentModel) = model.pages.any { p -> p.elements.any { it.owner == SIGNATURE_OWNER } }

    fun libraryName(context: android.content.Context): String = context.getString(
        if (LibraryStore.mode(context) == LibraryMode.SIGNED) R.string.library_name_signed else R.string.library_name_all,
    )

    override val homeActions = listOf(HomeAction(::libraryName, Icons.Filled.FolderSpecial) { host -> LibraryScreen(host) })

    override fun onSave(host: EditorHost, done: () -> Unit): Boolean {
        val context = host.context
        val signed = isSigned(host.session.model)
        if (LibraryStore.mode(context) == LibraryMode.SIGNED && !signed) return false
        val tree = LibraryStore.root(context)
        if (tree == null) {
            if (LibraryStore.declined) return false
            host.showDialog { dismiss ->
                SetupDialog(
                    onChoose = {
                        dismiss()
                        host.pickFolder { picked ->
                            LibraryStore.setRoot(context, picked)
                            showSaveDialog(host, signed, done)
                        }
                    },
                    onNotNow = {
                        dismiss()
                        LibraryStore.declined = true
                        host.saveDocumentWithPicker(done)
                    },
                    onCancel = dismiss,
                )
            }
            return true
        }
        showSaveDialog(host, signed, done)
        return true
    }

    private fun showSaveDialog(host: EditorHost, signed: Boolean, done: () -> Unit) {
        val context = host.context
        val tree = LibraryStore.root(context) ?: return host.saveDocumentWithPicker(done)
        val rootId = LibraryStore.rootId(tree)
        val suggested = context.getString(if (signed) R.string.library_name_suggest_signed else R.string.save_suggested_name, host.session.baseName)
        host.showDialog { dismiss ->
            var name by remember { mutableStateOf(suggested) }
            // Start in the folder used last time, as long as it is still there.
            var folder by remember { mutableStateOf(LibraryStore.lastFolder(context) ?: (rootId to "")) }
            var choosing by remember { mutableStateOf(false) }
            val libraryName = libraryName(context)
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text(stringResource(R.string.library_save_title, libraryName)) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            label = { Text(stringResource(R.string.library_file_name)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                if (folder.second.isEmpty()) libraryName else "$libraryName / ${folder.second}",
                                Modifier.weight(1f).padding(horizontal = 12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(onClick = { choosing = true }) { Text(stringResource(R.string.library_change_folder)) }
                        }
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = { dismiss(); host.saveDocumentWithPicker(done) }) {
                            Text(stringResource(R.string.library_save_elsewhere))
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = name.isNotBlank(),
                        onClick = {
                            dismiss()
                            val (folderId, path) = folder
                            val fileName = LibraryStore.pdfName(name)
                            host.saveDocumentTo(
                                create = {
                                    val target = if (LibraryStore.exists(context, tree, folderId)) folderId else rootId
                                    LibraryStore.setLastFolder(context, target, if (target == folderId) path else "")
                                    LibraryStore.createPdf(context, tree, target, fileName)
                                },
                                onSaved = done,
                            )
                        },
                    ) { Text(stringResource(R.string.library_save)) }
                },
                dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } },
            )
            if (choosing) {
                FolderChooser(
                    context, tree, libraryName,
                    confirmLabel = stringResource(R.string.library_choose_here),
                    onCancel = { choosing = false },
                ) { id, path -> folder = id to path; choosing = false }
            }
        }
    }

    @Composable
    private fun SetupDialog(onChoose: () -> Unit, onNotNow: () -> Unit, onCancel: () -> Unit) {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(stringResource(R.string.library_setup_title)) },
            text = { Text(stringResource(R.string.library_setup_text)) },
            confirmButton = { TextButton(onClick = onChoose) { Text(stringResource(R.string.library_setup_choose)) } },
            dismissButton = { TextButton(onClick = onNotNow) { Text(stringResource(R.string.library_setup_not_now)) } },
        )
    }
}
