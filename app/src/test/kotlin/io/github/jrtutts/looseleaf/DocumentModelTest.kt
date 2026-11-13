package io.github.jrtutts.looseleaf

import io.github.jrtutts.looseleaf.core.model.Blend
import io.github.jrtutts.looseleaf.core.model.DocumentModel
import io.github.jrtutts.looseleaf.core.model.InkStroke
import io.github.jrtutts.looseleaf.core.model.PPoint
import io.github.jrtutts.looseleaf.core.model.PRect
import io.github.jrtutts.looseleaf.core.model.PageEntry
import io.github.jrtutts.looseleaf.core.model.RectMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocumentModelTest {
    private val page = PageEntry(key = 1, source = 0, index = 0, width = 600f, height = 800f)
    private val mark = RectMark(10, "annotate", PRect(10f, 10f, 50f, 30f), 0xFFFFEE58.toInt(), Blend.MULTIPLY)

    @Test fun addUpdateRemoveElement() {
        var m = DocumentModel(listOf(page)).addElement(1, mark)
        assertEquals(mark, m.findElement(10)?.second)
        m = m.updateElement(10) { it.withBounds(PRect(0f, 0f, 5f, 5f)) }
        assertEquals(5f, m.findElement(10)!!.second.bounds.right, 0f)
        m = m.removeElement(10)
        assertNull(m.findElement(10))
    }

    @Test fun rotatedPageSwapsShownSize() {
        val turned = page.copy(rotation = 90)
        assertEquals(800f, turned.shownWidth, 0f)
        assertEquals(600f, turned.shownHeight, 0f)
        assertEquals(3, turned.uprightTurns)
    }

    @Test fun inkScalesWithItsBounds() {
        val ink = InkStroke(1, "annotate", listOf(PPoint(0f, 0f), PPoint(10f, 20f)), 0, 2f)
        val bigger = ink.withBounds(PRect(100f, 100f, 120f, 140f))
        assertEquals(PPoint(100f, 100f), bigger.points.first())
        assertEquals(PPoint(120f, 140f), bigger.points.last())
    }

    @Test fun movedInsideKeepsSize() {
        val r = PRect(-10f, 790f, 30f, 810f).movedInside(page.bounds)
        assertEquals(PRect(0f, 780f, 40f, 800f), r)
    }
}
