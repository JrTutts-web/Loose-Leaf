package io.github.jrtutts.looseleaf.modules.compress

import android.graphics.Bitmap
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Shrinks a PDF by re-encoding its pictures: each image is scaled down so
 * that its longer side is at most [maxSide] pixels and stored as JPEG at
 * [quality] (0..1). An image is only replaced if the result is really smaller.
 * Text and vector graphics are untouched and stay sharp.
 */
class ImageRecompressor(private val doc: PDDocument, private val maxSide: Int, private val quality: Float) {
    private val visited = IdentityHashMap<COSBase, Boolean>()
    private val results = IdentityHashMap<COSBase, PDImageXObject?>()
    var replaced = 0
        private set

    fun run() {
        for (page in doc.pages) page.resources?.let { walk(it) }
    }

    private fun walk(res: PDResources) {
        if (visited.put(res.cosObject, true) != null) return
        for (name in res.xObjectNames.toList()) {
            val x = try { res.getXObject(name) } catch (t: Throwable) { null } ?: continue
            when (x) {
                is PDImageXObject -> {
                    val key = x.cosObject
                    if (!results.containsKey(key)) results[key] = recompress(x)
                    results[key]?.let { res.put(name, it) }
                }
                is PDFormXObject -> x.resources?.let { walk(it) }
            }
        }
    }

    private fun recompress(img: PDImageXObject): PDImageXObject? = try {
        val w = img.width
        val h = img.height
        val old = img.cosObject
        when {
            // Line art and fax-style scans are already tiny and would get worse as JPEG.
            img.isStencil || img.bitsPerComponent == 1 -> null
            w * h < 128 * 128 -> null
            // Colour-key masking cannot survive JPEG.
            old.containsKey(COSName.MASK) -> null
            else -> {
                val scale = (maxSide.toFloat() / max(w, h)).coerceAtMost(1f)
                val full = img.opaqueImage
                val small = if (scale < 1f) {
                    Bitmap.createScaledBitmap(full, (w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1), true)
                } else full
                val jpeg = JPEGFactory.createFromImage(doc, small, quality)
                if (jpeg.cosObject.length >= old.length * 0.9) {
                    null
                } else {
                    // Keep transparency: the soft mask stretches to fit the smaller image.
                    old.getItem(COSName.SMASK)?.let { jpeg.cosObject.setItem(COSName.SMASK, it) }
                    replaced++
                    jpeg
                }
            }
        }
    } catch (t: Throwable) {
        null   // unsupported encoding or out of memory: leave this image as it is
    }
}
