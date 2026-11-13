package io.github.jrtutts.looseleaf

import io.github.jrtutts.looseleaf.modules.pages.PageRanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageRangesTest {
    @Test fun parsesSinglesAndRanges() {
        assertEquals(listOf(0, 1, 2, 4), PageRanges.parse("1-3, 5", 10))
        assertEquals(listOf(6), PageRanges.parse(" 7 ", 10))
    }

    @Test fun openEndedRanges() {
        assertEquals(listOf(7, 8, 9), PageRanges.parse("8-", 10))
        assertEquals(listOf(0, 1), PageRanges.parse("-2", 10))
    }

    @Test fun keepsOrderAndDropsRepeats() {
        assertEquals(listOf(4, 0, 1), PageRanges.parse("5, 1-2, 1", 10))
    }

    @Test fun rejectsNonsense() {
        assertNull(PageRanges.parse("", 10))
        assertNull(PageRanges.parse("0", 10))
        assertNull(PageRanges.parse("11", 10))
        assertNull(PageRanges.parse("5-2", 10))
        assertNull(PageRanges.parse("a-b", 10))
    }

    @Test fun formatsShortest() {
        assertEquals("1-3, 5", PageRanges.format(listOf(4, 0, 2, 1)))
        assertEquals("", PageRanges.format(emptyList()))
    }
}
