package io.github.jrtutts.looseleaf.modules.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/** A folder or a PDF in the library. [id] is the document id the system's storage provider gave it. */
data class LibEntry(val id: String, val name: String, val isFolder: Boolean, val modified: Long, val size: Long)

enum class LibraryMode { ALL, SIGNED }

/**
 * The library: one folder on the phone, chosen once by the user with the
 * system folder picker, in which the app keeps saved PDFs. Because it is an
 * ordinary folder, the files are visible in the Files app, can be backed up,
 * and stay there if Loose Leaf is uninstalled. The app needs no storage
 * permission for it: access is the one grant the user gave for that folder.
 *
 * Everything here talks to the system storage provider and must be called off
 * the main thread, except the small preference getters.
 */
object LibraryStore {
    private const val PDF = "application/pdf"

    private fun prefs(context: Context) = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    /** The library folder, or null if none has been chosen or access to it was taken away. */
    fun root(context: Context): Uri? {
        val uri = prefs(context).getString("tree", null)?.let(Uri::parse) ?: return null
        val held = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
        return if (held) uri else null
    }

    fun setRoot(context: Context, tree: Uri) {
        prefs(context).edit().putString("tree", tree.toString()).remove("lastFolder").remove("lastFolderPath").apply()
    }

    fun rootId(tree: Uri): String = DocumentsContract.getTreeDocumentId(tree)

    fun mode(context: Context) =
        if (prefs(context).getString("mode", null) == "signed") LibraryMode.SIGNED else LibraryMode.ALL

    fun setMode(context: Context, mode: LibraryMode) {
        prefs(context).edit().putString("mode", if (mode == LibraryMode.SIGNED) "signed" else "all").apply()
    }

    /** True once the user answered "not now" to setting the library up while saving. */
    var declined: Boolean = false

    /** The folder the last save went to, as (id, path for display), if it is remembered. */
    fun lastFolder(context: Context): Pair<String, String>? {
        val p = prefs(context)
        val id = p.getString("lastFolder", null) ?: return null
        return id to (p.getString("lastFolderPath", null) ?: return null)
    }

    fun setLastFolder(context: Context, id: String, path: String) {
        prefs(context).edit().putString("lastFolder", id).putString("lastFolderPath", path).apply()
    }

    fun uri(tree: Uri, id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    /** Folders first, then PDFs, newest first. Other kinds of files in the folder are left out. */
    fun list(context: Context, tree: Uri, folderId: String): List<LibEntry> {
        val out = ArrayList<LibEntry>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId)
        val columns = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE,
        )
        context.contentResolver.query(children, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val mime = c.getString(2) ?: continue
                val name = c.getString(1) ?: continue
                val folder = mime == Document.MIME_TYPE_DIR
                if (!folder && mime != PDF && !name.endsWith(".pdf", ignoreCase = true)) continue
                out += LibEntry(c.getString(0), name, folder, if (c.isNull(3)) 0L else c.getLong(3), if (c.isNull(4)) 0L else c.getLong(4))
            }
        }
        return out.sortedWith(compareByDescending<LibEntry> { it.isFolder }.thenBy { if (it.isFolder) it.name.lowercase() else "" }.thenByDescending { it.modified })
    }

    fun exists(context: Context, tree: Uri, id: String): Boolean = runCatching {
        context.contentResolver.query(uri(tree, id), arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } == true
    }.getOrDefault(false)

    fun createFolder(context: Context, tree: Uri, parentId: String, name: String): String? =
        DocumentsContract.createDocument(context.contentResolver, uri(tree, parentId), Document.MIME_TYPE_DIR, name)
            ?.let(DocumentsContract::getDocumentId)

    /** A new, empty PDF. If the name is taken the system picks a free one ("name (1).pdf"); nothing is overwritten. */
    fun createPdf(context: Context, tree: Uri, folderId: String, name: String): Uri =
        DocumentsContract.createDocument(context.contentResolver, uri(tree, folderId), PDF, name)
            ?: throw java.io.IOException("The library folder could not be written to")

    fun rename(context: Context, tree: Uri, id: String, name: String): Boolean =
        runCatching { DocumentsContract.renameDocument(context.contentResolver, uri(tree, id), name) != null }.getOrDefault(false)

    fun delete(context: Context, tree: Uri, id: String): Boolean =
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri(tree, id)) }.getOrDefault(false)

    fun move(context: Context, tree: Uri, id: String, fromId: String, toId: String): Boolean =
        runCatching {
            DocumentsContract.moveDocument(context.contentResolver, uri(tree, id), uri(tree, fromId), uri(tree, toId)) != null
        }.getOrDefault(false)

    /** A name that is safe as a file name and ends in .pdf. */
    fun pdfName(raw: String): String {
        val clean = raw.trim().replace(Regex("[/\\\\:*?\"<>|\\p{Cntrl}]"), "_").trim('.', ' ').ifEmpty { "document" }
        return if (clean.endsWith(".pdf", ignoreCase = true)) clean else "$clean.pdf"
    }

    fun folderName(raw: String): String =
        raw.trim().replace(Regex("[/\\\\:*?\"<>|\\p{Cntrl}]"), "_").trim('.', ' ')
}
