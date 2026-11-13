package io.github.jrtutts.looseleaf.core.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.jrtutts.looseleaf.core.model.PPoint
import io.github.jrtutts.looseleaf.core.model.PageEntry
import io.github.jrtutts.looseleaf.core.pdf.PageGeometry

/** Where one page sits in the scrolling column at zoom 1, in pixels. [scale] is pixels per point. */
class PageSlot(
    val index: Int,
    val page: PageEntry,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val scale: Float,
)

/** [signature] changes only when pages are added, removed, moved or rotated (not when elements change). */
class PageLayout(val slots: List<PageSlot>, val height: Float, val signature: List<Long>)

class PageBitmap(val bitmap: Bitmap, val rotation: Int, val forWidth: Int)

/** A sharp rendering of what was on screen at a given zoom and scroll position. */
class Patch(val bitmap: Bitmap, val zoom: Float, val scrollX: Float, val scrollY: Float, val signature: List<Long>)

/**
 * Scroll and zoom state of the page view. Pages are stacked in one column,
 * each fitted to the width of the view at zoom 1. A screen position is
 * `layoutPosition * zoom - scroll`.
 */
class ViewerState {
    var viewW by mutableIntStateOf(0); private set
    var viewH by mutableIntStateOf(0); private set
    var zoom by mutableFloatStateOf(1f); private set
    var scrollX by mutableFloatStateOf(0f); private set
    var scrollY by mutableFloatStateOf(0f); private set
    var layout by mutableStateOf(PageLayout(emptyList(), 0f, emptyList())); private set

    /** Space around pages in pixels; set from the density by the view. */
    var margin = 16f

    private val tmp = Matrix()
    private val pt = FloatArray(2)

    fun setViewSize(w: Int, h: Int) { viewW = w; viewH = h }

    fun relayout(pages: List<PageEntry>) {
        if (viewW == 0) return
        // Remember which page is at the top so that rotating the device or
        // changing pages does not lose the user's place.
        val old = layout
        val anchor = old.slots.firstOrNull { (it.top + it.height) * zoom > scrollY }
        val anchorFraction = anchor?.let { ((scrollY / zoom) - it.top) / it.height } ?: 0f

        val slots = ArrayList<PageSlot>(pages.size)
        val w = viewW - 2 * margin
        var y = margin
        pages.forEachIndexed { i, p ->
            val scale = w / p.shownWidth
            val h = p.shownHeight * scale
            slots += PageSlot(i, p, margin, y, w, h, scale)
            y += h + margin
        }
        layout = PageLayout(slots, y, pages.map { it.key * 4 + it.rotation / 90 })

        if (anchor != null) {
            val now = slots.firstOrNull { it.page.key == anchor.page.key } ?: slots.getOrNull(anchor.index.coerceAtMost(slots.size - 1))
            if (now != null) scrollY = (now.top + anchorFraction * now.height) * zoom
        }
        clamp()
    }

    fun screenLeft(s: PageSlot) = s.left * zoom - scrollX
    fun screenTop(s: PageSlot) = s.top * zoom - scrollY
    fun pixelsPerPoint(s: PageSlot) = s.scale * zoom

    /** Page space to screen pixels for [s]. */
    fun matrix(s: PageSlot, out: Matrix = Matrix()): Matrix =
        PageGeometry.displayMatrix(s.page, pixelsPerPoint(s), screenLeft(s), screenTop(s), out)

    fun toPage(s: PageSlot, x: Float, y: Float): PPoint {
        matrix(s, tmp).invert(tmp)
        pt[0] = x; pt[1] = y
        tmp.mapPoints(pt)
        return PPoint(pt[0], pt[1])
    }

    fun firstVisibleIndex(): Int {
        val slots = layout.slots
        var lo = 0
        var hi = slots.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if ((slots[mid].top + slots[mid].height) * zoom <= scrollY) lo = mid + 1 else hi = mid
        }
        return lo
    }

    fun lastVisibleIndex(): Int {
        val slots = layout.slots
        var i = firstVisibleIndex()
        while (i + 1 < slots.size && slots[i + 1].top * zoom < scrollY + viewH) i++
        return i
    }

    fun visibleSlots(): List<PageSlot> {
        val slots = layout.slots
        if (slots.isEmpty()) return emptyList()
        return slots.subList(firstVisibleIndex(), lastVisibleIndex() + 1)
    }

    /** The page under a screen point, or null if the point is between or beside pages. */
    fun slotAt(x: Float, y: Float): PageSlot? = visibleSlots().firstOrNull {
        val l = screenLeft(it); val t = screenTop(it)
        x >= l && x <= l + it.width * zoom && y >= t && y <= t + it.height * zoom
    }

    /** The page whose middle is closest to the middle of the view. */
    fun centerSlot(): PageSlot? = visibleSlots().minByOrNull {
        kotlin.math.abs(screenTop(it) + it.height * zoom / 2f - viewH / 2f)
    }

    fun scrollBy(dx: Float, dy: Float) {
        scrollX += dx
        scrollY += dy
        clamp()
    }

    /** Multiply the zoom by [factor], keeping the content under screen point ([cx], [cy]) in place. */
    fun zoomBy(factor: Float, cx: Float, cy: Float) = zoomTo(zoom * factor, cx, cy)

    fun zoomTo(target: Float, cx: Float, cy: Float) {
        val next = target.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val k = next / zoom
        scrollX = (scrollX + cx) * k - cx
        scrollY = (scrollY + cy) * k - cy
        zoom = next
        clamp()
    }

    fun scrollToPage(index: Int) {
        val s = layout.slots.getOrNull(index) ?: return
        scrollY = (s.top - margin) * zoom
        clamp()
    }

    /** Scroll so that the middle of [rect] on page [index] is in the middle of the view. */
    fun scrollTo(index: Int, rect: io.github.jrtutts.looseleaf.core.model.PRect) {
        val s = layout.slots.getOrNull(index) ?: return
        matrix(s, tmp)
        pt[0] = rect.centerX; pt[1] = rect.centerY
        tmp.mapPoints(pt)
        scrollBy(pt[0] - viewW / 2f, pt[1] - viewH / 2f)
    }

    private fun clamp() {
        scrollX = scrollX.coerceIn(0f, (viewW * zoom - viewW).coerceAtLeast(0f))
        scrollY = scrollY.coerceIn(0f, (layout.height * zoom - viewH).coerceAtLeast(0f))
    }

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 8f
    }
}
