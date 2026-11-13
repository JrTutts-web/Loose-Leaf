package io.github.jrtutts_web.looseleaf.core.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import io.github.jrtutts_web.looseleaf.core.model.DocumentModel
import io.github.jrtutts_web.looseleaf.core.model.HiddenText
import io.github.jrtutts_web.looseleaf.core.model.PRect
import io.github.jrtutts_web.looseleaf.core.model.PageElement
import io.github.jrtutts_web.looseleaf.core.model.PageEntry
import io.github.jrtutts_web.looseleaf.core.model.RectMark
import io.github.jrtutts_web.looseleaf.core.render.ElementPainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * A page turned into a picture. The first [burned] elements of the page are
 * part of the picture and must not be written to the PDF again.
 */
class PageRaster(val file: File, val burned: Int)

/**
 * Redaction. A page that carries a [RectMark] with `erase` set is saved as a
 * picture of itself with the box painted in, so whatever was under the box
 * (text, images, links, form fields) is not in the saved file at all. The
 * price is that the rest of that page stops being selectable text.
 */
object PageRasterizer {
    private const val DPI = 200f
    private const val MAX_SIDE = 3000f

    fun needsRaster(page: PageEntry) = page.elements.any { it is RectMark && it.erase }

    /** Pictures for every page of [model] that needs one, as JPEG files in [dir], keyed by page key. */
    suspend fun rasterize(model: DocumentModel, renderer: PdfRenderEngine, dir: File): Map<Long, PageRaster> {
        val out = LinkedHashMap<Long, PageRaster>()
        try {
            for (page in model.pages) {
                val last = page.elements.indexOfLast { it is RectMark && it.erase }
                if (last < 0) continue
                val scale = min(DPI / 72f, MAX_SIDE / max(page.width, page.height))
                val bmp = Bitmap.createBitmap(ceil(page.width * scale).toInt(), ceil(page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                try {
                    bmp.eraseColor(Color.WHITE)
                    // Page space, without the rotation added in the app: the writer applies that to the page itself.
                    if (!renderer.render(page.source, page.index, bmp, Matrix().apply { setScale(scale, scale) })) {
                        throw IOException("A page with a redaction could not be turned into a picture")
                    }
                    // Everything up to the last redaction box goes into the picture, so nothing under a box stays behind.
                    withContext(Dispatchers.Main.immediate) {
                        val c = Canvas(bmp)
                        c.scale(scale, scale)
                        for (i in 0..last) ElementPainter.draw(c, page.elements[i])
                    }
                    val file = File(dir, "raster-${page.key}-${System.nanoTime()}.jpg")
                    withContext(Dispatchers.IO) { file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
                    out[page.key] = PageRaster(file, last + 1)
                } finally {
                    bmp.recycle()
                }
            }
        } catch (t: Throwable) {
            out.values.forEach { it.file.delete() }
            throw t
        }
        return out
    }

    /**
     * The elements of a rasterised page that still have to be written to the
     * PDF: everything above the last redaction box, plus invisible text layers
     * from below it with the words under any box taken out.
     */
    fun remaining(page: PageEntry, burned: Int): List<PageElement> {
        val boxes = page.elements.filter { it is RectMark && it.erase }.map { it.bounds }
        val below = page.elements.take(burned).filterIsInstance<HiddenText>()
        return (below + page.elements.drop(burned)).map { e ->
            if (e is HiddenText) e.copy(words = e.words.filterNot { w -> boxes.any { overlaps(it, PageGeometry.shownRectToPage(w.rect, e.frameRotation, page.width, page.height)) } }) else e
        }
    }

    private fun overlaps(a: PRect, b: PRect) = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
}
