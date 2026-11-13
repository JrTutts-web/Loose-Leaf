package io.github.jrtutts.looseleaf.modules.signatures

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import java.io.File
import kotlin.math.ceil
import kotlin.math.max

/**
 * Saved signatures: transparent PNG files in the app's private storage
 * (files/signatures). They never leave the device and are excluded from backups.
 */
class SignatureStore(context: Context) {
    private val dir = File(context.filesDir, "signatures").apply { mkdirs() }

    fun list(): List<File> = dir.listFiles { f -> f.extension == "png" }?.sortedByDescending { it.lastModified() }.orEmpty()

    fun save(bitmap: Bitmap): File {
        val file = File(dir, "sig-${System.currentTimeMillis()}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    fun delete(file: File) { file.delete() }

    companion object {
        /** Render strokes drawn on the pad (in pad pixels) tightly cropped, about [targetWidth] pixels wide. */
        fun renderStrokes(strokes: List<List<Offset>>, color: Int, strokeWidth: Float, targetWidth: Int = 1000): Bitmap? {
            val pts = strokes.flatten()
            if (pts.isEmpty()) return null
            val l = pts.minOf { it.x }; val t = pts.minOf { it.y }
            val w = max(pts.maxOf { it.x } - l, 1f)
            val h = max(pts.maxOf { it.y } - t, 1f)
            val scale = (targetWidth / w).coerceAtMost(600f / h)
            val pad = strokeWidth * scale
            val bmp = Bitmap.createBitmap(ceil(w * scale + 2 * pad).toInt(), ceil(h * scale + 2 * pad).toInt(), Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                this.color = color
                this.strokeWidth = strokeWidth * scale
            }
            val canvas = Canvas(bmp)
            for (s in strokes) {
                if (s.isEmpty()) continue
                val path = Path()
                fun x(o: Offset) = (o.x - l) * scale + pad
                fun y(o: Offset) = (o.y - t) * scale + pad
                path.moveTo(x(s[0]), y(s[0]))
                if (s.size == 1) path.lineTo(x(s[0]) + 0.1f, y(s[0]))
                for (i in 1 until s.size - 1) {
                    path.quadTo(x(s[i]), y(s[i]), (x(s[i]) + x(s[i + 1])) / 2f, (y(s[i]) + y(s[i + 1])) / 2f)
                }
                if (s.size > 1) path.lineTo(x(s.last()), y(s.last()))
                canvas.drawPath(path, paint)
            }
            return bmp
        }

        /** Render a typed name in a handwriting font, tightly cropped. */
        fun renderText(text: String, typeface: Typeface, color: Int): Bitmap? {
            if (text.isBlank()) return null
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.typeface = typeface
                this.color = color
                textSize = 180f
            }
            val box = Rect()
            paint.getTextBounds(text, 0, text.length, box)
            if (box.width() <= 0 || box.height() <= 0) return null
            val pad = 12
            val bmp = Bitmap.createBitmap(box.width() + 2 * pad, box.height() + 2 * pad, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawText(text, (pad - box.left).toFloat(), (pad - box.top).toFloat(), paint)
            return bmp
        }
    }
}
