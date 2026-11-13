package io.github.jrtutts_web.looseleaf.core.session

import android.content.Context
import io.github.jrtutts_web.looseleaf.core.model.DocumentModel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What was kept of an editing session that never got saved. */
class StoredSession(
    val dir: File,
    val name: String,
    val sourceUri: String?,
    /** File names of the working copies in [dir], in source-id order. */
    val sources: List<String>,
    val model: DocumentModel,
    val dirty: Boolean,
    val time: Long,
)

/**
 * Keeps the state of the editing session in the app's private storage, so
 * that unsaved edits survive the app being closed by the system or crashing.
 * Each session has its own directory with the working copies of the PDFs and
 * a `session.json` describing the edits. Nothing here ever leaves the device,
 * and a session's directory is deleted as soon as it is closed.
 */
object SessionStore {
    private const val FILE = "session.json"

    fun root(context: Context) = File(context.filesDir, "sessions")

    fun newDir(context: Context) = File(root(context), System.nanoTime().toString())

    /** Called from a background thread; replaces the file in one step so a crash cannot leave half of it. */
    fun write(dir: File, name: String, sourceUri: String?, sources: List<String>, model: DocumentModel, dirty: Boolean) {
        if (!dir.isDirectory) return
        val json = JSONObject().apply {
            put("version", 1)
            put("name", name)
            if (sourceUri != null) put("sourceUri", sourceUri)
            put("sources", JSONArray(sources))
            put("dirty", dirty)
            put("time", System.currentTimeMillis())
            put("model", ModelJson.write(model, dir))
        }
        val tmp = File(dir, "$FILE.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(File(dir, FILE))) tmp.delete()
    }

    /** Null if there is nothing usable in [dir]. */
    fun read(dir: File): StoredSession? = runCatching {
        val o = JSONObject(File(dir, FILE).readText())
        val names = o.getJSONArray("sources").let { a -> (0 until a.length()).map { a.getString(it) } }
        if (names.isEmpty() || names.any { !File(dir, it).isFile }) return null
        val model = ModelJson.read(o.getJSONObject("model"), dir)
        if (model.pages.isEmpty() || model.pages.any { it.source !in names.indices }) return null
        StoredSession(dir, o.getString("name"), o.optString("sourceUri").ifEmpty { null }, names, model, o.optBoolean("dirty"), o.optLong("time"))
    }.getOrNull()

    /** The session directories that exist right now. Cheap; call before any new session is created. */
    fun existing(context: Context): List<File> = root(context).listFiles { f -> f.isDirectory }?.toList().orEmpty()

    /**
     * Of [dirs] (left behind by an earlier run of the app), keep the newest
     * one that has unsaved edits and delete the rest.
     */
    fun recoverable(dirs: List<File>): StoredSession? {
        val best = dirs.mapNotNull { read(it) }.filter { it.dirty }.maxByOrNull { it.time }
        for (d in dirs) if (d != best?.dir) d.deleteRecursively()
        return best
    }
}
