package io.github.jrtutts.looseleaf.core.pdf

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File

/** Small helpers for reading PDF files with pdfbox. */
object PdfFiles {
    private const val MAIN_MEMORY = 48L * 1024 * 1024

    /** Load for editing. Large documents spill to temp files in the app cache instead of the heap. */
    fun load(file: File, password: String = ""): PDDocument {
        val doc = PDDocument.load(file, password, MemoryUsageSetting.setupMixed(MAIN_MEMORY))
        // Whatever we write is a new, unprotected copy.
        if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
        return doc
    }

    /** Write an unprotected copy of a password-protected PDF. Throws if the password is wrong. */
    fun decrypt(src: File, password: String, out: File) {
        load(src, password).use { doc ->
            doc.isAllSecurityToBeRemoved = true
            doc.save(out)
        }
    }
}
