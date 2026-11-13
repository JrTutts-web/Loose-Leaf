package io.github.jrtutts_web.looseleaf.modules.text

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.jrtutts_web.looseleaf.R
import io.github.jrtutts_web.looseleaf.core.model.Ids
import io.github.jrtutts_web.looseleaf.core.model.PRect
import io.github.jrtutts_web.looseleaf.core.model.PageElement
import io.github.jrtutts_web.looseleaf.core.model.PageEntry
import io.github.jrtutts_web.looseleaf.core.model.TextBox
import io.github.jrtutts_web.looseleaf.core.module.EditorHost
import io.github.jrtutts_web.looseleaf.core.module.EditorModule
import io.github.jrtutts_web.looseleaf.core.module.MenuAction
import io.github.jrtutts_web.looseleaf.core.module.PageHit
import io.github.jrtutts_web.looseleaf.core.module.Tool
import io.github.jrtutts_web.looseleaf.core.render.TextLayout
import io.github.jrtutts_web.looseleaf.core.ui.ColorDots
import io.github.jrtutts_web.looseleaf.core.ui.Gap
import io.github.jrtutts_web.looseleaf.core.ui.Palette
import io.github.jrtutts_web.looseleaf.core.ui.OptionHint
import io.github.jrtutts_web.looseleaf.core.ui.Stepper
import kotlin.math.roundToInt

/** Text boxes laid over the page. Existing text in the PDF is not editable. */
object TextModule : EditorModule {
    override val id = "text"
    override val title = R.string.text_title
    override val description = R.string.text_description
    override val icon = Icons.Filled.TextFields

    private val colors = Palette.ink
    private var color by mutableIntStateOf(colors[0].argb)
    private var fontSize by mutableFloatStateOf(12f)

    private val tool = object : Tool {
        override val id = "text.add"
        override val label = R.string.text_tool
        override val icon: ImageVector = Icons.Filled.TextFields

        override fun onTap(host: EditorHost, hit: PageHit) {
            host.showDialog { dismiss ->
                TextDialog(initial = "", onCancel = dismiss) { text ->
                    dismiss()
                    add(host, hit, text)
                }
            }
        }

        @Composable
        override fun Options(host: EditorHost) = OptionHint(stringResource(R.string.text_tap_hint))
    }

    override val tools = listOf(tool)

    private fun add(host: EditorHost, hit: PageHit, text: String) {
        if (text.isBlank()) return
        val page = hit.page
        val turns = page.uprightTurns
        // Lay the text out on one line if it fits between the tap and the far edge, else wrap.
        val room = (if (turns % 2 == 0) page.width else page.height) * 0.9f
        val along = (TextLayout.naturalWidth(text, fontSize) + 1f).coerceIn(24f, room)
        val across = TextLayout.layout(text, fontSize, along).height
        val x = hit.point.x
        val y = hit.point.y
        val bounds = (if (turns % 2 == 0) PRect(x, y - across / 2f, x + along, y + across / 2f)
        else PRect(x - across / 2f, y, x + across / 2f, y + along)).movedInside(page.bounds)
        val box = TextLayout.fit(TextBox(Ids.next(), id, bounds, text, fontSize, color, turns))
        host.session.edit { it.addElement(page.key, box) }
        host.activate(null)
        host.session.selection = box.id
    }

    private fun edit(host: EditorHost, element: PageElement) {
        val box = element as? TextBox ?: return
        host.showDialog { dismiss ->
            TextDialog(initial = box.text, onCancel = dismiss) { text ->
                dismiss()
                if (text.isBlank()) {
                    host.session.edit { it.removeElement(box.id) }
                } else {
                    host.session.edit { m -> m.updateElement(box.id) { TextLayout.fit((it as TextBox).copy(text = text)) } }
                }
            }
        }
    }

    override fun onSelectedElementTapped(host: EditorHost, element: PageElement) = edit(host, element)

    override fun elementActions(host: EditorHost, element: PageElement) =
        listOf(MenuAction(R.string.text_edit, Icons.Filled.Edit) { edit(it, element) })

    @Composable
    override fun SelectionOptions(host: EditorHost, page: PageEntry, element: PageElement) {
        val box = element as? TextBox ?: return
        fun resize(delta: Float) {
            val next = (box.fontSize + delta).coerceIn(6f, 96f)
            fontSize = next
            host.session.edit(box.id) { m -> m.updateElement(box.id) { TextLayout.fit((it as TextBox).copy(fontSize = next)) } }
        }
        IconButton(onClick = { edit(host, box) }) { Icon(Icons.Filled.Edit, stringResource(R.string.text_edit)) }
        val step = if (box.fontSize >= 24f) 2f else 1f
        Stepper(
            value = stringResource(R.string.text_size, box.fontSize.roundToInt()),
            onMinus = { resize(-step) },
            onPlus = { resize(step) },
            minusLabel = stringResource(R.string.text_smaller),
            plusLabel = stringResource(R.string.text_larger),
        )
        ColorDots(colors, box.color, { c ->
            color = c
            host.session.edit { m -> m.updateElement(box.id) { (it as TextBox).copy(color = c) } }
        })
        Gap()
    }

    @Composable
    private fun TextDialog(initial: String, onCancel: () -> Unit, onDone: (String) -> Unit) {
        var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(stringResource(if (initial.isEmpty()) R.string.text_add_title else R.string.text_edit)) },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).focusRequester(focus),
                    placeholder = { Text(stringResource(R.string.text_placeholder)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
            },
            confirmButton = { TextButton(onClick = { onDone(value.text.trimEnd()) }) { Text(stringResource(R.string.done)) } },
            dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
