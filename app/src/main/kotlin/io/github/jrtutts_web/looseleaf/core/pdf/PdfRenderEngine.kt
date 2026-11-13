package io.github.jrtutts_web.looseleaf.core.pdf

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors

/**
 * The rendering layer: turns pages of the session's source PDFs into pixels
 * using the platform PdfRenderer (no network, no extra native code). All work
 * happens on one background thread because PdfRenderer allows only one open
 * page at a time.
 */
class PdfRenderEngine : Closeable {
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()

    private class Doc(val pfd: ParcelFileDescriptor, val renderer: PdfRenderer)
    private val docs = HashMap<Int, Doc>()

    /**
     * Open [file] as source [id] and return its number of pages. Throws
     * SecurityException for password-protected files and IOException for
     * files that are not PDFs.
     */
    suspend fun open(id: Int, file: File): Int = withContext(dispatcher) {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try {
            PdfRenderer(pfd)
        } catch (t: Throwable) {
            runCatching { pfd.close() }
            throw t
        }
        docs.remove(id)?.let { old -> runCatching { old.renderer.close() }; runCatching { old.pfd.close() } }
        docs[id] = Doc(pfd, renderer)
        renderer.pageCount
    }

    /**
     * Open [file] as source [id] and return its page sizes as displayed, in
     * whole points. Only the platform renderer is used: it opens even long
     * documents in a moment, where parsing the file with pdfbox can take many
     * seconds on a phone. The sizes are rounded; the writer scales page space
     * onto the real page box when saving, so nothing ends up misplaced.
     */
    suspend fun openWithSizes(id: Int, file: File): List<Pair<Float, Float>> {
        val t0 = System.nanoTime()
        val count = open(id, file)
        val t1 = System.nanoTime()
        val sizes = withContext(dispatcher) {
            val renderer = docs[id]?.renderer ?: return@withContext emptyList()
            (0 until renderer.pageCount).map { i ->
                renderer.openPage(i).use { it.width.toFloat() to it.height.toFloat() }
            }
        }
        // One line per opened file, to see where the time goes on a slow phone (adb logcat -s LooseLeaf).
        Log.i(
            "LooseLeaf",
            "open: $count pages, ${file.length() / 1024} kB; renderer ${(t1 - t0) / 1_000_000} ms, " +
                "page sizes ${(System.nanoTime() - t1) / 1_000_000} ms",
        )
        return sizes
    }

    /**
     * Draw page [index] of source [source] into [bitmap]. [matrix] maps page
     * points to bitmap pixels; [clip] limits drawing to part of the bitmap.
     * Returns false if the page could not be rendered.
     */
    suspend fun render(source: Int, index: Int, bitmap: Bitmap, matrix: Matrix, clip: Rect? = null): Boolean =
        withContext(dispatcher) {
            val doc = docs[source] ?: return@withContext false
            try {
                doc.renderer.openPage(index).use {
                    it.render(bitmap, clip, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
                true
            } catch (t: Throwable) {
                false
            }
        }

    override fun close() {
        executor.execute {
            for (d in docs.values) {
                runCatching { d.renderer.close() }
                runCatching { d.pfd.close() }
            }
            docs.clear()
        }
        executor.shutdown()
    }
}
