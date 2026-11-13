package io.github.jrtutts_web.looseleaf.modules.find

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import io.github.jrtutts_web.looseleaf.R
import io.github.jrtutts_web.looseleaf.core.model.HiddenText
import io.github.jrtutts_web.looseleaf.core.model.PRect
import io.github.jrtutts_web.looseleaf.core.model.PageEntry
import io.github.jrtutts_web.looseleaf.core.model.RectMark
import io.github.jrtutts_web.looseleaf.core.model.TextBox
import io.github.jrtutts_web.looseleaf.core.module.EditorHost
import io.github.jrtutts_web.looseleaf.core.module.EditorModule
import io.github.jrtutts_web.looseleaf.core.module.MenuAction
import io.github.jrtutts_web.looseleaf.core.module.Tool
import io.github.jrtutts_web.looseleaf.core.pdf.PageGeometry
import io.github.jrtutts_web.looseleaf.core.pdf.PdfFiles
import io.github.jrtutts_web.looseleaf.core.ui.Gap
import io.github.jrtutts_web.looseleaf.core.ui.OptionHint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Find text in the document and jump to a page number. Searches the text of
 * the PDF itself, text boxes added in the app and invisible text layers (from
 * OCR), and marks every match on the page.
 */
object FindModule : EditorModule {
    override val id = "find"
    override val title = R.string.find_title
    override val description = R.string.find_description
    override val icon = Icons.Filled.Search

    /** The text of one page, lower-cased, with the place on the page of every character (null for added spaces). */
    private class PageText(val text: String, val rects: List<PRect?>)

    private class Match(val pageKey: Long, val rects: List<PRect>)

    private class State {
        /** Text of the source PDFs by (source id, page index); read once per source. */
        val sourceText = HashMap<Pair<Int, Int>, PageText>()
        val indexed = HashSet<Int>()
        var query by mutableStateOf("")
        var searched = ""
        var matches by mutableStateOf<List<Match>>(emptyList())
        var current by mutableIntStateOf(-1)
    }

    private fun state(host: EditorHost) = host.session.extras.getOrPut(id) { State() } as State

    // ---- Reading the text ----

    private class Collector : PDFTextStripper() {
        private val sb = StringBuilder()
        private val rects = ArrayList<PRect?>()

        override fun writeString(text: String, textPositions: List<TextPosition>) {
            for (p in textPositions) {
                val u = p.unicode ?: continue
                // x and y are the start of the glyph's baseline, measured from the top-left of the page as displayed.
                val h = max(p.height, p.fontSizeInPt * 0.5f)
                val r = PRect(p.x, p.y - h * 1.15f, p.x + p.width, p.y + h * 0.35f)
                for (ch in u) { sb.append(Character.toLowerCase(ch)); rects.add(r) }
            }
        }

        override fun writeWordSeparator() = gap()
        override fun writeLineSeparator() = gap()
        private fun gap() { sb.append(' '); rects.add(null) }

        fun result() = PageText(sb.toString(), rects)
    }

    private fun read(file: File): List<PageText> = PdfFiles.load(file).use { doc ->
        (1..doc.numberOfPages).map { n ->
            runCatching {
                Collector().apply { startPage = n; endPage = n; getText(doc) }.result()
            }.getOrDefault(PageText("", emptyList()))
        }
    }

    private suspend fun ensureIndexed(host: EditorHost, st: State) {
        val needed = host.session.model.pages.map { it.source }.distinct().filter { it !in st.indexed }
        if (needed.isEmpty()) return
        val files = host.session.sources.toList()
        host.busy(host.context.getString(R.string.find_reading)) {
            val read = withContext(Dispatchers.IO) {
                needed.associateWith { id -> runCatching { read(files[id].file) }.getOrDefault(emptyList()) }
            }
            for ((id, pages) in read) {
                pages.forEachIndexed { i, t -> st.sourceText[id to i] = t }
                st.indexed += id
            }
        }
    }

    /** Text that exists only in the model: text boxes and invisible text layers. */
    private fun elementTexts(page: PageEntry): List<PageText> = page.elements.mapNotNull { e ->
        when (e) {
            is TextBox -> PageText(e.text.lowercaseChars(), List(e.text.length) { e.bounds })
            is HiddenText -> {
                val sb = StringBuilder()
                val rects = ArrayList<PRect?>()
                for (w in e.words) {
                    val r = PageGeometry.shownRectToPage(w.rect, e.frameRotation, page.width, page.height)
                    for (ch in w.text) { sb.append(Character.toLowerCase(ch)); rects.add(r) }
                    sb.append(' '); rects.add(null)
                }
                PageText(sb.toString(), rects)
            }
            else -> null
        }
    }

    // Character by character, so the result is exactly as long as the input and positions stay in step.
    private fun String.lowercaseChars() = String(CharArray(length) { Character.toLowerCase(this[it]) })

    // ---- Searching ----

