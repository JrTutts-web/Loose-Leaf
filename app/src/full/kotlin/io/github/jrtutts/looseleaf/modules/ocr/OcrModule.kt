package io.github.jrtutts.looseleaf.modules.ocr

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import io.github.jrtutts.looseleaf.R
import io.github.jrtutts.looseleaf.core.model.HiddenText
import io.github.jrtutts.looseleaf.core.model.Ids
import io.github.jrtutts.looseleaf.core.model.PRect
import io.github.jrtutts.looseleaf.core.model.PageEntry
import io.github.jrtutts.looseleaf.core.module.EditorHost
import io.github.jrtutts.looseleaf.core.module.EditorModule
import io.github.jrtutts.looseleaf.core.module.MenuAction
import io.github.jrtutts.looseleaf.core.pdf.PageGeometry
import io.github.jrtutts.looseleaf.core.pdf.PdfFiles
import io.github.jrtutts.looseleaf.core.ui.EditorController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Offline OCR: recognises the text on scanned pages and adds it to the model
 * as an invisible text layer, so the saved copy can be searched and its text
 * selected and copied. Only present in the "full" build flavour.
 */
object OcrModule : EditorModule {
    override val id = "ocr"
    override val title = R.string.ocr_title
    override val description = R.string.ocr_description
    override val icon = Icons.Filled.DocumentScanner

    override val menuActions = listOf(
        MenuAction(R.string.ocr_menu, Icons.Filled.DocumentScanner) { host -> host.showScreen { OcrScreen(host) } },
    )

    /** Pixels along the longer side of the image handed to the engine (about 250 dpi for A4). */
    private const val LONG_SIDE = 2800

    private class Outcome(val layers: Map<Long, HiddenText>, val skipped: Int)

    /** Recognise [pages]; [onPage] is told how many are done. Runs off the main thread, can be cancelled between pages. */
    private suspend fun recognise(
        host: EditorHost, pages: List<PageEntry>, languages: List<String>, skipTextPages: Boolean, onPage: (Int) -> Unit,
    ): Outcome {
        val layers = LinkedHashMap<Long, HiddenText>()
        var skipped = 0
        withContext(Dispatchers.Default) {
            val docs = HashMap<Int, PDDocument?>()
            val engine = OcrEngine(host.context, languages)
            try {
                pages.forEachIndexed { n, page ->
                    ensureActive()
                    onPage(n)
                    if (skipTextPages && hasText(host, docs, page)) { skipped++; return@forEachIndexed }
                    // Recognise the page the way the user sees it (their rotation applied), so sideways scans work.
                    val scale = LONG_SIDE / max(page.shownWidth, page.shownHeight)
                    val bmp = Bitmap.createBitmap(
                        (page.shownWidth * scale).roundToInt(), (page.shownHeight * scale).roundToInt(), Bitmap.Config.ARGB_8888,
                    )
                    bmp.eraseColor(Color.WHITE)
                    val m: Matrix = PageGeometry.displayMatrix(page, scale, 0f, 0f)
                    if (!host.renderer.render(page.source, page.index, bmp, m)) return@forEachIndexed
                    val words = engine.recognize(bmp).map {
                        HiddenText.Word(PRect(it.box.left / scale, it.box.top / scale, it.box.right / scale, it.box.bottom / scale), it.text)
                    }
                    bmp.recycle()
                    if (words.isNotEmpty()) layers[page.key] = HiddenText(Ids.next(), id, words, page.rotation)
                }
            } finally {
                engine.close()
                docs.values.forEach { runCatching { it?.close() } }
            }
        }
        return Outcome(layers, skipped)
    }

