package io.github.jrtutts_web.looseleaf.core.render

import io.github.jrtutts_web.looseleaf.core.model.LineMark
import io.github.jrtutts_web.looseleaf.core.model.PPoint
import kotlin.math.hypot
import kotlin.math.max

/** The polylines that make up a line or arrow. The screen and the PDF writer both draw exactly these. */
object LineGeometry {
    fun segments(e: LineMark): List<List<PPoint>> {
        val shaft = listOf(e.start, e.end)
        if (!e.arrow) return listOf(shaft)
        val dx = e.end.x - e.start.x
        val dy = e.end.y - e.start.y
        val len = hypot(dx, dy)
        if (len < 0.01f) return listOf(shaft)
        val ux = dx / len
        val uy = dy / len
        // Head length grows with the line width but never swallows a short arrow.
        val head = max(6f, e.width * 4f).coerceAtMost(len * 0.6f)
        val half = head * 0.5f
        val bx = e.end.x - ux * head
        val by = e.end.y - uy * head
        return listOf(
            shaft,
            listOf(PPoint(bx - uy * half, by + ux * half), e.end, PPoint(bx + uy * half, by - ux * half)),
        )
    }
}
