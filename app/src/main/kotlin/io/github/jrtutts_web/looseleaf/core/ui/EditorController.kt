package io.github.jrtutts_web.looseleaf.core.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import com.tom_roush.pdfbox.pdmodel.PDDocument
import io.github.jrtutts_web.looseleaf.R
import io.github.jrtutts_web.looseleaf.core.model.DocumentModel
import io.github.jrtutts_web.looseleaf.core.model.EditorSession
import io.github.jrtutts_web.looseleaf.core.model.Ids
import io.github.jrtutts_web.looseleaf.core.model.InkStroke
import io.github.jrtutts_web.looseleaf.core.model.LineMark
import io.github.jrtutts_web.looseleaf.core.model.PPoint
import io.github.jrtutts_web.looseleaf.core.model.PRect
import io.github.jrtutts_web.looseleaf.core.model.PageElement
import io.github.jrtutts_web.looseleaf.core.model.PageEntry
import io.github.jrtutts_web.looseleaf.core.model.ResizeMode
import io.github.jrtutts_web.looseleaf.core.model.TextBox
import io.github.jrtutts_web.looseleaf.core.module.EditorHost
import io.github.jrtutts_web.looseleaf.core.module.EditorModule
import io.github.jrtutts_web.looseleaf.core.module.PageHit
import io.github.jrtutts_web.looseleaf.core.module.Tool
import io.github.jrtutts_web.looseleaf.core.pdf.PageRasterizer
import io.github.jrtutts_web.looseleaf.core.pdf.PdfRenderEngine
import io.github.jrtutts_web.looseleaf.core.pdf.PdfWriter
import io.github.jrtutts_web.looseleaf.core.render.TextLayout
import io.github.jrtutts_web.looseleaf.core.session.SessionStore
import io.github.jrtutts_web.looseleaf.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class HandleKind { LT, RT, RB, LB, L, R, T, B, START, END }

/** What a one-finger drag is doing. */
enum class DragMode { SCROLL, DRAW, MOVE, RESIZE }

class ElementMenuRequest(val offset: IntOffset, val elementId: Long)

/**
 * The editor behind the screen: implements [EditorHost] for modules, and turns
 * gestures on the page view into scrolling, tool calls and edits of the
 * selected element. It lives in the view model, so it survives anything the
 * activity goes through.
 */
