package io.github.jrtutts_web.looseleaf

import io.github.jrtutts_web.looseleaf.modules.library.LibraryStore
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryNamesTest {
    @Test
    fun pdfNameAddsExtensionOnce() {
        assertEquals("lease.pdf", LibraryStore.pdfName("lease"))
        assertEquals("lease.PDF", LibraryStore.pdfName(" lease.PDF "))
    }

    @Test
    fun pdfNameReplacesCharactersThatCannotBeInAFileName() {
        assertEquals("a_b_c.pdf", LibraryStore.pdfName("a/b:c"))
        assertEquals("document.pdf", LibraryStore.pdfName("   "))
    }

    @Test
    fun folderNameIsTrimmedAndCleaned() {
        assertEquals("2026 taxes", LibraryStore.folderName("  2026 taxes. "))
        assertEquals("", LibraryStore.folderName(" .. "))
    }
}
