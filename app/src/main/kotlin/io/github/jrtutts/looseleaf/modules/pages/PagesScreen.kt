package io.github.jrtutts.looseleaf.modules.pages

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.jrtutts.looseleaf.R
import io.github.jrtutts.looseleaf.core.model.PageEntry
import io.github.jrtutts.looseleaf.core.module.EditorHost
import io.github.jrtutts.looseleaf.core.pdf.PageGeometry
import io.github.jrtutts.looseleaf.core.render.ElementPainter
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PagesScreen(host: EditorHost) {
    val session = host.session
    val pages = session.model.pages
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var extracting by remember { mutableStateOf(false) }
    var dragToken by remember { mutableStateOf(Any()) }

    // Pages can disappear through undo; forget selections that no longer exist.
    val live = selected.filter { key -> pages.any { it.key == key } }.toSet()
    if (live.size != selected.size) selected = live
    val selectedIndices = pages.indices.filter { pages[it].key in selected }

    val gridState = rememberLazyGridState()
    val reorder = rememberReorderableLazyGridState(gridState) { from, to ->
        session.edit(dragToken) { m ->
            m.copy(pages = m.pages.toMutableList().apply { add(to.index, removeAt(from.index)) })
        }
    }

    fun rotate(by: Int) = session.edit { m ->
        m.copy(pages = m.pages.map { if (it.key in selected) it.copy(rotation = (it.rotation + by + 360) % 360) else it })
    }

    fun delete() {
        if (selected.size >= pages.size) {
            host.message(host.context.getString(R.string.pages_keep_one))
            return
        }
        session.edit { m -> m.copy(pages = m.pages.filterNot { it.key in selected }) }
        selected = emptySet()
    }

    fun addPdf() = host.pickPdf { uri ->
        host.scope.launch {
            val added = host.busy(host.context.getString(R.string.pages_adding)) { host.importPdf(uri) } ?: return@launch
            // New pages go after the last selected page, or at the end.
            val at = (selectedIndices.lastOrNull() ?: (session.model.pages.size - 1)) + 1
            session.edit { m -> m.copy(pages = m.pages.toMutableList().apply { addAll(at.coerceAtMost(size), added) }) }
            host.message(host.context.resources.getQuantityString(R.plurals.pages_added, added.size, added.size))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selected.isEmpty()) stringResource(R.string.pages_menu)
                        else pluralStringResource(R.plurals.pages_selected, selected.size, selected.size),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { host.closeScreen() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    if (selected.size == pages.size) {
                        IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Filled.Deselect, stringResource(R.string.pages_select_none)) }
                    } else {
                        IconButton(onClick = { selected = pages.map { it.key }.toSet() }) { Icon(Icons.Filled.SelectAll, stringResource(R.string.pages_select_all)) }
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)
                        .horizontalScroll(rememberScrollState()).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    val any = selected.isNotEmpty()
                    Action(Icons.AutoMirrored.Filled.RotateLeft, stringResource(R.string.pages_rotate_left), any) { rotate(-90) }
                    Action(Icons.AutoMirrored.Filled.RotateRight, stringResource(R.string.pages_rotate_right), any) { rotate(90) }
                    Action(Icons.Filled.Delete, stringResource(R.string.delete), any) { delete() }
                    Action(Icons.Filled.ContentCut, stringResource(R.string.pages_extract), true) { extracting = true }
                    Action(Icons.Filled.NoteAdd, stringResource(R.string.pages_add_pdf), true) { addPdf() }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                stringResource(R.string.pages_hint),
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(104.dp),
                state = gridState,
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(pages, key = { it.key }) { page ->
                    ReorderableItem(reorder, key = page.key) { dragging ->
                        Thumbnail(
                            host, page,
                            number = pages.indexOf(page) + 1,
                            selected = page.key in selected,
                            modifier = Modifier
                                .longPressDraggableHandle(onDragStarted = { dragToken = Any() })
                                .then(if (dragging) Modifier.shadow(8.dp).alpha(0.9f) else Modifier),
                        ) { selected = if (page.key in selected) selected - page.key else selected + page.key }
                    }
                }
            }
        }
    }

    if (extracting) {
        ExtractDialog(
            pageCount = pages.size,
            initial = PageRanges.format(selectedIndices),
            onCancel = { extracting = false },
        ) { indices ->
            extracting = false
            val model = session.model
            val part = model.copy(pages = indices.map { model.pages[it] })
            val name = host.context.getString(R.string.pages_extract_name, session.baseName, PageRanges.format(indices).replace(", ", "_"))
            host.saveCopy(name) { out -> host.export(out, part) }
        }
    }
}

@Composable
private fun Action(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f)
    Column(
        Modifier.widthIn(min = 72.dp).clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

@Composable
private fun Thumbnail(host: EditorHost, page: PageEntry, number: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    // Re-rendered whenever the page (rotation, elements) changes.
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(page) { image = renderThumbnail(host, page, 320).asImageBitmap() }
    val shape = RoundedCornerShape(6.dp)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(page.shownWidth / page.shownHeight)
                .clip(shape)
                .background(Color.White)
                .border(
                    if (selected) 3.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape,
                )
                .clickable(onClick = onClick),
        ) {
            image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary) }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("$number", style = MaterialTheme.typography.labelMedium)
    }
}

private suspend fun renderThumbnail(host: EditorHost, page: PageEntry, widthPx: Int): Bitmap {
    val scale = widthPx / page.shownWidth
    val bmp = Bitmap.createBitmap(widthPx, (page.shownHeight * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    bmp.eraseColor(AColor.WHITE)
    val m = PageGeometry.displayMatrix(page, scale, 0f, 0f)
    host.renderer.render(page.source, page.index, bmp, m)
    val canvas = Canvas(bmp)
    canvas.concat(m)
    for (e in page.elements) ElementPainter.draw(canvas, e)
    return bmp
}

@Composable
private fun ExtractDialog(pageCount: Int, initial: String, onCancel: () -> Unit, onExtract: (List<Int>) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val parsed = PageRanges.parse(text, pageCount)
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.pages_extract_title)) },
        text = {
            Column {
                Text(pluralStringResource(R.plurals.pages_extract_text, pageCount, pageCount))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.pages_extract_label)) },
                    placeholder = { Text("1-3, 5") },
                    isError = text.isNotBlank() && parsed == null,
                    supportingText = { if (text.isNotBlank() && parsed == null) Text(stringResource(R.string.pages_extract_invalid, pageCount)) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onExtract) }, enabled = parsed != null) { Text(stringResource(R.string.pages_extract_confirm)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}
