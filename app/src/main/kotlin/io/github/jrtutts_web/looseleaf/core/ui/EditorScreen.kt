package io.github.jrtutts_web.looseleaf.core.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jrtutts_web.looseleaf.R
import io.github.jrtutts_web.looseleaf.core.module.Tool

/** The editor: top bar, page view, and a bottom area with tool options and the toolbar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(ctl: EditorController, onClose: () -> Unit, onSettings: () -> Unit) {
    val session = ctl.session
    var confirmClose by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { ctl.onPicked(it) }
    val creator = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { ctl.onCreated(it) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { ctl.onFolderPicked(it) }
    DisposableEffect(ctl) {
        ctl.launchPicker = { picker.launch(arrayOf(it)) }
        ctl.launchCreate = { creator.launch(it) }
        ctl.launchFolderPicker = { folderPicker.launch(null) }
        onDispose { ctl.launchPicker = null; ctl.launchCreate = null; ctl.launchFolderPicker = null }
    }

    val modules = ctl.modules
    LaunchedEffect(modules) { ctl.syncModules() }
    val tools = modules.flatMap { it.tools }
    val menuActions = modules.flatMap { it.menuActions }

    fun close() { if (session.isDirty) confirmClose = true else onClose() }

    BackHandler(enabled = ctl.moduleScreen == null) {
        when {
            ctl.activeTool != null -> ctl.activate(null)
            session.selection != null -> session.selection = null
            else -> close()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(session.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = { close() }) { Icon(Icons.Filled.Close, stringResource(R.string.close)) } },
                actions = {
                    IconButton(onClick = { session.undo() }, enabled = session.canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo))
                    }
                    IconButton(onClick = { session.redo() }, enabled = session.canRedo) {
                        Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.redo))
                    }
                    IconButton(onClick = { ctl.saveDocument() }) { Icon(Icons.Filled.SaveAlt, stringResource(R.string.save_copy)) }
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more)) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.save_copy)) },
                            leadingIcon = { Icon(Icons.Filled.SaveAlt, null) },
                            onClick = { menuOpen = false; ctl.saveDocument() },
                        )
                        for (a in menuActions) {
                            DropdownMenuItem(
                                text = { Text(stringResource(a.label)) },
                                leadingIcon = { Icon(a.icon, null) },
                                onClick = { menuOpen = false; ctl.activate(null); a.run(ctl) },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings)) },
                            leadingIcon = { Icon(Icons.Filled.Settings, null) },
                            onClick = { menuOpen = false; onSettings() },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(ctl.snackbar) },
        bottomBar = { BottomArea(ctl, tools) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            PageViewer(ctl)
            PageIndicator(ctl, Modifier.align(Alignment.TopEnd).padding(12.dp))
            ElementMenu(ctl)
        }
    }

    ctl.moduleScreen?.let { screen ->
        BackHandler { ctl.closeScreen() }
        Surface(Modifier.fillMaxSize()) {
            Box {
                screen()
                // Messages must stay visible while a module screen covers the editor.
                SnackbarHost(ctl.snackbar, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars))
            }
        }
    }
    ctl.dialog?.let { content -> content { ctl.dismissDialog() } }
    ctl.busyMessage?.let { BusyDialog(it) }

    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text(stringResource(R.string.unsaved_title)) },
            text = { Text(stringResource(R.string.unsaved_text)) },
            confirmButton = {
                TextButton(onClick = { confirmClose = false; ctl.saveDocument(then = onClose) }) { Text(stringResource(R.string.save_copy)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmClose = false }) { Text(stringResource(R.string.cancel)) }
                    TextButton(onClick = { confirmClose = false; onClose() }) { Text(stringResource(R.string.discard)) }
                }
            },
        )
    }
}

@Composable
private fun PageIndicator(ctl: EditorController, modifier: Modifier) {
    val vs = ctl.viewer
    val count = vs.layout.slots.size
    if (count == 0) return
    val current = (vs.centerSlot()?.index ?: 0) + 1
    Surface(modifier, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.75f)) {
        Text(
            stringResource(R.string.page_of, current, count),
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.inverseOnSurface,
        )
    }
}

/** Long-press menu of an element. */
@Composable
private fun ElementMenu(ctl: EditorController) {
    val req = ctl.elementMenu ?: return
    val element = ctl.session.model.findElement(req.elementId)?.second ?: return
    Box(Modifier.offset { req.offset }) {
        DropdownMenu(expanded = true, onDismissRequest = { ctl.elementMenu = null }) {
            for (a in ctl.moduleOf(element)?.elementActions(ctl, element).orEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(a.label)) },
                    leadingIcon = { Icon(a.icon, null) },
                    onClick = { ctl.elementMenu = null; a.run(ctl) },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.duplicate)) },
                leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                onClick = { ctl.elementMenu = null; ctl.duplicateSelected() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete)) },
                leadingIcon = { Icon(Icons.Filled.Delete, null) },
                onClick = { ctl.elementMenu = null; ctl.deleteSelected() },
            )
        }
    }
}

@Composable
private fun BottomArea(ctl: EditorController, tools: List<Tool>) {
    val selected = ctl.selected()
    val tool = ctl.activeTool
    if (tools.isEmpty() && selected == null) return
    Surface(tonalElevation = 3.dp) {
        // Above the keyboard when it is open (the Find tool types right here), else above the navigation bar.
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))) {
            if (selected != null) {
                val (slot, element) = selected
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(start = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val page = ctl.session.model.page(slot.page.key) ?: slot.page
                        ctl.moduleOf(element)?.SelectionOptions(ctl, page, element)
                    }
                    IconButton(onClick = { ctl.duplicateSelected() }) { Icon(Icons.Filled.ContentCopy, stringResource(R.string.duplicate)) }
                    IconButton(onClick = { ctl.deleteSelected() }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
                }
                HorizontalDivider()
            } else if (tool != null) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) { tool.Options(ctl) }
                HorizontalDivider()
            }
            if (tools.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    for (t in tools) ToolButton(t, active = t === tool) { ctl.activate(if (t === tool) null else t) }
                }
            }
        }
    }
}

@Composable
private fun ToolButton(tool: Tool, active: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .widthIn(min = 68.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) colors.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val tint = if (active) colors.onSecondaryContainer else colors.onSurfaceVariant
        Icon(tool.icon, null, tint = tint)
        Text(stringResource(tool.label), style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
    }
}
