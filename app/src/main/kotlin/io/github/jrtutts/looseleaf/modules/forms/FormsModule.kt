package io.github.jrtutts.looseleaf.modules.forms

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.jrtutts.looseleaf.R
import io.github.jrtutts.looseleaf.core.model.PageEntry
import io.github.jrtutts.looseleaf.core.module.EditorHost
import io.github.jrtutts.looseleaf.core.module.EditorModule
import io.github.jrtutts.looseleaf.core.module.PageHit
import io.github.jrtutts.looseleaf.core.module.Tool
import io.github.jrtutts.looseleaf.core.render.TextLayout
import io.github.jrtutts.looseleaf.core.ui.OptionHint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

/** What this module knows about the open document: its fields, grouped by source file and page. */
private class FormsState {
    var fields by mutableStateOf<List<FormField>>(emptyList())
    var scannedSources = 0
    var scanning = false

    fun on(page: PageEntry): List<Pair<FormField, FormWidget>> =
        fields.filter { it.key.source == page.source }
            .flatMap { f -> f.widgets.filter { it.pageIndex == page.index }.map { f to it } }
}

/** Fill in AcroForm fields. Values live in the document model and are written into the fields on save. */
object FormsModule : EditorModule {
    override val id = "forms"
    override val title = R.string.forms_title
    override val description = R.string.forms_description
    override val icon = Icons.Filled.EditNote

    private fun state(host: EditorHost) = host.session.extras.getOrPut(id) { FormsState() } as FormsState

    /** Look for fields in any source file not scanned yet (the opened PDF, and PDFs merged in later). */
    private fun scan(host: EditorHost, announce: Boolean) {
        val st = state(host)
        val sources = host.session.sources.toList()
        if (st.scanning || st.scannedSources >= sources.size) return
        st.scanning = true
        host.scope.launch {
            val found = withContext(Dispatchers.IO) {
                sources.drop(st.scannedSources).flatMap { s -> runCatching { FormReader.read(s.id, s.file) }.getOrDefault(emptyList()) }
            }
            st.fields = st.fields + found
            st.scannedSources = sources.size
            st.scanning = false
            if (announce && found.isNotEmpty()) {
                host.message(host.context.resources.getQuantityString(R.plurals.forms_found, found.size, found.size))
            }
        }
    }

    override fun onDocumentOpened(host: EditorHost) = scan(host, announce = true)

    private val tool = object : Tool {
        override val id = "forms.fill"
        override val label = R.string.forms_tool
        override val icon: ImageVector = Icons.Filled.EditNote

        override fun onActivated(host: EditorHost): Boolean {
            scan(host, announce = false)
            val st = state(host)
            if (!st.scanning && st.fields.isEmpty()) {
                host.message(host.context.getString(R.string.forms_none))
                return false
            }
            return true
        }

        override fun onTap(host: EditorHost, hit: PageHit) {
            val (field, widget) = state(host).on(hit.page)
                .filter { it.second.rect.contains(hit.point, 4f) }
                .minByOrNull { it.second.rect.width * it.second.rect.height } ?: return
            val current = host.session.model.formValues[field.key] ?: field.original
            fun set(value: String) = host.session.edit { it.copy(formValues = it.formValues + (field.key to value)) }
            when (field.kind) {
                FieldKind.CHECK -> set(if (current.isEmpty() || current == "Off") widget.onValue ?: "Yes" else "Off")
                FieldKind.RADIO -> set(if (current == widget.onValue) "Off" else widget.onValue ?: return)
                FieldKind.TEXT -> host.showDialog { dismiss -> TextFieldDialog(field, current, dismiss) { dismiss(); set(it) } }
                FieldKind.CHOICE -> host.showDialog { dismiss -> ChoiceDialog(field, current, dismiss) { dismiss(); set(it) } }
            }
        }

        @Composable
        override fun Options(host: EditorHost) = OptionHint(stringResource(R.string.forms_hint))
    }

