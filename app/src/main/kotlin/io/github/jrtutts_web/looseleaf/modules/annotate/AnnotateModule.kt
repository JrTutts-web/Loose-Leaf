package io.github.jrtutts_web.looseleaf.modules.annotate

import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Rectangle
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.jrtutts_web.looseleaf.R
import io.github.jrtutts_web.looseleaf.core.model.Blend
import io.github.jrtutts_web.looseleaf.core.model.Ids
import io.github.jrtutts_web.looseleaf.core.model.InkStroke
import io.github.jrtutts_web.looseleaf.core.model.LineMark
import io.github.jrtutts_web.looseleaf.core.model.PPoint
import io.github.jrtutts_web.looseleaf.core.model.PRect
import io.github.jrtutts_web.looseleaf.core.model.PageElement
import io.github.jrtutts_web.looseleaf.core.model.PageEntry
import io.github.jrtutts_web.looseleaf.core.model.RectMark
import io.github.jrtutts_web.looseleaf.core.model.ShapeKind
import io.github.jrtutts_web.looseleaf.core.model.ShapeMark
import io.github.jrtutts_web.looseleaf.core.module.EditorHost
import io.github.jrtutts_web.looseleaf.core.module.EditorModule
import io.github.jrtutts_web.looseleaf.core.module.PageHit
import io.github.jrtutts_web.looseleaf.core.module.Tool
import io.github.jrtutts_web.looseleaf.core.ui.ColorDots
import io.github.jrtutts_web.looseleaf.core.ui.Gap
import io.github.jrtutts_web.looseleaf.core.ui.IconChoice
import io.github.jrtutts_web.looseleaf.core.ui.NamedColor
import io.github.jrtutts_web.looseleaf.core.ui.OptionHint
import io.github.jrtutts_web.looseleaf.core.ui.Palette
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Highlighter, freehand pen and white-out boxes. */
object AnnotateModule : EditorModule {
    override val id = "annotate"
    override val title = R.string.annotate_title
    override val description = R.string.annotate_description
    override val icon = Icons.Filled.BorderColor

    // Light, saturated colours: highlights are multiplied onto the page like a real marker.
    private val highlightColors = listOf(
        NamedColor(0xFFFFEE58.toInt(), R.string.color_yellow), NamedColor(0xFFA8F0A0.toInt(), R.string.color_green),
        NamedColor(0xFFFFA6C9.toInt(), R.string.color_pink), NamedColor(0xFF9AD8FF.toInt(), R.string.color_blue),
        NamedColor(0xFFFFC56B.toInt(), R.string.color_orange),
    )
    private val inkColors = Palette.ink

    private var highlightColor by mutableIntStateOf(highlightColors[0].argb)
    private var inkColor by mutableIntStateOf(inkColors[0].argb)
    private var inkWidth by mutableFloatStateOf(2f)

    private enum class Shape(val label: Int, val icon: ImageVector) {
        LINE(R.string.annotate_shape_line, Icons.Filled.HorizontalRule),
        ARROW(R.string.annotate_shape_arrow, Icons.Filled.NorthEast),
        RECT(R.string.annotate_shape_rect, Icons.Filled.CropSquare),
        OVAL(R.string.annotate_shape_oval, Icons.Filled.RadioButtonUnchecked),
    }
    private var shape by mutableStateOf(Shape.ARROW)
    private var shapeColor by mutableIntStateOf(inkColors[2].argb)
    private var shapeWidth by mutableFloatStateOf(2f)

    /** A tool that drags out a rectangle. */
    private abstract class RectTool : Tool {
        override val dragDraws get() = true
        private var start = PPoint(0f, 0f)
        private var pageKey = 0L

        abstract fun make(rect: PRect): RectMark

        override fun onDragStart(host: EditorHost, hit: PageHit) {
            start = hit.point
            pageKey = hit.page.key
        }

        override fun onDrag(host: EditorHost, point: PPoint) {
            host.session.preview = pageKey to make(PRect.ofPoints(start, point))
        }

        override fun onDragEnd(host: EditorHost) {
            val mark = host.session.preview?.second
            host.session.preview = null
            if (mark != null && mark.bounds.width > 2f && mark.bounds.height > 2f) {
                host.session.edit { it.addElement(pageKey, mark) }
            }
        }

        override fun onDragCancel(host: EditorHost) { host.session.preview = null }
    }

    private val highlight = object : RectTool() {
        override val id = "annotate.highlight"
        override val label = R.string.annotate_highlight
        override val icon: ImageVector = Icons.Filled.BorderColor
        override fun make(rect: PRect) = RectMark(Ids.next(), AnnotateModule.id, rect, highlightColor, Blend.MULTIPLY)

        @Composable
        override fun Options(host: EditorHost) {
            Gap()
            ColorDots(highlightColors, highlightColor, { highlightColor = it })
            OptionHint(stringResource(R.string.annotate_highlight_hint))
        }
    }

    private val whiteout = object : RectTool() {
        override val id = "annotate.whiteout"
        override val label = R.string.annotate_whiteout
        override val icon: ImageVector = Icons.Filled.Rectangle
        override fun make(rect: PRect) = RectMark(Ids.next(), AnnotateModule.id, rect, 0xFFFFFFFF.toInt(), Blend.NORMAL)

        @Composable
        override fun Options(host: EditorHost) = OptionHint(stringResource(R.string.annotate_whiteout_hint))
    }

