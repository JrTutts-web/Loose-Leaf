package io.github.jrtutts_web.looseleaf.core.pdf

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import io.github.jrtutts_web.looseleaf.core.model.Blend
import io.github.jrtutts_web.looseleaf.core.model.HiddenText
import io.github.jrtutts_web.looseleaf.core.model.ImageStamp
import io.github.jrtutts_web.looseleaf.core.model.InkStroke
import io.github.jrtutts_web.looseleaf.core.model.LineMark
import io.github.jrtutts_web.looseleaf.core.model.PageElement
import io.github.jrtutts_web.looseleaf.core.model.RectMark
import io.github.jrtutts_web.looseleaf.core.model.ShapeKind
import io.github.jrtutts_web.looseleaf.core.model.ShapeMark
import io.github.jrtutts_web.looseleaf.core.model.TextBox
import io.github.jrtutts_web.looseleaf.core.render.Fonts
import io.github.jrtutts_web.looseleaf.core.render.LineGeometry
import io.github.jrtutts_web.looseleaf.core.render.TextLayout

/** Things shared by all pages of one output document: the embedded font and placed images. */
internal class WriterResources(private val context: Context, private val doc: PDDocument) {
    val font: PDType0Font by lazy {
        Fonts.openBody(context).use { PDType0Font.load(doc, it, true) }
    }

    private val images = HashMap<String, PDImageXObject?>()
    fun image(path: String): PDImageXObject? = images.getOrPut(path) {
        BitmapFactory.decodeFile(path)?.let { LosslessFactory.createFromImage(doc, it) }
    }

    private val glyphs = HashMap<Int, Boolean>()

    /** Replace characters the embedded font has no glyph for (emoji, CJK...) with [replacement]. */
    fun clean(text: String, replacement: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val ok = glyphs.getOrPut(cp) {
                !Character.isISOControl(cp) &&
                    runCatching { font.encode(String(Character.toChars(cp))) }.isSuccess
            }
            if (ok) sb.appendCodePoint(cp) else sb.append(replacement)
        }
        return sb.toString()
    }
}

/** Flattens page elements into a page's content stream. The PDF twin of ElementPainter. */
internal object ElementWriter {

