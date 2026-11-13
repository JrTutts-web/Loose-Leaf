package io.github.jrtutts_web.looseleaf.core.pdf

import android.graphics.Matrix
import io.github.jrtutts_web.looseleaf.core.model.PRect
import io.github.jrtutts_web.looseleaf.core.model.PageEntry

/** Conversions between page space (see Geometry.kt), the screen, and PDF user space. */
object PageGeometry {

    /**
     * Matrix from page space to pixels: applies the page's added rotation, then
     * [scale] (pixels per point), then moves the page's top-left corner to ([tx], [ty]).
     * Used both to draw on screen and to tell the renderer where to put a page.
     */
    fun displayMatrix(page: PageEntry, scale: Float, tx: Float, ty: Float, out: Matrix = Matrix()): Matrix {
        out.reset()
        when (page.rotation) {
            90 -> { out.postRotate(90f); out.postTranslate(page.height, 0f) }
            180 -> { out.postRotate(180f); out.postTranslate(page.width, page.height) }
            270 -> { out.postRotate(270f); out.postTranslate(0f, page.width) }
        }
        out.postScale(scale, scale)
        out.postTranslate(tx, ty)
        return out
    }

    /**
     * PDF matrix (a b c d e f) from page space to the user space of a PDF page
     * whose /Rotate is [rotate] and whose crop box is ([llx], [lly], [w], [h]).
     */
    fun visualToUser(rotate: Int, llx: Float, lly: Float, w: Float, h: Float): FloatArray = when (rotate) {
        90 -> floatArrayOf(0f, 1f, 1f, 0f, llx, lly)
        180 -> floatArrayOf(-1f, 0f, 0f, 1f, llx + w, lly)
        270 -> floatArrayOf(0f, -1f, -1f, 0f, llx + w, lly + h)
        else -> floatArrayOf(1f, 0f, 0f, -1f, llx, lly + h)
    }

    /** The same mapping as an android Matrix, handy for inverting (user space to page space). */
    fun visualToUserMatrix(rotate: Int, llx: Float, lly: Float, w: Float, h: Float): Matrix {
        val m = visualToUser(rotate, llx, lly, w, h)
        return Matrix().apply {
            setValues(floatArrayOf(m[0], m[2], m[4], m[1], m[3], m[5], 0f, 0f, 1f))
        }
    }

    /**
     * PDF-style matrix (a b c d e f) from the page as shown with [rotation]
     * added, back to page space, for a page of [w] by [h] points. The inverse
     * of the rotation part of [displayMatrix].
     */
    fun shownToPage(rotation: Int, w: Float, h: Float): FloatArray = when (rotation) {
        90 -> floatArrayOf(0f, -1f, 1f, 0f, 0f, h)
        180 -> floatArrayOf(-1f, 0f, 0f, -1f, w, h)
        270 -> floatArrayOf(0f, 1f, -1f, 0f, w, 0f)
        else -> floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)
    }

    /** A rectangle given in the page as shown with [rotation] added, in page space. */
    fun shownRectToPage(r: PRect, rotation: Int, w: Float, h: Float): PRect {
        val f = shownToPage(rotation, w, h)
        val x1 = f[0] * r.left + f[2] * r.top + f[4]
        val y1 = f[1] * r.left + f[3] * r.top + f[5]
        val x2 = f[0] * r.right + f[2] * r.bottom + f[4]
        val y2 = f[1] * r.right + f[3] * r.bottom + f[5]
        return PRect(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
    }

    fun normalizeRotation(r: Int): Int = (((r % 360) + 360) % 360) / 90 * 90
}
