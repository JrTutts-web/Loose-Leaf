package io.github.jrtutts.looseleaf.core.pdf

import android.content.Context
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.util.Matrix
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDNonTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import io.github.jrtutts.looseleaf.core.model.DocumentModel
import io.github.jrtutts.looseleaf.core.model.SourceFile
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Turns a [DocumentModel] into a new PDF file. This is the only place that
 * writes PDFs: it assembles the pages in model order from the source files,
 * fills in form values, flattens every page element into the page content and
 * applies added rotation. The source files are only read.
 */
object PdfWriter {

    /**
     * @param rasters pictures of the pages that carry a redaction, from [PageRasterizer]
     * @param postProcess optional last step on the assembled document before it
     *   is written (used by Compress to re-encode images).
     */
    @Throws(IOException::class)
    fun write(
        context: Context,
        sources: List<SourceFile>,
        model: DocumentModel,
        out: File,
        rasters: Map<Long, PageRaster> = emptyMap(),
        postProcess: ((PDDocument) -> Unit)? = null,
    ) {
        if (model.pages.isEmpty()) throw IOException("A PDF needs at least one page")
        val used = model.pages.map { it.source }.distinct()
        val docs = LinkedHashMap<Int, PDDocument>()
        try {
            for (id in used) {
                val doc = PdfFiles.load(sources[id].file)
                docs[id] = doc
                val values = model.formValues.filterKeys { it.source == id }.mapKeys { it.key.name }
                if (values.isNotEmpty()) applyFormValues(doc, values)
            }

            // The first source (the file the user opened) is the base when it is
            // still in use, so its outline, metadata and form survive.
            val baseId = if (0 in used) 0 else used.first()
            val dest = docs.getValue(baseId)
            val pagesOf = HashMap<Int, List<PDPage>>()
            pagesOf[baseId] = dest.pages.toList()
            for (id in used) {
                if (id == baseId) continue
                val src = docs.getValue(id)
                val before = dest.numberOfPages
                val count = src.numberOfPages
                PDFMergerUtility().appendDocument(dest, src)
                pagesOf[id] = dest.pages.toList().subList(before, before + count)
            }

            val wanted = model.pages.map { entry ->
                pagesOf.getValue(entry.source).getOrNull(entry.index)
                    ?: throw IOException("Page ${entry.index + 1} is missing from its source file")
            }
            val current = dest.pages.toList()
            if (!sameOrder(current, wanted)) restructure(dest, current, wanted)

            val resources = WriterResources(context, dest)
            model.pages.forEachIndexed { i, entry ->
                val page = wanted[i]
                val baseRotation = PageGeometry.normalizeRotation(page.rotation)
                var elements = entry.elements
                val raster = rasters[entry.key]
                if (raster != null) {
                    replaceWithPicture(dest, page, baseRotation, raster.file)
                    elements = PageRasterizer.remaining(entry, raster.burned)
                } else if (PageRasterizer.needsRaster(entry)) {
                    // Never write a page whose redaction could not be carried out.
                    throw IOException("Page ${i + 1} has a redaction but no picture was made of it")
                }
                if (elements.isNotEmpty()) {
                    ElementWriter.draw(dest, page, baseRotation, entry.width, entry.height, elements, resources)
                }
                if (entry.rotation != 0) page.rotation = (baseRotation + entry.rotation) % 360
            }
            if (rasters.isNotEmpty()) {
                // Form fields and tagged text that sat on a redacted page must go too.
                runCatching { pruneForm(dest, wanted) }
                runCatching { dest.documentCatalog.structureTreeRoot = null }
            }

            postProcess?.invoke(dest)
            dest.save(out)
        } finally {
            docs.values.forEach { runCatching { it.close() } }
        }
    }

