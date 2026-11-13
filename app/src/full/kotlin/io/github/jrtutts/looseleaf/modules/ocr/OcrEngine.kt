package io.github.jrtutts.looseleaf.modules.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.Closeable
import java.io.File
import java.io.IOException

class OcrWord(val box: Rect, val text: String)

/**
 * Offline text recognition with Tesseract. The English data ships inside the
 * APK (assets/tessdata) and is unpacked to private storage on first use;
 * other languages are files the user added (see OcrLanguages). Nothing is
 * downloaded. Not thread-safe: use from one thread.
 */
class OcrEngine(context: Context, languages: List<String> = listOf(OcrLanguages.BUNDLED)) : Closeable {
    private val api = TessBaseAPI()

    init {
        val root = OcrLanguages.root(context)
        val data = File(root, "tessdata/${OcrLanguages.BUNDLED}.traineddata")
        val asset = "tessdata/${OcrLanguages.BUNDLED}.traineddata"
        // available() is the full length for an asset stream; a size change means the app shipped newer data.
        val wanted = context.assets.open(asset).use { it.available().toLong() }
        if (!data.exists() || data.length() != wanted) {
            data.parentFile?.mkdirs()
            context.assets.open(asset).use { input -> data.outputStream().use { input.copyTo(it) } }
        }
        if (!api.init(root.absolutePath, languages.joinToString("+"))) {
            api.recycle()
            throw IOException("OCR engine could not start")
        }
        api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
    }

    /** Words found in [bitmap], with boxes in bitmap pixels. */
    fun recognize(bitmap: Bitmap): List<OcrWord> {
        api.setImage(bitmap)
        api.getUTF8Text()   // runs the recognition
        val out = ArrayList<OcrWord>()
        val it = api.resultIterator ?: return out
        try {
            it.begin()
            val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
            do {
                val text = it.getUTF8Text(level)?.trim().orEmpty()
                if (text.isNotEmpty() && it.confidence(level) >= 35f) out += OcrWord(it.getBoundingRect(level), text)
            } while (it.next(level))
        } finally {
            it.delete()
            api.clear()
        }
        return out
    }

    override fun close() = api.recycle()
}
