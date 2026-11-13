package io.github.jrtutts.looseleaf

import io.github.jrtutts.looseleaf.core.model.Blend
import io.github.jrtutts.looseleaf.core.model.DocumentModel
import io.github.jrtutts.looseleaf.core.model.FormKey
import io.github.jrtutts.looseleaf.core.model.HiddenText
import io.github.jrtutts.looseleaf.core.model.ImageStamp
import io.github.jrtutts.looseleaf.core.model.InkStroke
import io.github.jrtutts.looseleaf.core.model.LineMark
import io.github.jrtutts.looseleaf.core.model.PPoint
import io.github.jrtutts.looseleaf.core.model.PRect
import io.github.jrtutts.looseleaf.core.model.PageEntry
import io.github.jrtutts.looseleaf.core.model.RectMark
import io.github.jrtutts.looseleaf.core.model.ShapeKind
import io.github.jrtutts.looseleaf.core.model.ShapeMark
import io.github.jrtutts.looseleaf.core.model.TextBox
import io.github.jrtutts.looseleaf.core.pdf.PageRasterizer
import io.github.jrtutts.looseleaf.core.session.ModelJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ModelJsonTest {
    private val dir: File = Files.createTempDirectory("looseleaf").toFile()

    private fun sample(): DocumentModel {
        val picture = File(dir, "stamp-a.png").apply { writeText("x") }
        val elements = listOf(
            RectMark(1, "annotate", PRect(1f, 2f, 3f, 4f), -256, Blend.MULTIPLY),
            RectMark(2, "redact", PRect(10f, 20f, 30f, 40f), -16777216, Blend.NORMAL, erase = true),
            InkStroke(3, "annotate", listOf(PPoint(0f, 0f), PPoint(5.5f, 7.25f)), -1, 2f),
            LineMark(4, "annotate", PPoint(1f, 1f), PPoint(9f, 3f), -65536, 3f, arrow = true),
            ShapeMark(5, "stamps", PRect(0f, 0f, 14f, 14f), ShapeKind.CHECK, -16777216, 1.5f, turns = 1),
            TextBox(6, "text", PRect(5f, 5f, 80f, 20f), "Line one\nwith \"quotes\" and é", 12f, -16777216, turns = 3),
            ImageStamp(7, "signatures", PRect(0f, 0f, 50f, 20f), picture.path),
            HiddenText(8, "ocr", listOf(HiddenText.Word(PRect(1f, 1f, 2f, 2f), "word")), frameRotation = 90),
        )
        return DocumentModel(
            pages = listOf(PageEntry(11, 0, 0, 612f, 792f, 90, elements), PageEntry(12, 1, 4, 595.5f, 842f)),
            formValues = mapOf(FormKey(0, "name.first") to "Ada"),
        )
    }

    @Test
    fun roundTripKeepsEverything() {
        val model = sample()
        // Through text, as it is stored on disk.
        val back = ModelJson.read(JSONObject(ModelJson.write(model, dir).toString()), dir)
        assertEquals(model, back)
    }

    @Test
    fun maxIdCoversPagesAndElements() {
        assertEquals(12L, ModelJson.maxId(sample()))
    }

    @Test
    fun pictureWithMissingFileIsDropped() {
        val json = ModelJson.write(sample(), dir).toString()
        File(dir, "stamp-a.png").delete()
        val back = ModelJson.read(JSONObject(json), dir)
        assertTrue(back.pages[0].elements.none { it is ImageStamp })
        assertEquals(7, back.pages[0].elements.size)
    }

    @Test
    fun redactedPageDropsHiddenWordsUnderTheBox() {
        val words = listOf(
            HiddenText.Word(PRect(12f, 22f, 28f, 30f), "secret"),
            HiddenText.Word(PRect(100f, 100f, 140f, 110f), "public"),
        )
        val page = PageEntry(
            1, 0, 0, 612f, 792f,
            elements = listOf(
                HiddenText(1, "ocr", words),
                RectMark(2, "redact", PRect(10f, 20f, 30f, 40f), -16777216, Blend.NORMAL, erase = true),
                TextBox(3, "text", PRect(0f, 0f, 10f, 10f), "on top", 12f, -16777216),
            ),
        )
        val rest = PageRasterizer.remaining(page, burned = 2)
        assertEquals(listOf("public"), (rest[0] as HiddenText).words.map { it.text })
        assertTrue(rest[1] is TextBox)
        assertEquals(2, rest.size)
    }
}