    /** True if the page already carries real text (a digital PDF, or a layer from an earlier run). */
    private fun hasText(host: EditorHost, docs: HashMap<Int, PDDocument?>, page: PageEntry): Boolean {
        if (page.elements.any { it is HiddenText }) return true
        val doc = docs.getOrPut(page.source) { runCatching { PdfFiles.load(host.session.sources[page.source].file) }.getOrNull() }
            ?: return false
        return runCatching {
            val stripper = PDFTextStripper()
            stripper.startPage = page.index + 1
            stripper.endPage = page.index + 1
            stripper.getText(doc).count { !it.isWhitespace() } >= 12
        }.getOrDefault(false)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun OcrScreen(host: EditorHost) {
        val context = host.context
        val current = remember { host.viewCenter() }
        var allPages by remember { mutableStateOf(true) }
        var skipTextPages by remember { mutableStateOf(true) }
        var job by remember { mutableStateOf<Job?>(null) }
        var done by remember { mutableIntStateOf(0) }
        var total by remember { mutableIntStateOf(0) }
        DisposableEffect(Unit) { onDispose { job?.cancel() } }
        var installed by remember { mutableStateOf(OcrLanguages.installed(context)) }
        var chosen by remember { mutableStateOf(OcrLanguages.selected(context)) }

        fun choose(code: String, on: Boolean) {
            val next = installed.filter { if (it == code) on else it in chosen }
            if (next.isEmpty()) return   // at least one language stays on
            chosen = next
            OcrLanguages.select(context, next)
        }

        fun addLanguage() = host.pickFile("*/*") { uri ->
            host.scope.launch {
                val name = EditorController.displayName(context, uri) ?: uri.lastPathSegment.orEmpty()
                try {
                    val code = host.busy(context.getString(R.string.ocr_lang_adding)) {
                        withContext(Dispatchers.IO) { OcrLanguages.add(context, uri, name) }
                    }
                    installed = OcrLanguages.installed(context)
                    choose(code, true)
                    host.message(context.getString(R.string.ocr_lang_added, OcrLanguages.displayName(code)))
                } catch (e: LanguageFileException) {
                    host.message(context.getString(e.reason))
                }
            }
        }

        fun removeLanguage(code: String) {
            OcrLanguages.remove(context, code)
            installed = OcrLanguages.installed(context)
            chosen = OcrLanguages.selected(context)
        }

        fun start() {
            val model = host.session.model
            val pages = if (allPages) model.pages else listOfNotNull(current?.let { model.page(it.page.key) })
            total = pages.size
            done = 0
            job = host.scope.launch {
                try {
                    val outcome = recognise(host, pages, chosen, skipTextPages) { done = it }
                    if (outcome.layers.isNotEmpty()) {
                        host.session.edit { m ->
                            m.copy(pages = m.pages.map { p ->
                                val layer = outcome.layers[p.key] ?: return@map p
                                p.copy(elements = p.elements.filterNot { it is HiddenText } + layer)
                            })
                        }
                    }
                    host.message(
                        when {
                            outcome.layers.isNotEmpty() ->
                                context.resources.getQuantityString(R.plurals.ocr_done, outcome.layers.size, outcome.layers.size)
                            outcome.skipped > 0 -> context.getString(R.string.ocr_all_skipped)
                            else -> context.getString(R.string.ocr_nothing)
                        },
                    )
                    host.closeScreen()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    host.message(context.getString(R.string.ocr_failed, e.message ?: e.javaClass.simpleName))
                } finally {
                    job = null
                }
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.ocr_menu)) },
                    navigationIcon = {
                        IconButton(onClick = { host.closeScreen() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(stringResource(R.string.ocr_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                val running = job != null
                if (!running) {
                    Choice(allPages, pluralStringResource(R.plurals.ocr_all_pages, host.session.model.pages.size, host.session.model.pages.size)) { allPages = true }
                    if (current != null) {
                        Choice(!allPages, stringResource(R.string.ocr_this_page, current.pageIndex + 1)) { allPages = false }
                    }
                    Row(
                        Modifier.fillMaxWidth().toggleable(skipTextPages, role = Role.Checkbox) { skipTextPages = it }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = skipTextPages, onCheckedChange = null)
                        Text(stringResource(R.string.ocr_skip_text), Modifier.padding(start = 16.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.ocr_languages), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    for (code in installed) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Row(
                                Modifier.weight(1f).toggleable(code in chosen, role = Role.Checkbox) { choose(code, it) }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = code in chosen, onCheckedChange = null)
                                Text(OcrLanguages.displayName(code), Modifier.padding(start = 16.dp))
                            }
                            if (code != OcrLanguages.BUNDLED) {
                                IconButton(onClick = { removeLanguage(code) }) {
                                    Icon(Icons.Filled.Delete, stringResource(R.string.ocr_lang_remove, OcrLanguages.displayName(code)))
                                }
                            }
                        }
                    }
                    TextButton(onClick = { addLanguage() }) {
                        Icon(Icons.Filled.Add, null)
                        Text(stringResource(R.string.ocr_lang_add), Modifier.padding(start = 8.dp))
                    }
                    Text(
                        stringResource(R.string.ocr_lang_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { start() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.ocr_start)) }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.ocr_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(stringResource(R.string.ocr_progress, (done + 1).coerceAtMost(total), total), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { if (total == 0) 0f else done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = { job?.cancel() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }

    @Composable
    private fun Choice(selected: Boolean, label: String, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Text(label, Modifier.padding(start = 16.dp))
        }
    }
}
