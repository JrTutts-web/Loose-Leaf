package io.github.jrtutts.looseleaf.core.model

import kotlin.math.max
import kotlin.math.min

/**
 * Geometry in "page space": PDF points (1/72 inch), origin at the top-left of
 * the page as it is displayed before any rotation added in this app, x to the
 * right and y down. Everything in the document model uses these units.
 */
data class PPoint(val x: Float, val y: Float)

data class PRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2f
    val centerY get() = (top + bottom) / 2f

    fun contains(p: PPoint, slop: Float = 0f) =
        p.x >= left - slop && p.x <= right + slop && p.y >= top - slop && p.y <= bottom + slop

    fun offset(dx: Float, dy: Float) = PRect(left + dx, top + dy, right + dx, bottom + dy)

    /** Shift the rectangle (keeping its size) so that it lies inside [bounds] where possible. */
    fun movedInside(bounds: PRect): PRect {
        var dx = 0f
        var dy = 0f
        if (left < bounds.left) dx = bounds.left - left else if (right > bounds.right) dx = bounds.right - right
        if (top < bounds.top) dy = bounds.top - top else if (bottom > bounds.bottom) dy = bounds.bottom - bottom
        return offset(dx, dy)
    }

    companion object {
        fun ofPoints(a: PPoint, b: PPoint) =
            PRect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
    }
}
