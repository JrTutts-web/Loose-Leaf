package io.github.jrtutts.looseleaf.core.module

import android.content.Context
import android.graphics.Canvas
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.tom_roush.pdfbox.pdmodel.PDDocument
import io.github.jrtutts.looseleaf.core.model.DocumentModel
import io.github.jrtutts.looseleaf.core.model.EditorSession
import io.github.jrtutts.looseleaf.core.model.PPoint
import io.github.jrtutts.looseleaf.core.model.PRect
import io.github.jrtutts.looseleaf.core.model.PageElement
import io.github.jrtutts.looseleaf.core.model.PageEntry
import io.github.jrtutts.looseleaf.core.pdf.PdfRenderEngine
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * A feature that can be switched on and off in Settings. A module contributes
 * toolbar tools and menu entries and changes the document only through
 * [EditorHost.session]. Modules must not reference each other; the full list
 * lives in modules/ModuleRegistry.kt and nowhere else.
 */
interface EditorModule {
    /** Stable id, also used as the settings key and as [PageElement.owner]. */
    val id: String
    @get:StringRes val title: Int
    @get:StringRes val description: Int
    val icon: ImageVector
    val enabledByDefault: Boolean get() = true

    /** Buttons for the bottom toolbar of the editor. */
    val tools: List<Tool> get() = emptyList()

    /** Entries for the editor's overflow menu. */
    val menuActions: List<MenuAction> get() = emptyList()

    /** Entries on the home screen, each opening a screen of the module's own (a library of saved files, say). */
    val homeActions: List<HomeAction> get() = emptyList()

    /**
     * The user asked to save the document. A module may take over (to offer its
     * own destination) by returning true; it then finishes the job with
     * [EditorHost.saveDocumentTo] or [EditorHost.saveDocumentWithPicker] and
     * passes [done] along. The first module that returns true wins; if none
     * does, the system file picker is used.
     */
    fun onSave(host: EditorHost, done: () -> Unit): Boolean = false

    /** Called once after a document has been opened (and again if the module is switched on later). */
    fun onDocumentOpened(host: EditorHost) {}

    /**
     * Draw something extra on a page, on top of its elements. The canvas is in
     * page space. Read Compose state here and the page redraws when it changes.
     */
    fun drawPageOverlay(canvas: Canvas, page: PageEntry, host: EditorHost) {}

    /** Controls shown while an element this module owns is selected. */
    @Composable
    fun SelectionOptions(host: EditorHost, page: PageEntry, element: PageElement) {}

    /** Extra entries for the long-press menu of an element this module owns. */
    fun elementActions(host: EditorHost, element: PageElement): List<MenuAction> = emptyList()

    /** The user tapped an element of this module that was already selected. */
    fun onSelectedElementTapped(host: EditorHost, element: PageElement) {}
}

/** A point on a page, in page space. */
data class PageHit(val page: PageEntry, val pageIndex: Int, val point: PPoint)

/** A toolbar button. While a tool is active, taps and (if [dragDraws]) one-finger drags on pages go to it. */
interface Tool {
    val id: String
    @get:StringRes val label: Int
    val icon: ImageVector

    /** True if a one-finger drag draws with this tool instead of scrolling. Two fingers always pan and zoom. */
    val dragDraws: Boolean get() = false

    /** The button was tapped. Return false if the tool did its work right away and should not stay active. */
    fun onActivated(host: EditorHost): Boolean = true

    fun onTap(host: EditorHost, hit: PageHit) {}
    fun onDragStart(host: EditorHost, hit: PageHit) {}
    /** [point] is on the page the drag started on, clamped to its edges. */
    fun onDrag(host: EditorHost, point: PPoint) {}
    fun onDragEnd(host: EditorHost) {}
    fun onDragCancel(host: EditorHost) {}

    /** A row of options (colours, sizes, hints) shown above the toolbar while the tool is active. */
    @Composable
    fun Options(host: EditorHost) {}
}

/** A button on the home screen. [label] may depend on the module's own settings. */
class HomeAction(val label: (Context) -> String, val icon: ImageVector, val screen: @Composable (AppHost) -> Unit)

/** What the app offers to a module screen opened from the home screen, where no document is open. */
interface AppHost {
    val context: Context
    /** Open the PDF at [uri] in the editor. The module screen stays underneath and is back when the editor closes. */
    fun open(uri: Uri)
    /** Let the user choose a folder with the system picker. The app keeps access to it across restarts. */
    fun pickFolder(onPicked: (Uri) -> Unit)
    /** Leave the module screen. */
    fun close()
}

class MenuAction(@StringRes val label: Int, val icon: ImageVector, val run: (EditorHost) -> Unit)

/** What the editor offers to modules. */
interface EditorHost {
    val context: Context
    val session: EditorSession
    val scope: CoroutineScope
    val renderer: PdfRenderEngine

    val activeTool: Tool?
    fun activate(tool: Tool?)

    /** Show a short message at the bottom of the screen. */
    fun message(text: String)

    /** Show module UI over the whole editor until [closeScreen] or Back. */
    fun showScreen(content: @Composable () -> Unit)
    fun closeScreen()

    /** Host a dialog. The content calls `dismiss` when it is done. */
    fun showDialog(content: @Composable (dismiss: () -> Unit) -> Unit)

    /** The page in the middle of the screen and the point of it that is there. */
    fun viewCenter(): PageHit?
    fun scrollToPage(index: Int)

    /** Bring [rect] of page [pageIndex] to the middle of the screen. */
    fun scrollTo(pageIndex: Int, rect: PRect)

    /** Run [block] behind a blocking progress indicator. Call `progress` to change its text. */
    suspend fun <T> busy(message: String, block: suspend (progress: (String) -> Unit) -> T): T

    /** Let the user choose a PDF with the system file picker. */
    fun pickPdf(onPicked: (Uri) -> Unit)

    /** Let the user choose any file of [mimeType] (may be a wildcard for "any file"). */
    fun pickFile(mimeType: String, onPicked: (Uri) -> Unit)

    /**
     * Let the user choose where to save a new file, then produce it with
     * [write]. Refuses to write over the file that was opened.
     */
    fun saveCopy(suggestedName: String, onSaved: () -> Unit = {}, write: suspend (File) -> Unit)

    /** Save the document being edited through the system file picker, as "Save a copy" does by default. */
    fun saveDocumentWithPicker(onSaved: () -> Unit = {})

    /**
     * Save the document being edited to a place the module provides. [create]
     * must make a new, empty file and return its address; it is only called
     * once the PDF has been produced, so a failed save leaves nothing behind.
     */
    fun saveDocumentTo(create: suspend () -> Uri, onSaved: () -> Unit = {})

    /** Let the user choose a folder with the system picker. The app keeps access to it across restarts. */
    fun pickFolder(onPicked: (Uri) -> Unit)

    /** Write [model] (by default the current document) to [out] as a PDF. */
    suspend fun export(out: File, model: DocumentModel = session.model, postProcess: ((PDDocument) -> Unit)? = null)

    /** Copy another PDF into the session and return its pages, ready to be added to the model. */
    suspend fun importPdf(uri: Uri): List<PageEntry>?
}
