package io.github.jrtutts.looseleaf.modules.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jrtutts.looseleaf.R
import io.github.jrtutts.looseleaf.core.module.AppHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the library screen is, as (folder id, folder name) from the top down. Kept while the app runs, so coming back from the editor lands in the same folder. */
private val trail = mutableStateListOf<Pair<String, String>>()

private fun path(steps: List<Pair<String, String>>) = steps.drop(1).joinToString(" / ") { it.second }

/** The library: browse the folder, open PDFs, and keep them in order. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(host: AppHost) {
    val context = host.context
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tree by remember { mutableStateOf(LibraryStore.root(context)) }
    var mode by remember { mutableStateOf(LibraryStore.mode(context)) }
    val title = LibraryModule.libraryName(context).also { mode }   // re-read when the mode changes
    var entries by remember { mutableStateOf<List<LibEntry>?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    var showSettings by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<LibEntry?>(null) }
    var deleting by remember { mutableStateOf<LibEntry?>(null) }
    var moving by remember { mutableStateOf<LibEntry?>(null) }

    fun say(text: String) { scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(text) } }

    fun chooseFolder() = host.pickFolder { picked ->
        LibraryStore.setRoot(context, picked)
        LibraryStore.declined = false
        trail.clear()
        tree = LibraryStore.root(context)
    }

    val root = tree
    if (root != null && (trail.isEmpty() || trail[0].first != LibraryStore.rootId(root))) {
        trail.clear()
        trail += LibraryStore.rootId(root) to title
    }
    val here = trail.lastOrNull()

    LaunchedEffect(root, here?.first, reload) {
        if (root == null || here == null) { entries = null; return@LaunchedEffect }
        entries = withContext(Dispatchers.IO) { runCatching { LibraryStore.list(context, root, here.first) }.getOrNull() }
        // The folder may have been removed with another app; fall back to the top.
        if (entries == null && trail.size > 1) { while (trail.size > 1) trail.removeAt(trail.lastIndex) }
    }

    fun up() { if (trail.size > 1) trail.removeAt(trail.lastIndex) else host.close() }
    BackHandler { up() }

    /** Run a storage operation in the background, then refresh; [failed] is shown if it reports failure. */
    fun change(failed: Int, op: (Uri) -> Boolean) {
        val t = root ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { op(t) }.getOrDefault(false) }
            if (!ok) say(context.getString(failed))
            reload++
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (trail.size > 1) here?.second.orEmpty() else title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = { up() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    if (root != null) {
                        IconButton(onClick = { newFolder = true }) { Icon(Icons.Filled.CreateNewFolder, stringResource(R.string.library_new_folder)) }
                    }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Filled.Tune, stringResource(R.string.library_options)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val list = entries
            when {
                root == null -> Message(stringResource(R.string.library_setup_text)) {
                    Button(onClick = { chooseFolder() }) { Text(stringResource(R.string.library_setup_choose)) }
                }
                list == null -> Unit
                list.isEmpty() -> Message(
                    stringResource(
                        when {
                            trail.size > 1 -> R.string.library_empty_folder
                            mode == LibraryMode.SIGNED -> R.string.library_empty_signed
                            else -> R.string.library_empty_all
                        },
                    ),
                ) {}
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    if (trail.size > 1) item {
                        Text(
                            "$title / ${path(trail)}",
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(list, key = { it.id }) { e ->
                        EntryRow(
                            context, e,
                            onOpen = { if (e.isFolder) trail += e.id to e.name else host.open(LibraryStore.uri(root, e.id)) },
                            onRename = { renaming = e },
                            onMove = { moving = e },
                            onShare = { share(context, LibraryStore.uri(root, e.id)) },
                            onDelete = { deleting = e },
                        )
                    }
                }
            }
        }
    }

    if (newFolder && root != null && here != null) {
        NameDialog(stringResource(R.string.library_new_folder), "", stringResource(R.string.library_folder_name), onCancel = { newFolder = false }) { name ->
            newFolder = false
            val clean = LibraryStore.folderName(name)
            if (clean.isNotEmpty()) change(R.string.library_failed) { LibraryStore.createFolder(context, it, here.first, clean) != null }
        }
    }
    renaming?.let { e ->
        NameDialog(stringResource(R.string.library_rename), e.name, stringResource(R.string.library_file_name), onCancel = { renaming = null }) { name ->
            renaming = null
            val clean = if (e.isFolder) LibraryStore.folderName(name) else LibraryStore.pdfName(name)
            if (clean.isNotEmpty() && clean != e.name) change(R.string.library_failed) { LibraryStore.rename(context, it, e.id, clean) }
        }
    }
    deleting?.let { e ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.library_delete_title, e.name)) },
            text = { Text(stringResource(if (e.isFolder) R.string.library_delete_folder_text else R.string.library_delete_text)) },
            confirmButton = {
                TextButton(onClick = { deleting = null; change(R.string.library_failed) { LibraryStore.delete(context, it, e.id) } }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    val toMove = moving
    if (toMove != null && root != null && here != null) {
        FolderChooser(
            context, root, title,
            confirmLabel = stringResource(R.string.library_move_here),
            exclude = toMove.id.takeIf { toMove.isFolder },
            onCancel = { moving = null },
        ) { target, _ ->
            moving = null
            if (target != here.first) change(R.string.library_move_failed) { LibraryStore.move(context, it, toMove.id, here.first, target) }
        }
    }
    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text(stringResource(R.string.library_options)) },
            text = {
                Column {
                    Text(stringResource(R.string.library_mode_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    for ((m, label) in listOf(LibraryMode.ALL to R.string.library_mode_all, LibraryMode.SIGNED to R.string.library_mode_signed)) {
                        androidx.compose.foundation.layout.Row(
                            Modifier.fillMaxWidth().selectable(mode == m, role = Role.RadioButton) {
                                mode = m
                                LibraryStore.setMode(context, m)
                                if (trail.isNotEmpty()) trail[0] = trail[0].first to LibraryModule.libraryName(context)
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = mode == m, onClick = null)
                            Text(stringResource(label), Modifier.padding(start = 16.dp))
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(
                        stringResource(if (root == null) R.string.library_no_folder else R.string.library_folder_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { showSettings = false; chooseFolder() }) {
                        Text(stringResource(if (root == null) R.string.library_setup_choose else R.string.library_change_root))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSettings = false }) { Text(stringResource(R.string.done)) } },
        )
    }
}

@Composable
private fun Message(text: String, action: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        action()
    }
}

@Composable
private fun EntryRow(
    context: Context, e: LibEntry,
    onOpen: () -> Unit, onRename: () -> Unit, onMove: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        leadingContent = {
            Icon(
                if (e.isFolder) Icons.Filled.Folder else Icons.Filled.PictureAsPdf, null,
                tint = if (e.isFolder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        headlineContent = { Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            if (!e.isFolder) {
                val date = if (e.modified > 0) DateUtils.formatDateTime(
                    context, e.modified, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH,
                ) else ""
                Text(listOf(date, Formatter.formatShortFileSize(context, e.size)).filter { it.isNotEmpty() }.joinToString("  ·  "))
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.library_actions, e.name)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    @Composable
                    fun item(label: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, run: () -> Unit) = DropdownMenuItem(
                        text = { Text(stringResource(label)) },
                        leadingIcon = { Icon(icon, null) },
                        onClick = { menu = false; run() },
                    )
                    item(R.string.library_rename, Icons.Filled.Edit, onRename)
                    item(R.string.library_move, Icons.AutoMirrored.Filled.DriveFileMove, onMove)
                    if (!e.isFolder) item(R.string.share, Icons.Filled.Share, onShare)
                    item(R.string.delete, Icons.Filled.Delete, onDelete)
                }
            }
        },
    )
}

private fun share(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/pdf")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun NameDialog(title: String, initial: String, label: String, onCancel: () -> Unit, onDone: (String) -> Unit) {
    // Select the name without its ".pdf" so typing replaces just the name.
    val stem = if (initial.endsWith(".pdf", ignoreCase = true)) initial.length - 4 else initial.length
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, stem))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value, onValueChange = { value = it }, singleLine = true, label = { Text(label) },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onDone(value.text) }, enabled = value.text.isNotBlank()) { Text(stringResource(R.string.done)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Pick a folder inside the library: walk down by tapping folders, back up with
 * the first row, make a new one on the spot. [exclude] is a folder that must
 * not be offered (a folder cannot be moved into itself).
 */
@Composable
fun FolderChooser(
    context: Context, tree: Uri, libraryName: String, confirmLabel: String,
    exclude: String? = null, onCancel: () -> Unit, onChosen: (id: String, path: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val steps = remember { mutableStateListOf(LibraryStore.rootId(tree) to libraryName) }
    var folders by remember { mutableStateOf<List<LibEntry>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var naming by remember { mutableStateOf(false) }
    val here = steps.last()

    LaunchedEffect(here.first, reload) {
        folders = withContext(Dispatchers.IO) {
            runCatching { LibraryStore.list(context, tree, here.first) }.getOrDefault(emptyList())
        }.filter { it.isFolder && it.id != exclude }
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (steps.size > 1) "$libraryName / ${path(steps)}" else libraryName, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    if (steps.size > 1) item {
                        ListItem(
                            modifier = Modifier.clickable { steps.removeAt(steps.lastIndex) },
                            leadingContent = { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) },
                            headlineContent = { Text(stringResource(R.string.library_up)) },
                        )
                    }
                    items(folders, key = { it.id }) { f ->
                        ListItem(
                            modifier = Modifier.clickable { steps += f.id to f.name },
                            leadingContent = { Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                            headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                    if (folders.isEmpty()) item {
                        Text(
                            stringResource(R.string.library_no_subfolders),
                            Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = { naming = true }) {
                    Icon(Icons.Filled.CreateNewFolder, null)
                    Text(stringResource(R.string.library_new_folder), Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onChosen(here.first, path(steps)) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )

    if (naming) {
        NameDialog(stringResource(R.string.library_new_folder), "", stringResource(R.string.library_folder_name), onCancel = { naming = false }) { name ->
            naming = false
            val clean = LibraryStore.folderName(name)
            if (clean.isNotEmpty()) scope.launch {
                val id = withContext(Dispatchers.IO) { runCatching { LibraryStore.createFolder(context, tree, here.first, clean) }.getOrNull() }
                if (id != null) steps += id to clean else reload++
            }
        }
    }
}
