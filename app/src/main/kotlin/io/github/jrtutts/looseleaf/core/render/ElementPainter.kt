package io.github.jrtutts.looseleaf.core.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.LruCache
import io.github.jrtutts.looseleaf.core.model.Blend
import io.github.jrtutts.looseleaf.core.model.HiddenText
import io.github.jrtutts.looseleaf.core.model.ImageStamp
import io.github.jrtutts.looseleaf.core.model.InkStroke
import io.github.jrtutts.looseleaf.core.model.LineMark
import io.github.jrtutts.looseleaf.core.model.PPoint
import io.github.jrtutts.looseleaf.core.model.PageElement
import io.github.jrtutts.looseleaf.core.model.RectMark
import io.github.jrtutts.looseleaf.core.model.ShapeKind
import io.github.jrtutts.looseleaf.core.model.ShapeMark
import io.github.jrtutts.looseleaf.core.model.TextBox

/**
 * Draws model elements onto an android Canvas whose coordinate system has
 * already been set to page space. Call from the main thread only.
 */
object ElementPainter {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val multiply = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val image = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val rect = RectF()

    private val bitmaps = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun bitmap(path: String): Bitmap? =
        bitmaps.get(path) ?: BitmapFactory.decodeFile(path)?.also { bitmaps.put(path, it) }

    fun draw(c: Canvas, e: PageElement) {
        when (e) {
            is RectMark -> {
                val b = e.bounds
                fill.color = e.color
                fill.xfermode = if (e.blend == Blend.MULTIPLY) multiply else null
                c.drawRect(b.left, b.top, b.right, b.bottom, fill)
                fill.xfermode = null
            }
            is InkStroke -> {
                stroke.color = e.color
                stroke.strokeWidth = e.width
                inkPath(e.points, path)
                c.drawPath(path, stroke)
            }
            is TextBox -> withTurns(c, e) { iw, _ ->
                val lay = TextLayout.layout(e.text, e.fontSize, iw)
                val p = TextLayout.paint(e.fontSize).apply { color = e.color }
                lay.lines.forEachIndexed { i, line -> c.drawText(line, 0f, lay.baseline(i), p) }
            }
            is ImageStamp -> withTurns(c, e) { iw, ih ->
                val bmp = bitmap(e.imagePath) ?: return@withTurns
                rect.set(0f, 0f, iw, ih)
                c.drawBitmap(bmp, null, rect, image)
            }
            is LineMark -> {
                stroke.color = e.color
                stroke.strokeWidth = e.width
                path.rewind()
                for (seg in LineGeometry.segments(e)) {
                    path.moveTo(seg[0].x, seg[0].y)
                    for (i in 1 until seg.size) path.lineTo(seg[i].x, seg[i].y)
                }
                c.drawPath(path, stroke)
            }
            is ShapeMark -> withTurns(c, e) { iw, ih ->
                stroke.color = e.color
                stroke.strokeWidth = e.width
                when (e.kind) {
                    ShapeKind.RECT -> { stroke.strokeJoin = Paint.Join.MITER; c.drawRect(0f, 0f, iw, ih, stroke); stroke.strokeJoin = Paint.Join.ROUND }
                    ShapeKind.OVAL -> c.drawOval(0f, 0f, iw, ih, stroke)
                    else -> {
                        path.rewind()
                        for (s in ShapeMark.strokes(e.kind)) {
                            path.moveTo(s[0].x * iw, s[0].y * ih)
                            for (i in 1 until s.size) path.lineTo(s[i].x * iw, s[i].y * ih)
                        }
                        c.drawPath(path, stroke)
                    }
                }
            }
            is HiddenText -> Unit
        }
    }

    /** Runs [block] with the canvas origin at the element's own top-left, rotated by its quarter turns. */
    private inline fun withTurns(c: Canvas, e: PageElement, block: (innerW: Float, innerH: Float) -> Unit) {
        val b = e.bounds
        val odd = e.turns % 2 == 1
        val iw = if (odd) b.height else b.width
        val ih = if (odd) b.width else b.height
        c.save()
        c.translate(b.centerX, b.centerY)
        c.rotate(e.turns * 90f)
        c.translate(-iw / 2f, -ih / 2f)
        block(iw, ih)
        c.restore()
    }

    /** Smooth path through the points: quadratic curves between midpoints. */
    fun inkPath(points: List<PPoint>, out: Path) {
        out.rewind()
        if (points.isEmpty()) return
        out.moveTo(points[0].x, points[0].y)
        if (points.size == 1) { out.lineTo(points[0].x + 0.01f, points[0].y); return }
        for (i in 1 until points.size - 1) {
            val p = points[i]
            val n = points[i + 1]
            out.quadTo(p.x, p.y, (p.x + n.x) / 2f, (p.y + n.y) / 2f)
        }
        val last = points.last()
        out.lineTo(last.x, last.y)
    }
}