    /**
     * [pageW] and [pageH] are the size the model has for this page. It comes
     * from the screen renderer in whole points, so page space is stretched (by
     * a fraction of a percent at most) onto the real page box here.
     */
    fun draw(
        doc: PDDocument, page: PDPage, pageRotation: Int, pageW: Float, pageH: Float,
        elements: List<PageElement>, res: WriterResources,
    ) {
        val crop = page.cropBox
        val sideways = pageRotation % 180 != 0
        val shownW = if (sideways) crop.height else crop.width
        val shownH = if (sideways) crop.width else crop.height
        val m = PageGeometry.visualToUser(pageRotation, crop.lowerLeftX, crop.lowerLeftY, crop.width, crop.height)
        // resetContext = true wraps the existing content in q/Q so its graphics state cannot leak into ours.
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            // From here on we draw in page space: points, origin top-left, y down.
            cs.transform(Matrix(m[0], m[1], m[2], m[3], m[4], m[5]))
            if (pageW > 0f && pageH > 0f) cs.transform(Matrix(shownW / pageW, 0f, 0f, shownH / pageH, 0f, 0f))
            for (e in elements) {
                cs.saveGraphicsState()
                when (e) {
                    is RectMark -> rect(cs, e)
                    is InkStroke -> ink(cs, e)
                    is TextBox -> text(cs, e, res)
                    is ImageStamp -> stamp(cs, e, res)
                    is LineMark -> line(cs, e)
                    is ShapeMark -> shape(cs, e)
                    is HiddenText -> hiddenText(cs, e, res, pageW, pageH)
                }
                cs.restoreGraphicsState()
            }
        }
    }

    private fun color(argb: Int) = PDColor(
        floatArrayOf(Color.red(argb) / 255f, Color.green(argb) / 255f, Color.blue(argb) / 255f),
        PDDeviceRGB.INSTANCE,
    )

    private fun rect(cs: PDPageContentStream, e: RectMark) {
        val gs = PDExtendedGraphicsState()
        var useGs = false
        if (e.blend == Blend.MULTIPLY) { gs.cosObject.setItem(COSName.BM, COSName.getPDFName("Multiply")); useGs = true }
        if (Color.alpha(e.color) < 255) { gs.nonStrokingAlphaConstant = Color.alpha(e.color) / 255f; useGs = true }
        if (useGs) cs.setGraphicsStateParameters(gs)
        cs.setNonStrokingColor(color(e.color))
        val b = e.bounds
        cs.addRect(b.left, b.top, b.width, b.height)
        cs.fill()
    }

    private fun ink(cs: PDPageContentStream, e: InkStroke) {
        val pts = e.points
        if (pts.isEmpty()) return
        if (Color.alpha(e.color) < 255) {
            cs.setGraphicsStateParameters(PDExtendedGraphicsState().apply { strokingAlphaConstant = Color.alpha(e.color) / 255f })
        }
        cs.setStrokingColor(color(e.color))
        cs.setLineWidth(e.width)
        cs.setLineCapStyle(1)
        cs.setLineJoinStyle(1)
        var cx = pts[0].x
        var cy = pts[0].y
        cs.moveTo(cx, cy)
        if (pts.size == 1) {
            cs.lineTo(cx + 0.01f, cy)
        } else {
            // Same curve as ElementPainter.inkPath, with each quadratic raised to a cubic.
            for (i in 1 until pts.size - 1) {
                val p = pts[i]
                val n = pts[i + 1]
                val ex = (p.x + n.x) / 2f
                val ey = (p.y + n.y) / 2f
                cs.curveTo(
                    cx + 2f / 3f * (p.x - cx), cy + 2f / 3f * (p.y - cy),
                    ex + 2f / 3f * (p.x - ex), ey + 2f / 3f * (p.y - ey),
                    ex, ey,
                )
                cx = ex; cy = ey
            }
            cs.lineTo(pts.last().x, pts.last().y)
        }
        cs.stroke()
    }

    private fun strokeStyle(cs: PDPageContentStream, argb: Int, width: Float) {
        cs.setStrokingColor(color(argb))
        cs.setLineWidth(width)
        cs.setLineCapStyle(1)
        cs.setLineJoinStyle(1)
    }

    private fun line(cs: PDPageContentStream, e: LineMark) {
        strokeStyle(cs, e.color, e.width)
        for (seg in LineGeometry.segments(e)) {
            cs.moveTo(seg[0].x, seg[0].y)
            for (i in 1 until seg.size) cs.lineTo(seg[i].x, seg[i].y)
        }
        cs.stroke()
    }

    private fun shape(cs: PDPageContentStream, e: ShapeMark) {
        val (iw, ih) = enterBox(cs, e)
        strokeStyle(cs, e.color, e.width)
        when (e.kind) {
            ShapeKind.RECT -> { cs.setLineJoinStyle(0); cs.addRect(0f, 0f, iw, ih) }
            ShapeKind.OVAL -> {
                // Four Bezier quarters; k is the usual circle constant.
                val k = 0.5522848f
                val rx = iw / 2f; val ry = ih / 2f
                cs.moveTo(iw, ry)
                cs.curveTo(iw, ry + k * ry, rx + k * rx, ih, rx, ih)
                cs.curveTo(rx - k * rx, ih, 0f, ry + k * ry, 0f, ry)
                cs.curveTo(0f, ry - k * ry, rx - k * rx, 0f, rx, 0f)
                cs.curveTo(rx + k * rx, 0f, iw, ry - k * ry, iw, ry)
                cs.closePath()
            }
            else -> for (s in ShapeMark.strokes(e.kind)) {
                cs.moveTo(s[0].x * iw, s[0].y * ih)
                for (i in 1 until s.size) cs.lineTo(s[i].x * iw, s[i].y * ih)
            }
        }
        cs.stroke()
    }

    /** Move the origin to the element's own top-left corner, turned by its quarter turns. Returns inner width and height. */
    private fun enterBox(cs: PDPageContentStream, e: PageElement): Pair<Float, Float> {
        val b = e.bounds
        val odd = e.turns % 2 == 1
        val iw = if (odd) b.height else b.width
        val ih = if (odd) b.width else b.height
        cs.transform(Matrix.getRotateInstance(Math.toRadians(e.turns * 90.0), b.centerX, b.centerY))
        cs.transform(Matrix.getTranslateInstance(-iw / 2f, -ih / 2f))
        return iw to ih
    }

    private fun text(cs: PDPageContentStream, e: TextBox, res: WriterResources) {
        val (iw, _) = enterBox(cs, e)
        val lay = TextLayout.layout(e.text, e.fontSize, iw)
        cs.setNonStrokingColor(color(e.color))
        cs.beginText()
        cs.setFont(res.font, e.fontSize)
        lay.lines.forEachIndexed { i, line ->
            val clean = res.clean(line, "?")
            if (clean.isNotEmpty()) {
                // y is flipped back so glyphs are upright in our y-down space.
                cs.setTextMatrix(Matrix(1f, 0f, 0f, -1f, 0f, lay.baseline(i)))
                cs.showText(clean)
            }
        }
        cs.endText()
    }

    private fun stamp(cs: PDPageContentStream, e: ImageStamp, res: WriterResources) {
        val img = res.image(e.imagePath) ?: return
        val (iw, ih) = enterBox(cs, e)
        cs.transform(Matrix(iw, 0f, 0f, -ih, 0f, ih))
        cs.drawImage(img, 0f, 0f, 1f, 1f)
    }

    private fun hiddenText(cs: PDPageContentStream, e: HiddenText, res: WriterResources, pageW: Float, pageH: Float) {
        val f = PageGeometry.shownToPage(e.frameRotation, pageW, pageH)
        cs.transform(Matrix(f[0], f[1], f[2], f[3], f[4], f[5]))
        cs.beginText()
        cs.setRenderingMode(RenderingMode.NEITHER)
        for (w in e.words) {
            val t = res.clean(w.text, "")
            if (t.isBlank() || w.rect.width <= 0f || w.rect.height <= 0f) continue
            val size = (w.rect.height * 0.9f).coerceAtLeast(1f)
            val natural = res.font.getStringWidth(t) / 1000f * size
            if (natural <= 0f) continue
            cs.setFont(res.font, size)
            // Stretch the word horizontally so selection and search highlight line up with the scan.
            cs.setTextMatrix(Matrix(w.rect.width / natural, 0f, 0f, -1f, w.rect.left, w.rect.bottom - w.rect.height * 0.2f))
            cs.showText(t)
        }
        cs.endText()
    }
}