    /** Throw away everything the page contains and show [jpeg] (a picture of the page) instead. */
    private fun replaceWithPicture(doc: PDDocument, page: PDPage, rotation: Int, jpeg: File) {
        page.annotations = emptyList()
        page.resources = PDResources()
        for (key in listOf("Thumb", "PieceInfo", "Metadata", "B", "StructParents", "AA")) {
            page.cosObject.removeItem(COSName.getPDFName(key))
        }
        val image = jpeg.inputStream().use { JPEGFactory.createFromStream(doc, it) }
        val crop = page.cropBox
        val m = PageGeometry.visualToUser(rotation, crop.lowerLeftX, crop.lowerLeftY, crop.width, crop.height)
        val w = if (rotation % 180 == 0) crop.width else crop.height
        val h = if (rotation % 180 == 0) crop.height else crop.width
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.OVERWRITE, true, false).use { cs ->
            cs.transform(Matrix(m[0], m[1], m[2], m[3], m[4], m[5]))
            cs.transform(Matrix(w, 0f, 0f, -h, 0f, h))
            cs.drawImage(image, 0f, 0f, 1f, 1f)
        }
    }

    private fun sameOrder(a: List<PDPage>, b: List<PDPage>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) if (a[i].cosObject !== b[i].cosObject) return false
        return true
    }

    /** Replace the page tree with exactly [wanted], in order, and scrub what was dropped. */
    private fun restructure(doc: PDDocument, current: List<PDPage>, wanted: List<PDPage>) {
        // Pages may inherit these from intermediate tree nodes we are about to drop.
        for (p in wanted) {
            p.mediaBox = p.mediaBox
            p.cropBox = p.cropBox
            p.rotation = p.rotation
            p.resources = p.resources ?: PDResources()
        }
        val root = doc.documentCatalog.cosObject.getDictionaryObject(COSName.PAGES) as COSDictionary
        val kids = COSArray()
        for (p in wanted) {
            p.cosObject.setItem(COSName.PARENT, root)
            kids.add(p.cosObject)
        }
        root.setItem(COSName.KIDS, kids)
        root.setInt(COSName.COUNT, wanted.size)

        val kept = identitySet<COSDictionary>().apply { wanted.forEach { add(it.cosObject) } }
        val removed = current.filter { it.cosObject !in kept }
        if (removed.isEmpty()) return

        runCatching { pruneForm(doc, wanted) }
        runCatching { doc.documentCatalog.structureTreeRoot = null }
        // Bookmarks or links elsewhere may still point at a deleted page, which
        // would keep its content in the file. Empty the page object so that
        // nothing of a deleted page can survive in the saved copy.
        removed.forEach { it.cosObject.clear() }
    }

    /** Drop form fields whose widgets were all on deleted pages. */
    private fun pruneForm(doc: PDDocument, wanted: List<PDPage>) {
        val form = doc.documentCatalog.acroForm ?: return
        val keptWidgets = identitySet<COSDictionary>()
        for (p in wanted) for (a in p.annotations) keptWidgets.add(a.cosObject)

        fun keep(field: PDField): Boolean = when (field) {
            is PDNonTerminalField -> {
                val children = field.children
                val kept = children.filter { keep(it) }
                if (kept.size != children.size) field.children = kept
                kept.isNotEmpty()
            }
            is PDTerminalField -> field.widgets.any { it.cosObject in keptWidgets }
            else -> true
        }
        val fields = form.fields
        val kept = fields.filter { keep(it) }
        if (kept.size != fields.size) form.fields = kept
    }

    private fun applyFormValues(doc: PDDocument, values: Map<String, String>) {
        val form = doc.documentCatalog.acroForm ?: return
        for ((name, value) in values) {
            val field = form.getField(name) ?: continue
            try {
                when (field) {
                    is PDCheckBox -> if (value.isEmpty() || value == "Off") field.unCheck() else field.check()
                    else -> field.setValue(value)
                }
            } catch (e: Exception) {
                // pdfbox could not build the appearance (usually a missing font).
                // Store the value and ask the viewer to draw the field itself.
                if (field is PDButton) {
                    field.cosObject.setName(COSName.V, value)
                    for (w in field.widgets) {
                        val normal = w.appearance?.normalAppearance
                        val on = normal != null && normal.isSubDictionary && normal.subDictionary.containsKey(COSName.getPDFName(value))
                        w.cosObject.setName(COSName.AS, if (on) value else "Off")
                    }
                } else {
                    field.cosObject.setString(COSName.V, value)
                }
                form.setNeedAppearances(true)
            }
        }
    }

    private fun <T> identitySet(): MutableSet<T> = Collections.newSetFromMap(IdentityHashMap())
}