    private fun search(host: EditorHost, st: State) {
        val q = st.query.trim().lowercaseChars()
        val found = ArrayList<Match>()
        if (q.isNotEmpty()) {
            for (page in host.session.model.pages) {
                // Text under a redaction box is as good as gone already; do not find it.
                val hidden = page.elements.filter { it is RectMark && it.erase }.map { it.bounds }
                val texts = listOfNotNull(st.sourceText[page.source to page.index]) + elementTexts(page)
                for (t in texts) {
                    var i = t.text.indexOf(q)
                    while (i >= 0) {
                        val rects = lines(t.rects.subList(i, i + q.length).filterNotNull())
                        val covered = rects.any { r -> hidden.any { it.left < r.right && r.left < it.right && it.top < r.bottom && r.top < it.bottom } }
                        if (rects.isNotEmpty() && !covered) found += Match(page.key, rects)
                        i = t.text.indexOf(q, i + 1)
                    }
                }
            }
        }
        st.matches = found
        val newQuery = q != st.searched
        st.searched = q
        if (found.isEmpty()) { st.current = -1; return }
        if (newQuery || st.current !in found.indices) {
            // Start with the first match on or after the page being looked at.
            val keys = host.session.model.pages.map { it.key }
            val here = host.viewCenter()?.pageIndex ?: 0
            st.current = found.indexOfFirst { keys.indexOf(it.pageKey) >= here }.coerceAtLeast(0)
            if (newQuery) show(host, st)
        }
    }

    /** One rectangle per line of text covered by the characters at [rects]. */
    private fun lines(rects: List<PRect>): List<PRect> {
        val out = ArrayList<PRect>()
        for (r in rects) {
            val last = out.lastOrNull()
            if (last != null && r.top < last.bottom && last.top < r.bottom) {
                out[out.size - 1] = PRect(min(last.left, r.left), min(last.top, r.top), max(last.right, r.right), max(last.bottom, r.bottom))
            } else {
                out += r
            }
        }
        return out
    }

    private fun show(host: EditorHost, st: State) {
        val m = st.matches.getOrNull(st.current) ?: return
        val index = host.session.model.pages.indexOfFirst { it.key == m.pageKey }
        if (index >= 0) host.scrollTo(index, m.rects[0])
    }

    private fun step(host: EditorHost, st: State, by: Int) {
        val n = st.matches.size
        if (n == 0) return
        st.current = ((st.current + by) % n + n) % n
        show(host, st)
    }

    // ---- Marks on the page ----

    private val mark = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY) }

    override fun drawPageOverlay(canvas: Canvas, page: PageEntry, host: EditorHost) {
        if (host.activeTool !== tool) return
        val st = host.session.extras[id] as? State ?: return
        val current = st.current
        st.matches.forEachIndexed { i, m ->
            if (m.pageKey != page.key) return@forEachIndexed
            mark.color = if (i == current) 0xFFFF9A3C.toInt() else 0xFFFFE066.toInt()
            for (r in m.rects) canvas.drawRect(r.left, r.top, r.right, r.bottom, mark)
        }
    }

    // ---- UI ----

    private val tool = object : Tool {
        override val id = "find.text"
        override val label = R.string.find_tool
        override val icon: ImageVector = Icons.Filled.Search

        @Composable
        override fun Options(host: EditorHost) {
            val st = state(host)
            val focus = remember { FocusRequester() }
            val keyboard = LocalSoftwareKeyboardController.current
            LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
            LaunchedEffect(host.session.model, st.query) {
                ensureIndexed(host, st)
                search(host, st)
            }
            Gap()
            OutlinedTextField(
                value = st.query,
                onValueChange = { st.query = it },
                modifier = Modifier.width(190.dp).padding(vertical = 4.dp).focusRequester(focus),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.find_placeholder)) },
                trailingIcon = {
                    if (st.query.isNotEmpty()) {
                        IconButton(onClick = { st.query = "" }) { Icon(Icons.Filled.Clear, stringResource(R.string.find_clear)) }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { step(host, st, 1) }),
            )
            val n = st.matches.size
            IconButton(onClick = { step(host, st, -1) }, enabled = n > 0) {
                Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.find_previous))
            }
            IconButton(onClick = { step(host, st, 1) }, enabled = n > 0) {
                Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.find_next))
            }
            OptionHint(
                when {
                    st.query.isBlank() -> stringResource(R.string.find_hint)
                    n == 0 -> stringResource(R.string.find_none)
                    else -> stringResource(R.string.find_count, st.current + 1, n)
                },
            )
        }
    }

    override val tools = listOf(tool)

    override val menuActions = listOf(
        MenuAction(R.string.find_goto, Icons.Filled.Pin) { host ->
            host.showDialog { dismiss -> GoToDialog(host.session.model.pages.size, onCancel = dismiss) { dismiss(); host.scrollToPage(it - 1) } }
        },
    )

    @Composable
    private fun GoToDialog(pages: Int, onCancel: () -> Unit, onGo: (Int) -> Unit) {
        var value by remember { mutableStateOf("") }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        val number = value.toIntOrNull()?.takeIf { it in 1..pages }
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(stringResource(R.string.find_goto)) },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { v -> value = v.filter { it.isDigit() }.take(6) },
                    modifier = Modifier.focusRequester(focus),
                    singleLine = true,
                    label = { Text(stringResource(R.string.find_goto_label, pages)) },
                    isError = value.isNotEmpty() && number == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { number?.let(onGo) }),
                )
            },
            confirmButton = { TextButton(onClick = { number?.let(onGo) }, enabled = number != null) { Text(stringResource(R.string.find_go)) } },
            dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
