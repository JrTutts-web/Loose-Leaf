package io.github.jrtutts_web.looseleaf.core.model

/**
 * One page of the document being edited: a page of one of the session's source
 * PDFs, plus what the user has done to it.
 *
 * [width] and [height] are the size of the source page in points as displayed
 * (the source's own /Rotate already applied). [rotation] is extra clockwise
 * rotation added in this app: 0, 90, 180 or 270.
 */
data class PageEntry(
    val key: Long,
    val source: Int,
    val index: Int,
    val width: Float,
    val height: Float,
    val rotation: Int = 0,
    val elements: List<PageElement> = emptyList(),
) {
    val bounds get() = PRect(0f, 0f, width, height)
    /** Size on screen, after [rotation]. */
    val shownWidth get() = if (rotation % 180 == 0) width else height
    val shownHeight get() = if (rotation % 180 == 0) height else width
    /** Quarter turns that make content read upright on screen despite [rotation]. */
    val uprightTurns get() = (4 - rotation / 90) % 4
}

/** Identifies an AcroForm field: which source PDF it lives in and its fully qualified name. */
data class FormKey(val source: Int, val name: String)

/**
 * The whole document as an immutable value. Every edit produces a new
 * DocumentModel, which is what makes undo and redo trivial.
 */
data class DocumentModel(
    val pages: List<PageEntry>,
    val formValues: Map<FormKey, String> = emptyMap(),
) {
    fun page(key: Long) = pages.firstOrNull { it.key == key }

    fun updatePage(key: Long, f: (PageEntry) -> PageEntry) =
        copy(pages = pages.map { if (it.key == key) f(it) else it })

    fun addElement(pageKey: Long, e: PageElement) =
        updatePage(pageKey) { it.copy(elements = it.elements + e) }

    fun findElement(id: Long): Pair<PageEntry, PageElement>? {
        for (p in pages) for (e in p.elements) if (e.id == id) return p to e
        return null
    }

    fun updateElement(id: Long, f: (PageElement) -> PageElement) =
        copy(pages = pages.map { p ->
            if (p.elements.none { it.id == id }) p
            else p.copy(elements = p.elements.map { if (it.id == id) f(it) else it })
        })

    fun removeElement(id: Long) =
        copy(pages = pages.map { p ->
            if (p.elements.none { it.id == id }) p else p.copy(elements = p.elements.filterNot { it.id == id })
        })
}
