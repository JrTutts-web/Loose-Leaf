package io.github.jrtutts_web.looseleaf.modules.stamps

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import io.github.jrtutts_web.looseleaf.R
import io.github.jrtutts_web.looseleaf.core.model.Ids
import io.github.jrtutts_web.looseleaf.core.model.PRect
import io.github.jrtutts_web.looseleaf.core.model.PageElement
import io.github.jrtutts_web.looseleaf.core.model.PageEntry
import io.github.jrtutts_web.looseleaf.core.model.ShapeKind
import io.github.jrtutts_web.looseleaf.core.model.ShapeMark
import io.github.jrtutts_web.looseleaf.core.model.TextBox
import io.github.jrtutts_web.looseleaf.core.module.EditorHost
import io.github.jrtutts_web.looseleaf.core.module.EditorModule
import io.github.jrtutts_web.looseleaf.core.module.PageHit
import io.github.jrtutts_web.looseleaf.core.module.Tool
import io.github.jrtutts_web.looseleaf.core.render.TextLayout
import io.github.jrtutts_web.looseleaf.core.ui.ColorDots
import io.github.jrtutts_web.looseleaf.core.ui.Gap
import io.github.jrtutts_web.looseleaf.core.ui.IconChoice
import io.github.jrtutts_web.looseleaf.core.ui.OptionHint
import io.github.jrtutts_web.looseleaf.core.ui.Palette
import io.github.jrtutts_web.looseleaf.core.ui.Stepper
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Check marks, crosses and today's date, placed with one tap each: what is
 * needed to fill in a form that is only a picture of a form.
 */
object StampsModule : EditorModule {
    override val id = "stamps"
    override val title = R.string.stamps_title
    override val description = R.string.stamps_description
    override val icon = Icons.Filled.TaskAlt

    private enum class Kind(val label: Int, val icon: ImageVector) {
        CHECK(R.string.stamps_check, Icons.Filled.Check),
        CROSS(R.string.stamps_cross, Icons.Filled.Close),
        DATE(R.string.stamps_date, Icons.Filled.Event),
    }

    private val colors = Palette.ink
    private var kind by mutableStateOf(Kind.CHECK)
    private var color by mutableIntStateOf(colors[0].argb)
    private var markSize by mutableFloatStateOf(14f)
    private var dateSize by mutableFloatStateOf(11f)
    private var dateStyle by mutableIntStateOf(0)

    /** Today's date in one of three styles: as the phone's language writes it, short, or year-month-day. */
    private fun today(style: Int): String = when (style % 3) {
        0 -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date())
        1 -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date())
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
    }

    private val tool = object : Tool {
        override val id = "stamps.place"
        override val label = R.string.stamps_tool
        override val icon: ImageVector = Icons.Filled.TaskAlt

        // The tool stays active after a tap: forms usually need many marks in a row.
        override fun onTap(host: EditorHost, hit: PageHit) {
            val page = hit.page
            val turns = page.uprightTurns
            val x = hit.point.x
            val y = hit.point.y
            val element: PageElement = if (kind == Kind.DATE) {
                val text = today(dateStyle)
                val along = TextLayout.naturalWidth(text, dateSize) + 1f
                val across = TextLayout.layout(text, dateSize, along).height
                val bounds = if (turns % 2 == 0) PRect(x, y - across / 2f, x + along, y + across / 2f)
                else PRect(x - across / 2f, y, x + across / 2f, y + along)
                TextLayout.fit(TextBox(Ids.next(), StampsModule.id, bounds.movedInside(page.bounds), text, dateSize, color, turns))
            } else {
                val h = markSize / 2f
                ShapeMark(
                    Ids.next(), StampsModule.id, PRect(x - h, y - h, x + h, y + h).movedInside(page.bounds),
                    if (kind == Kind.CHECK) ShapeKind.CHECK else ShapeKind.CROSS, color, (markSize / 9f).coerceIn(1f, 4f), turns,
                )
            }
            host.session.edit { it.addElement(page.key, element) }
        }

        @Composable
        override fun Options(host: EditorHost) {
            Gap()
            for (k in Kind.entries) IconChoice(k.icon, stringResource(k.label), k == kind) { kind = k }
            Gap()
            if (kind == Kind.DATE) {
                // Tap the date to change how it is written.
                TextButton(onClick = { dateStyle = (dateStyle + 1) % 3 }) { Text(today(dateStyle)) }
                SizeStepper(dateSize) { dateSize = it }
            } else {
                SizeStepper(markSize) { markSize = it }
            }
            ColorDots(colors, color, { color = it })
            OptionHint(stringResource(R.string.stamps_hint))
        }
    }

    override val tools = listOf(tool)

    @Composable
    private fun SizeStepper(size: Float, onChange: (Float) -> Unit) {
        Stepper(
            value = stringResource(R.string.stamps_size, size.roundToInt()),
            onMinus = { onChange((size - 1f).coerceAtLeast(6f)) },
            onPlus = { onChange((size + 1f).coerceAtMost(48f)) },
            minusLabel = stringResource(R.string.stamps_smaller),
            plusLabel = stringResource(R.string.stamps_larger),
        )
    }

    @Composable
    override fun SelectionOptions(host: EditorHost, page: PageEntry, element: PageElement) {
        when (element) {
            is TextBox -> {
                SizeStepper(element.fontSize) { s ->
                    dateSize = s
                    host.session.edit(element.id) { m -> m.updateElement(element.id) { TextLayout.fit((it as TextBox).copy(fontSize = s)) } }
                }
                ColorDots(colors, element.color, { c ->
                    color = c
                    host.session.edit { m -> m.updateElement(element.id) { (it as TextBox).copy(color = c) } }
                })
            }
            is ShapeMark -> ColorDots(colors, element.color, { c ->
                color = c
                host.session.edit { m -> m.updateElement(element.id) { (it as ShapeMark).copy(color = c) } }
            })
            else -> Unit
        }
        Gap()
    }
}