class EditorController(
    override val context: Context,
    override val session: EditorSession,
    override val renderer: PdfRenderEngine,
    private val settings: Settings,
    override val scope: CoroutineScope,
) : EditorHost {

    val viewer = ViewerState()
    val snackbar = SnackbarHostState()
    val pageBitmaps = mutableStateMapOf<Long, PageBitmap>()
    var patch by mutableStateOf<Patch?>(null)
    var gestureActive by mutableStateOf(false)
    var density = 1f

    /** A scope tied to the screen, for animations (which need the UI frame clock). */
    var uiScope: CoroutineScope? = null

    override var activeTool by mutableStateOf<Tool?>(null)
        private set
    var moduleScreen by mutableStateOf<(@Composable () -> Unit)?>(null)
        private set
    var dialog by mutableStateOf<(@Composable (() -> Unit) -> Unit)?>(null)
        private set
    var busyMessage by mutableStateOf<String?>(null)
        private set
    var elementMenu by mutableStateOf<ElementMenuRequest?>(null)

    /** Wired up by the editor screen, which owns the system file pickers. */
    var launchPicker: ((String) -> Unit)? = null
    var launchCreate: ((String) -> Unit)? = null
    var launchFolderPicker: (() -> Unit)? = null
    private var pendingFolder: ((Uri) -> Unit)? = null
    private var pendingPick: ((Uri) -> Unit)? = null
    private var pendingSave: PendingSave? = null
    private class PendingSave(val onSaved: () -> Unit, val write: suspend (File) -> Unit)

    val modules: List<EditorModule> get() = settings.enabledModules

    /**
     * Keeps the session on disk a moment after every change, so unsaved edits
     * can be offered again if the app is closed by the system or crashes.
     */
    private val autosave: Job = scope.launch {
        snapshotFlow { session.model to session.isDirty }.drop(1).collectLatest { (model, dirty) ->
            delay(400)
            val sources = session.sources.map { it.file.name }
            withContext(Dispatchers.IO) {
                runCatching {
                    SessionStore.write(session.dir, session.displayName, session.sourceUri?.toString(), sources, model, dirty)
                }
            }
        }
    }

    /** The session is over; stop writing to its directory. */
    fun close() = autosave.cancel()
    private val opened = HashSet<String>()

    fun moduleOf(e: PageElement): EditorModule? = modules.firstOrNull { it.id == e.owner }

    /** Tell modules that were just switched on about the document, and drop a tool whose module is gone. */
    fun syncModules() {
        val now = modules
        for (m in now) if (opened.add(m.id)) m.onDocumentOpened(this)
        opened.retainAll(now.map { it.id }.toSet())
        val tool = activeTool
        if (tool != null && now.none { m -> m.tools.any { it === tool } }) activeTool = null
    }

    // ---- EditorHost ----

    override fun activate(tool: Tool?) {
        activeTool?.onDragCancel(this)
        session.preview = null
        if (tool == null) { activeTool = null; return }
        session.selection = null
        activeTool = if (tool.onActivated(this)) tool else null
    }

    override fun message(text: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text, duration = SnackbarDuration.Short)
        }
    }

    override fun showScreen(content: @Composable () -> Unit) { moduleScreen = content }
    override fun closeScreen() { moduleScreen = null }

    override fun showDialog(content: @Composable (dismiss: () -> Unit) -> Unit) { dialog = content }
    fun dismissDialog() { dialog = null }

    override fun viewCenter(): PageHit? {
        val s = viewer.centerSlot() ?: return null
        val p = viewer.toPage(s, viewer.viewW / 2f, viewer.viewH / 2f)
        return PageHit(s.page, s.index, PPoint(p.x.coerceIn(0f, s.page.width), p.y.coerceIn(0f, s.page.height)))
    }

    override fun scrollToPage(index: Int) = viewer.scrollToPage(index)

    override fun scrollTo(pageIndex: Int, rect: PRect) = viewer.scrollTo(pageIndex, rect)

    override suspend fun <T> busy(message: String, block: suspend (progress: (String) -> Unit) -> T): T {
        busyMessage = message
        try {
            return block { busyMessage = it }
        } finally {
            busyMessage = null
        }
    }

    override fun pickPdf(onPicked: (Uri) -> Unit) = pickFile("application/pdf", onPicked)

    override fun pickFile(mimeType: String, onPicked: (Uri) -> Unit) {
        pendingPick = onPicked
        launchPicker?.invoke(mimeType)
    }

    fun onPicked(uri: Uri?) {
        val cb = pendingPick
        pendingPick = null
        if (uri != null) cb?.invoke(uri)
    }

    override fun saveCopy(suggestedName: String, onSaved: () -> Unit, write: suspend (File) -> Unit) {
        pendingSave = PendingSave(onSaved, write)
        launchCreate?.invoke(suggestedName)
    }

    /** Save the document being edited as a new file. A module may offer its own destination first. */
    fun saveDocument(then: () -> Unit = {}) {
        val done = { session.markSaved(); then() }
        if (modules.none { it.onSave(this, done) }) saveDocumentWithPicker(done)
    }

    override fun saveDocumentWithPicker(onSaved: () -> Unit) {
        saveCopy(context.getString(R.string.save_suggested_name, session.baseName), onSaved) { out -> export(out) }
    }

    override fun saveDocumentTo(create: suspend () -> Uri, onSaved: () -> Unit) {
        scope.launch { deliver(onSaved, { out -> export(out) }) { withContext(Dispatchers.IO) { create() } } }
    }

    override fun pickFolder(onPicked: (Uri) -> Unit) {
        pendingFolder = onPicked
        launchFolderPicker?.invoke()
    }

    fun onFolderPicked(uri: Uri?) {
        val cb = pendingFolder
        pendingFolder = null
        if (uri != null) {
            keepFolderAccess(context, uri)
            cb?.invoke(uri)
        }
    }

    fun onCreated(uri: Uri?) {
        val save = pendingSave
        pendingSave = null
        if (uri == null || save == null) return
        scope.launch {
            // The system picker lets the user pick an existing file and confirm "replace". If that
            // file is the original (or any PDF that was opened or added in this session) we refuse.
            if (uri == session.sourceUri || withContext(Dispatchers.IO) { isAnOriginal(uri) }) {
                message(context.getString(R.string.save_refuse_original))
                return@launch
            }
            deliver(save.onSaved, save.write) { uri }
        }
    }

    /** Produce the file with [write], then copy it to the address [target] gives, and tell the user. */
    private suspend fun deliver(onSaved: () -> Unit, write: suspend (File) -> Unit, target: suspend () -> Uri) {
        val temp = File(session.dir, "out-${System.nanoTime()}.pdf")
        var uri: Uri? = null
        try {
            busy(context.getString(R.string.saving)) {
                write(temp)
                val to = target().also { uri = it }
                withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openOutputStream(to, "wt")
                        ?: throw java.io.IOException("Cannot write to the chosen location")
                    stream.use { out -> temp.inputStream().use { it.copyTo(out) } }
                }
            }
            onSaved()
            val to = uri ?: return
            val name = displayName(context, to) ?: to.lastPathSegment ?: ""
            val result = snackbar.showSnackbar(
                context.getString(R.string.saved_as, name),
                actionLabel = context.getString(R.string.share),
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) share(to)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Do not leave an empty or half-written file behind.
            uri?.let { runCatching { DocumentsContract.deleteDocument(context.contentResolver, it) } }
            message(context.getString(R.string.save_failed, e.message ?: e.javaClass.simpleName))
        } finally {
            temp.delete()
        }
    }

    /**
     * True if the file at [uri] already has the same content as one of the PDFs
     * copied into this session. The address alone is not enough: a PDF that
     * was shared to the app has a different address than the same file in the
     * system file picker.
     */
    private fun isAnOriginal(uri: Uri): Boolean {
        val originals = session.dir.listFiles { f -> f.name.startsWith("src") && f.name.endsWith(".pdf") }.orEmpty()
        if (originals.isEmpty()) return false
        return try {
            val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            if (size == 0L) return false   // a file the picker just created
            originals.any { o ->
                (size < 0 || size == o.length()) && context.contentResolver.openInputStream(uri)?.use { a ->
                    o.inputStream().buffered().use { b -> sameBytes(a.buffered(), b) }
                } == true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun sameBytes(a: java.io.InputStream, b: java.io.InputStream): Boolean {
        val x = ByteArray(64 * 1024)
        val y = ByteArray(64 * 1024)
        while (true) {
            val n = a.readNBytesCompat(x)
            val m = b.readNBytesCompat(y)
            if (n != m) return false
            if (n == 0) return true
            for (i in 0 until n) if (x[i] != y[i]) return false
        }
    }

    private fun java.io.InputStream.readNBytesCompat(buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun share(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching {
            context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override suspend fun export(out: File, model: DocumentModel, postProcess: ((PDDocument) -> Unit)?) {
        val sources = session.sources.toList()
        // Pages with a redaction are saved as pictures; make those first (needs the renderer).
        val rasters = PageRasterizer.rasterize(model, renderer, session.dir)
        try {
            withContext(Dispatchers.IO) { PdfWriter.write(context, sources, model, out, rasters, postProcess) }
        } finally {
            rasters.values.forEach { it.file.delete() }
        }
    }

    override suspend fun importPdf(uri: Uri): List<PageEntry>? {
        val file = session.nextSourceFile()
        val id = session.sources.size
        return try {
            withContext(Dispatchers.IO) {
                val input = context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("unreadable")
                input.use { i -> file.outputStream().use { i.copyTo(it) } }
            }
            val sizes = renderer.openWithSizes(id, file)
            session.addSource(file)
            sizes.mapIndexed { i, (w, h) -> PageEntry(Ids.next(), id, i, w, h) }
        } catch (e: SecurityException) {
            file.delete()
            message(context.getString(R.string.import_password))
            null
        } catch (e: Exception) {
            file.delete()
            message(context.getString(R.string.open_failed))
            null
        }
    }

    // ---- Gestures from the page view ----

    private fun hitAt(pos: Offset): PageHit? {
        val s = viewer.slotAt(pos.x, pos.y) ?: return null
        return PageHit(s.page, s.index, viewer.toPage(s, pos.x, pos.y))
    }

    private fun slotOf(pageKey: Long) = viewer.layout.slots.firstOrNull { it.page.key == pageKey }

    fun handleOutset(s: PageSlot) = 12f * density / viewer.pixelsPerPoint(s)

    private fun touchSlop(s: PageSlot) = 14f * density / viewer.pixelsPerPoint(s)

    private fun elementAt(pos: Offset): Pair<PageSlot, PageElement>? {
        val s = viewer.slotAt(pos.x, pos.y) ?: return null
        val p = viewer.toPage(s, pos.x, pos.y)
        val slop = touchSlop(s)
        val e = s.page.elements.lastOrNull { it.resize != ResizeMode.NONE && hits(it, p, slop) } ?: return null
        return s to e
    }

    private fun hits(e: PageElement, p: PPoint, slop: Float): Boolean = when (e) {
        is LineMark -> nearPolyline(listOf(e.start, e.end), p, e.width / 2f + slop)
        is InkStroke -> e.bounds.contains(p, slop) && nearPolyline(e.points, p, e.width / 2f + slop)
        else -> e.bounds.contains(p, slop / 2f)
    }

    private fun nearPolyline(pts: List<PPoint>, p: PPoint, d: Float): Boolean {
        if (pts.size == 1) return hypot(pts[0].x - p.x, pts[0].y - p.y) <= d
        for (i in 0 until pts.size - 1) {
            val a = pts[i]; val b = pts[i + 1]
            val vx = b.x - a.x; val vy = b.y - a.y
            val len2 = vx * vx + vy * vy
            val t = if (len2 == 0f) 0f else (((p.x - a.x) * vx + (p.y - a.y) * vy) / len2).coerceIn(0f, 1f)
            if (hypot(a.x + t * vx - p.x, a.y + t * vy - p.y) <= d) return true
        }
        return false
    }

    /** The selected element with the page it is on. */
    fun selected(): Pair<PageSlot, PageElement>? {
        val id = session.selection ?: return null
        val (page, e) = session.model.findElement(id) ?: return null
        val slot = slotOf(page.key) ?: return null
        return slot to e
    }

    /** Handle positions in page space. Side handles sit [outset] outside the box so they do not cover its text. */
    fun handlesOf(e: PageElement, outset: Float): List<Pair<HandleKind, PPoint>> {
        val b = e.bounds
        return when (e.resize) {
            ResizeMode.NONE -> emptyList()
            ResizeMode.ENDS -> (e as? LineMark)?.let { listOf(HandleKind.START to it.start, HandleKind.END to it.end) }.orEmpty()
            ResizeMode.FREE, ResizeMode.ASPECT -> listOf(
                HandleKind.LT to PPoint(b.left, b.top), HandleKind.RT to PPoint(b.right, b.top),
                HandleKind.RB to PPoint(b.right, b.bottom), HandleKind.LB to PPoint(b.left, b.bottom),
            )
            ResizeMode.WIDTH -> if (e.turns % 2 == 0) {
                listOf(HandleKind.L to PPoint(b.left - outset, b.centerY), HandleKind.R to PPoint(b.right + outset, b.centerY))
            } else {
                listOf(HandleKind.T to PPoint(b.centerX, b.top - outset), HandleKind.B to PPoint(b.centerX, b.bottom + outset))
            }
        }
    }

    private var lastTapTime = 0L
    private var lastTapPos = Offset.Zero

    fun onTap(pos: Offset, time: Long) {
        elementMenu = null
        val tool = activeTool
        if (tool != null) {
            hitAt(pos)?.let { tool.onTap(this, it) }
            return
        }
        val hit = elementAt(pos)
        if (hit != null) {
            val (_, e) = hit
            if (session.selection == e.id) moduleOf(e)?.onSelectedElementTapped(this, e)
            else session.selection = e.id
            lastTapTime = 0
            return
        }
        session.selection = null
        // Double tap on the page: zoom in on that spot, or back out.
        if (time - lastTapTime < 300 && (pos - lastTapPos).getDistance() < 40 * density) {
            lastTapTime = 0
            val target = if (viewer.zoom > 1.2f) 1f else 2.5f
            uiScope?.launch {
                androidx.compose.animation.core.animate(viewer.zoom, target) { v, _ -> viewer.zoomTo(v, pos.x, pos.y) }
            }
        } else {
            lastTapTime = time
            lastTapPos = pos
        }
    }

    fun onLongPress(pos: Offset) {
        if (activeTool?.dragDraws == true) return
        val (_, e) = elementAt(pos) ?: return
        session.selection = e.id
        elementMenu = ElementMenuRequest(IntOffset(pos.x.toInt(), pos.y.toInt()), e.id)
    }

    private var dragPageKey = 0L
    private var dragOrigin: PageElement? = null
    private var dragStartPoint = PPoint(0f, 0f)
    private var dragHandle = HandleKind.LT
    private var dragToken = Any()

    /** A one-finger drag has started at [start]; decide what it does. */
    fun onDragBegin(start: Offset): DragMode {
        elementMenu = null
        val tool = activeTool
        if (tool != null) {
            if (!tool.dragDraws) return DragMode.SCROLL
            val hit = hitAt(start) ?: return DragMode.SCROLL
            dragPageKey = hit.page.key
            tool.onDragStart(this, hit)
            return DragMode.DRAW
        }
        val sel = selected() ?: return DragMode.SCROLL
        val (slot, e) = sel
        val p = viewer.toPage(slot, start.x, start.y)
        val reach = 22f * density / viewer.pixelsPerPoint(slot)
        val handle = handlesOf(e, handleOutset(slot)).minByOrNull { hypot(it.second.x - p.x, it.second.y - p.y) }
        dragPageKey = slot.page.key
        dragOrigin = e
        dragStartPoint = p
        dragToken = Any()
        if (handle != null && hypot(handle.second.x - p.x, handle.second.y - p.y) <= reach) {
            dragHandle = handle.first
            return DragMode.RESIZE
        }
        val onIt = if (e is LineMark) hits(e, p, touchSlop(slot) * 1.5f) else e.bounds.contains(p, touchSlop(slot))
        if (onIt) return DragMode.MOVE
        return DragMode.SCROLL
    }

    fun onDrag(mode: DragMode, pos: Offset) {
        val slot = slotOf(dragPageKey) ?: return
        val raw = viewer.toPage(slot, pos.x, pos.y)
        val page = slot.page
        when (mode) {
            DragMode.DRAW -> activeTool?.onDrag(
                this, PPoint(raw.x.coerceIn(0f, page.width), raw.y.coerceIn(0f, page.height)),
            )
            DragMode.MOVE -> {
                val origin = dragOrigin ?: return
                val moved = origin.bounds
                    .offset(raw.x - dragStartPoint.x, raw.y - dragStartPoint.y)
                    .movedInside(page.bounds)
                session.edit(dragToken) { m -> m.updateElement(origin.id) { origin.withBounds(moved) } }
            }
            DragMode.RESIZE -> {
                val origin = dragOrigin ?: return
                if (origin is LineMark) {
                    val p = PPoint(raw.x.coerceIn(0f, page.width), raw.y.coerceIn(0f, page.height))
                    val next = if (dragHandle == HandleKind.START) origin.copy(start = p) else origin.copy(end = p)
                    session.edit(dragToken) { m -> m.updateElement(origin.id) { next } }
                    return
                }
                val r = resized(origin, dragHandle, raw, page.bounds)
                session.edit(dragToken) { m -> m.updateElement(origin.id) { normalize(origin.withBounds(r)) } }
            }
            DragMode.SCROLL -> Unit
        }
    }

    fun onDragEnd(mode: DragMode, cancelled: Boolean) {
        if (mode == DragMode.DRAW) {
            if (cancelled) activeTool?.onDragCancel(this) else activeTool?.onDragEnd(this)
        }
        dragOrigin = null
    }

    private fun resized(e: PageElement, handle: HandleKind, p: PPoint, page: PRect): PRect {
        val b = e.bounds
        val x = p.x.coerceIn(page.left, page.right)
        val y = p.y.coerceIn(page.top, page.bottom)
        val minSize = 8f
        return when (e.resize) {
            ResizeMode.FREE -> when (handle) {
                HandleKind.LT -> PRect(min(x, b.right - minSize), min(y, b.bottom - minSize), b.right, b.bottom)
                HandleKind.RT -> PRect(b.left, min(y, b.bottom - minSize), max(x, b.left + minSize), b.bottom)
                HandleKind.RB -> PRect(b.left, b.top, max(x, b.left + minSize), max(y, b.top + minSize))
                else -> PRect(min(x, b.right - minSize), b.top, b.right, max(y, b.top + minSize))
            }
            ResizeMode.ASPECT -> {
                val right = handle == HandleKind.RT || handle == HandleKind.RB
                val down = handle == HandleKind.RB || handle == HandleKind.LB
                val ax = if (right) b.left else b.right
                val ay = if (down) b.top else b.bottom
                val dx = if (right) x - ax else ax - x
                val dy = if (down) y - ay else ay - y
                val roomX = if (right) page.right - ax else ax - page.left
                val roomY = if (down) page.bottom - ay else ay - page.top
                val s = max(dx / b.width, dy / b.height)
                    .coerceAtMost(min(roomX / b.width, roomY / b.height))
                    .coerceAtLeast(minSize * 2 / max(b.width, b.height))
                val w = b.width * s
                val h = b.height * s
                PRect(
                    if (right) ax else ax - w, if (down) ay else ay - h,
                    if (right) ax + w else ax, if (down) ay + h else ay,
                )
            }
            ResizeMode.WIDTH -> when (handle) {
                HandleKind.L -> PRect(min(x, b.right - 24f), b.top, b.right, b.bottom)
                HandleKind.R -> PRect(b.left, b.top, max(x, b.left + 24f), b.bottom)
                HandleKind.T -> PRect(b.left, min(y, b.bottom - 24f), b.right, b.bottom)
                else -> PRect(b.left, b.top, b.right, max(y, b.top + 24f))
            }
            ResizeMode.NONE, ResizeMode.ENDS -> b
        }
    }

    // ---- Actions on the selected element ----

    fun deleteSelected() {
        val id = session.selection ?: return
        session.edit { it.removeElement(id) }
        session.selection = null
    }

    fun duplicateSelected() {
        val (slot, e) = selected() ?: return
        val shift = 14f
        val copy = e.withId(Ids.next()).let { it.withBounds(it.bounds.offset(shift, shift).movedInside(slot.page.bounds)) }
        session.edit { it.addElement(slot.page.key, copy) }
        session.selection = copy.id
    }

    companion object {
        /** Keep derived geometry right after an element changed (text boxes size themselves). */
        fun normalize(e: PageElement): PageElement = if (e is TextBox) TextLayout.fit(e) else e

        /** Keep access to a folder the user picked, so it still works after the app restarts. */
        fun keepFolderAccess(context: Context, uri: Uri) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }

        fun displayName(context: Context, uri: Uri): String? = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }
}
