package io.github.jrtutts.looseleaf.core.model

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/** A PDF file copied into the session directory. Pages of the model refer to these by [id]. */
data class SourceFile(val id: Int, val file: File)

/**
 * The state of one editing session: the working copies of the source PDFs, the
 * current [DocumentModel], undo history and the current selection. The original
 * file is never touched; [sourceUri] is kept only to refuse saving over it.
 */
class EditorSession(
    val dir: File,
    val displayName: String,
    val sourceUri: Uri?,
    sources: List<SourceFile>,
    initial: DocumentModel,
    /** True when [initial] already contains edits that were never saved (a restored session). */
    restoredDirty: Boolean = false,
) {
    private val _sources = sources.toMutableList()
    val sources: List<SourceFile> get() = _sources

    var model by mutableStateOf(initial)
        private set
    private var savedModel by mutableStateOf(if (restoredDirty) null else initial)
    private val undoStack = ArrayDeque<DocumentModel>()
    private val redoStack = ArrayDeque<DocumentModel>()
    private var lastToken: Any? = null

    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set

    /** Id of the selected element, if any. */
    var selection by mutableStateOf<Long?>(null)

    /** An element being drawn right now (page key to element); shown but not yet part of the model. */
    var preview by mutableStateOf<Pair<Long, PageElement>?>(null)

    /** Per-module scratch state that should live as long as the session. */
    val extras = mutableMapOf<String, Any>()

    val isDirty get() = model !== savedModel

    val baseName get() = displayName.removeSuffix(".pdf").removeSuffix(".PDF")

    /**
     * Apply an edit. Edits that pass the same non-null [token] one after another
     * (every step of one drag, say) collapse into a single undo step.
     */
    fun edit(token: Any? = null, f: (DocumentModel) -> DocumentModel) {
        val next = f(model)
        if (next == model) return
        if (token == null || token !== lastToken) {
            undoStack.addLast(model)
            if (undoStack.size > 100) undoStack.removeFirst()
        }
        lastToken = token
        redoStack.clear()
        model = next
        refresh()
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(model)
        model = prev
        lastToken = null
        refresh()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(model)
        model = next
        lastToken = null
        refresh()
    }

    fun markSaved() { savedModel = model }

    fun addSource(file: File): SourceFile {
        val s = SourceFile(_sources.size, file)
        _sources += s
        return s
    }

    fun nextSourceFile() = File(dir, "src${_sources.size}.pdf")

    private fun refresh() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
        val sel = selection
        if (sel != null && model.findElement(sel) == null) selection = null
        preview = null
    }
}
