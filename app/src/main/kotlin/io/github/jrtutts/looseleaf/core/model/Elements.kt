package io.github.jrtutts.looseleaf.core.model

import java.util.concurrent.atomic.AtomicLong

/** How the selection handles of an element behave. */
enum class ResizeMode {
    /** Not selectable at all (for example an invisible text layer). */
    NONE,
    /** Four corner handles, width and height change freely. */
    FREE,
    /** Four corner handles, aspect ratio is kept. */
    ASPECT,
    /** Left and right handles only; the element works out its own height. */
    WIDTH,
    /** One handle on each end of a line. */
    ENDS,
}

enum class Blend { NORMAL, MULTIPLY }

/**
 * Something the user has put on a page. These few primitives are all the
 * renderer and the PDF writer know about; modules build their features out of
 * them and never draw to the screen or the file themselves.
 *
 * [owner] is the id of the module that created the element, so that the module
 * can offer its own options when the element is selected.
 */
sealed interface PageElement {
    val id: Long
    val owner: String
    val bounds: PRect
    val resize: ResizeMode
    /** Clockwise quarter turns of the content inside [bounds]. */
    val turns: Int get() = 0
    fun withBounds(r: PRect): PageElement
    fun withId(newId: Long): PageElement
}

/**
 * A filled rectangle: a highlight (MULTIPLY, translucent) or a white-out box
 * (NORMAL, opaque).
 *
 * With [erase] set the box is a redaction: the saved file must not contain
 * what is under it, so the writer saves that whole page as a picture with the
 * box burnt in (see PageRasterizer).
 */
data class RectMark(
    override val id: Long,
    override val owner: String,
    override val bounds: PRect,
    val color: Int,
    val blend: Blend,
    val erase: Boolean = false,
) : PageElement {
    override val resize get() = ResizeMode.FREE
    override fun withBounds(r: PRect) = copy(bounds = r)
    override fun withId(newId: Long) = copy(id = newId)
}

/** A freehand stroke. [width] is in points. */
data class InkStroke(
    override val id: Long,
    override val owner: String,
    val points: List<PPoint>,
    val color: Int,
    val width: Float,
) : PageElement {
    override val bounds: PRect = run {
        if (points.isEmpty()) return@run PRect(0f, 0f, 0f, 0f)
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (p in points) {
            if (p.x < l) l = p.x; if (p.x > r) r = p.x
            if (p.y < t) t = p.y; if (p.y > b) b = p.y
        }
        PRect(l, t, r, b)
    }
    override val resize get() = ResizeMode.FREE

    override fun withBounds(r: PRect): InkStroke {
        val old = bounds
        val sx = if (old.width > 0.01f) r.width / old.width else 1f
        val sy = if (old.height > 0.01f) r.height / old.height else 1f
        return copy(points = points.map {
            PPoint(r.left + (it.x - old.left) * sx, r.top + (it.y - old.top) * sy)
        })
    }
    override fun withId(newId: Long) = copy(id = newId)
}

/** A straight line from [start] to [end], optionally with an arrow head at [end]. [width] is in points. */
data class LineMark(
    override val id: Long,
    override val owner: String,
    val start: PPoint,
    val end: PPoint,
    val color: Int,
    val width: Float,
    val arrow: Boolean = false,
) : PageElement {
    override val bounds: PRect = PRect.ofPoints(start, end)
    override val resize get() = ResizeMode.ENDS

    /** Only used to move the line: the ends keep their place relative to the box. */
    override fun withBounds(r: PRect): LineMark {
        val old = bounds
        val sx = if (old.width > 0.01f) r.width / old.width else 1f
        val sy = if (old.height > 0.01f) r.height / old.height else 1f
        fun map(p: PPoint) = PPoint(r.left + (p.x - old.left) * sx, r.top + (p.y - old.top) * sy)
        return copy(start = map(start), end = map(end))
    }
    override fun withId(newId: Long) = copy(id = newId)
}

enum class ShapeKind { RECT, OVAL, CHECK, CROSS }

/** An outlined shape that fills its box. [width] is the line width in points. */
data class ShapeMark(
    override val id: Long,
    override val owner: String,
    override val bounds: PRect,
    val kind: ShapeKind,
    val color: Int,
    val width: Float,
    override val turns: Int = 0,
) : PageElement {
    override val resize get() = ResizeMode.FREE
    override fun withBounds(r: PRect) = copy(bounds = r)
    override fun withId(newId: Long) = copy(id = newId)

    companion object {
        /** The strokes of a check mark or cross in a unit square, shared by the screen and the PDF writer. */
        fun strokes(kind: ShapeKind): List<List<PPoint>> = when (kind) {
            ShapeKind.CHECK -> listOf(listOf(PPoint(0.04f, 0.56f), PPoint(0.36f, 0.90f), PPoint(0.96f, 0.10f)))
            ShapeKind.CROSS -> listOf(
                listOf(PPoint(0.08f, 0.08f), PPoint(0.92f, 0.92f)),
                listOf(PPoint(0.92f, 0.08f), PPoint(0.08f, 0.92f)),
            )
            else -> emptyList()
        }
    }
}

/**
 * A box of text set in the bundled font. [turns] is the number of clockwise
 * quarter turns of the text inside the box, used so that text added to a page
 * the user has rotated still reads upright.
 */
data class TextBox(
    override val id: Long,
    override val owner: String,
    override val bounds: PRect,
    val text: String,
    val fontSize: Float,
    val color: Int,
    override val turns: Int = 0,
) : PageElement {
    override val resize get() = ResizeMode.WIDTH
    override fun withBounds(r: PRect) = copy(bounds = r)
    override fun withId(newId: Long) = copy(id = newId)
}

/** A picture (PNG with transparency) stored in the session directory, such as a signature. */
data class ImageStamp(
    override val id: Long,
    override val owner: String,
    override val bounds: PRect,
    val imagePath: String,
    override val turns: Int = 0,
) : PageElement {
    override val resize get() = ResizeMode.ASPECT
    override fun withBounds(r: PRect) = copy(bounds = r)
    override fun withId(newId: Long) = copy(id = newId)
}

/**
 * Invisible, searchable text (for example from OCR). Each word is stretched to
 * fill its box. Word boxes are in the page as it was shown when the text was
 * recognised, that is page space turned clockwise by [frameRotation] degrees,
 * so that text on a page the user turned upright runs in the right direction.
 */
data class HiddenText(
    override val id: Long,
    override val owner: String,
    val words: List<Word>,
    val frameRotation: Int = 0,
) : PageElement {
    data class Word(val rect: PRect, val text: String)

    override val bounds get() = PRect(0f, 0f, 0f, 0f)
    override val resize get() = ResizeMode.NONE
    override fun withBounds(r: PRect) = this
    override fun withId(newId: Long) = copy(id = newId)
}

object Ids {
    private val next = AtomicLong(1)
    fun next(): Long = next.getAndIncrement()

    /** After loading a stored document: never hand out an id that is already in use. */
    fun ensureAbove(id: Long) { next.updateAndGet { if (it <= id) id + 1 else it } }
}