    private val ink = object : Tool {
        override val id = "annotate.ink"
        override val label = R.string.annotate_ink
        override val icon: ImageVector = Icons.Filled.Gesture
        override val dragDraws get() = true
        private val points = ArrayList<PPoint>()
        private var pageKey = 0L

        private fun stroke() = InkStroke(Ids.next(), AnnotateModule.id, points.toList(), inkColor, inkWidth)

        override fun onDragStart(host: EditorHost, hit: PageHit) {
            points.clear()
            points += hit.point
            pageKey = hit.page.key
        }

        override fun onDrag(host: EditorHost, point: PPoint) {
            val last = points.last()
            if (hypot(point.x - last.x, point.y - last.y) < 0.4f) return
            points += point
            host.session.preview = pageKey to stroke()
        }

        override fun onDragEnd(host: EditorHost) {
            host.session.preview = null
            if (points.isNotEmpty()) {
                val done = stroke()
                host.session.edit { it.addElement(pageKey, done) }
            }
            points.clear()
        }

        override fun onDragCancel(host: EditorHost) {
            host.session.preview = null
            points.clear()
        }

        // A tap leaves a dot, so that "i"s can be dotted and boxes ticked.
        override fun onTap(host: EditorHost, hit: PageHit) {
            val dot = InkStroke(Ids.next(), AnnotateModule.id, listOf(hit.point), inkColor, inkWidth)
            host.session.edit { it.addElement(hit.page.key, dot) }
        }

        @Composable
        override fun Options(host: EditorHost) {
            Gap()
            ColorDots(inkColors, inkColor, { inkColor = it })
            WidthSlider(inkWidth) { inkWidth = it }
            OptionHint(stringResource(R.string.annotate_two_fingers))
        }
    }

    /** Lines, arrows, rectangles and ovals: drag from one corner (or end) to the other. */
    private val shapes = object : Tool {
        override val id = "annotate.shapes"
        override val label = R.string.annotate_shapes
        override val icon: ImageVector = Icons.Filled.Category
        override val dragDraws get() = true
        private var start = PPoint(0f, 0f)
        private var pageKey = 0L

        private fun make(end: PPoint): PageElement = when (shape) {
            Shape.LINE, Shape.ARROW -> LineMark(Ids.next(), AnnotateModule.id, start, end, shapeColor, shapeWidth, arrow = shape == Shape.ARROW)
            Shape.RECT, Shape.OVAL -> ShapeMark(
                Ids.next(), AnnotateModule.id, PRect.ofPoints(start, end),
                if (shape == Shape.RECT) ShapeKind.RECT else ShapeKind.OVAL, shapeColor, shapeWidth,
            )
        }

        override fun onDragStart(host: EditorHost, hit: PageHit) {
            start = hit.point
            pageKey = hit.page.key
        }

        override fun onDrag(host: EditorHost, point: PPoint) {
            host.session.preview = pageKey to make(point)
        }

        override fun onDragEnd(host: EditorHost) {
            val e = host.session.preview?.second
            host.session.preview = null
            val b = e?.bounds ?: return
            val bigEnough = if (e is LineMark) hypot(b.width, b.height) > 4f else b.width > 3f && b.height > 3f
            if (bigEnough) host.session.edit { it.addElement(pageKey, e) }
        }

        override fun onDragCancel(host: EditorHost) { host.session.preview = null }

        @Composable
        override fun Options(host: EditorHost) {
            Gap()
            for (s in Shape.entries) IconChoice(s.icon, stringResource(s.label), s == shape) { shape = s }
            Gap()
            ColorDots(inkColors, shapeColor, { shapeColor = it })
            WidthSlider(shapeWidth) { shapeWidth = it }
            OptionHint(stringResource(R.string.annotate_two_fingers))
        }
    }

    override val tools = listOf(highlight, ink, shapes, whiteout)

    @Composable
    private fun WidthSlider(width: Float, onChange: (Float) -> Unit) {
        Gap()
        Text(stringResource(R.string.annotate_width, width.roundToInt()))
        Gap()
        Slider(
            value = width,
            onValueChange = { onChange(it.roundToInt().toFloat()) },
            valueRange = 1f..12f,
            modifier = Modifier.width(140.dp),
        )
    }

    @Composable
    override fun SelectionOptions(host: EditorHost, page: PageEntry, element: PageElement) {
        when {
            element is RectMark && element.blend == Blend.MULTIPLY ->
                ColorDots(highlightColors, element.color, { c ->
                    highlightColor = c
                    host.session.edit { it.updateElement(element.id) { e -> (e as RectMark).copy(color = c) } }
                })
            element is RectMark -> OptionHint(stringResource(R.string.annotate_whiteout_selected))
            element is LineMark -> {
                ColorDots(inkColors, element.color, { c ->
                    shapeColor = c
                    host.session.edit { it.updateElement(element.id) { e -> (e as LineMark).copy(color = c) } }
                })
                WidthSlider(element.width) { w ->
                    shapeWidth = w
                    host.session.edit(element.id) { it.updateElement(element.id) { e -> (e as LineMark).copy(width = w) } }
                }
            }
            element is ShapeMark -> {
                ColorDots(inkColors, element.color, { c ->
                    shapeColor = c
                    host.session.edit { it.updateElement(element.id) { e -> (e as ShapeMark).copy(color = c) } }
                })
                WidthSlider(element.width) { w ->
                    shapeWidth = w
                    host.session.edit(element.id) { it.updateElement(element.id) { e -> (e as ShapeMark).copy(width = w) } }
                }
            }
            element is InkStroke -> {
                ColorDots(inkColors, element.color, { c ->
                    inkColor = c
                    host.session.edit { it.updateElement(element.id) { e -> (e as InkStroke).copy(color = c) } }
                })
                WidthSlider(element.width) { w ->
                    inkWidth = w
                    host.session.edit(element.id) { it.updateElement(element.id) { e -> (e as InkStroke).copy(width = w) } }
                }
            }
        }
    }
}
