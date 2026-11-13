package io.github.jrtutts_web.looseleaf.core.render

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import io.github.jrtutts_web.looseleaf.R
import io.github.jrtutts_web.looseleaf.core.model.PRect
import io.github.jrtutts_web.looseleaf.core.model.TextBox
import java.io.InputStream

/** The one font used for text boxes, on screen and embedded in saved files. */
object Fonts {
    lateinit var body: Typeface
        private set

    fun init(context: Context) {
        body = ResourcesCompat.getFont(context, R.font.noto_sans) ?: Typeface.SANS_SERIF
    }

    // Fonts in res/font can be read as raw streams; lint only knows res/raw.
    @android.annotation.SuppressLint("ResourceType")
    fun openBody(context: Context): InputStream = context.resources.openRawResource(R.font.noto_sans)
}

class LaidOutText(val lines: List<String>, val lineHeight: Float, private val ascent: Float) {
    val height get() = lines.size * lineHeight
    fun baseline(line: Int) = line * lineHeight + ascent
}

/**
 * Line breaking for text boxes. The screen and the PDF writer both use this,
 * so a saved file breaks lines exactly where the editor showed them.
 */
object TextLayout {
    fun paint(fontSize: Float) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
        typeface = Fonts.body
        textSize = fontSize
        hinting = Paint.HINTING_OFF
        // The PDF writer places glyphs by plain advance widths; match that here.
        fontFeatureSettings = "'kern' 0, 'liga' 0, 'clig' 0"
    }

    fun layout(text: String, fontSize: Float, maxWidth: Float): LaidOutText {
        val p = paint(fontSize)
        val fm = p.fontMetrics
        val lines = ArrayList<String>()
        for (para in text.replace("\t", "    ").split('\n')) {
            if (para.isEmpty()) { lines += ""; continue }
            var line = StringBuilder()
            for (word in para.split(' ')) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (p.measureText(candidate) <= maxWidth || maxWidth <= 0f) {
                    line = StringBuilder(candidate)
                    continue
                }
                if (line.isNotEmpty()) { lines += line.toString(); line = StringBuilder() }
                // The word alone may still be too wide: break it by characters.
                var rest = word
                while (rest.isNotEmpty() && p.measureText(rest) > maxWidth) {
                    val n = p.breakText(rest, true, maxWidth, null).coerceAtLeast(1)
                    lines += rest.substring(0, n)
                    rest = rest.substring(n)
                }
                line = StringBuilder(rest)
            }
            lines += line.toString()
        }
        return LaidOutText(lines, fm.descent - fm.ascent, -fm.ascent)
    }

    /** Width of the widest line if the text were never wrapped. */
    fun naturalWidth(text: String, fontSize: Float): Float {
        val p = paint(fontSize)
        return text.split('\n').maxOfOrNull { p.measureText(it.replace("\t", "    ")) } ?: 0f
    }

    /**
     * Make the box exactly as tall as its text needs (wide, for sideways
     * text), keeping its top-left corner where it is.
     */
    fun fit(box: TextBox): TextBox {
        val b = box.bounds
        return if (box.turns % 2 == 0) {
            val h = layout(box.text, box.fontSize, b.width).height
            box.copy(bounds = PRect(b.left, b.top, b.right, b.top + h))
        } else {
            val w = layout(box.text, box.fontSize, b.height).height
            box.copy(bounds = PRect(b.left, b.top, b.left + w, b.bottom))
        }
    }
}