    override val tools = listOf(tool)

    // ---- Drawing: show where fields are while filling, and show values the user has entered ----

    private val tint = Paint().apply { color = 0x332962FF }
    private val tintEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x992962FF.toInt(); style = Paint.Style.STROKE; strokeWidth = 0.7f }
    private val paper = Paint().apply { color = 0xFFFFFFFF.toInt() }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    override fun drawPageOverlay(canvas: Canvas, page: PageEntry, host: EditorHost) {
        val st = host.session.extras[id] as? FormsState ?: return
        val filling = host.activeTool === tool
        val values = host.session.model.formValues
        for ((field, widget) in st.on(page)) {
            val r = widget.rect
            val value = values[field.key]
            if (value != null) {
                // Cover what the renderer drew for this field and draw the new value instead.
                canvas.drawRect(r.left + 1f, r.top + 1f, r.right - 1f, r.bottom - 1f, paper)
                when (field.kind) {
                    FieldKind.CHECK -> if (value.isNotEmpty() && value != "Off") {
                        val s = min(r.width, r.height)
                        mark.strokeWidth = s * 0.12f
                        path.rewind()
                        path.moveTo(r.centerX - s * 0.28f, r.centerY)
                        path.lineTo(r.centerX - s * 0.08f, r.centerY + s * 0.22f)
                        path.lineTo(r.centerX + s * 0.3f, r.centerY - s * 0.25f)
                        canvas.drawPath(path, mark)
                    }
                    FieldKind.RADIO -> if (value == widget.onValue) {
                        canvas.drawCircle(r.centerX, r.centerY, min(r.width, r.height) * 0.25f, ink)
                    }
                    FieldKind.TEXT, FieldKind.CHOICE -> {
                        val shown = if (field.kind == FieldKind.CHOICE) field.options.firstOrNull { it.first == value }?.second ?: value else value
                        drawText(canvas, shown, field.multiline, widget)
                    }
                }
            }
            if (filling) {
                canvas.drawRect(r.left, r.top, r.right, r.bottom, tint)
                canvas.drawRect(r.left, r.top, r.right, r.bottom, tintEdge)
            }
        }
    }

    private fun drawText(canvas: Canvas, text: String, multiline: Boolean, widget: FormWidget) {
        val r = widget.rect
        canvas.save()
        canvas.clipRect(r.left, r.top, r.right, r.bottom)
        if (multiline) {
            val size = 10f
            val lay = TextLayout.layout(text, size, r.width - 4f)
            val p = TextLayout.paint(size)
            lay.lines.forEachIndexed { i, line -> canvas.drawText(line, r.left + 2f, r.top + 2f + lay.baseline(i), p) }
        } else {
            val size = (r.height * 0.62f).coerceIn(4f, 12f)
            val p = TextLayout.paint(size)
            val fm = p.fontMetrics
            canvas.drawText(text, r.left + 2f, r.centerY - (fm.ascent + fm.descent) / 2f, p)
        }
        canvas.restore()
    }

    @Composable
    private fun TextFieldDialog(field: FormField, current: String, onCancel: () -> Unit, onDone: (String) -> Unit) {
        var value by remember { mutableStateOf(TextFieldValue(current, TextRange(current.length))) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(field.label) },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = !field.multiline,
                    modifier = Modifier.fillMaxWidth().heightIn(min = if (field.multiline) 120.dp else 0.dp).focusRequester(focus),
                )
            },
            confirmButton = { TextButton(onClick = { onDone(value.text) }) { Text(stringResource(R.string.done)) } },
            dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
        )
    }

    @Composable
    private fun ChoiceDialog(field: FormField, current: String, onCancel: () -> Unit, onPick: (String) -> Unit) {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(field.label) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    for ((export, display) in field.options) {
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(export == current, role = Role.RadioButton) { onPick(export) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = export == current, onClick = null)
                            Text(display, Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
