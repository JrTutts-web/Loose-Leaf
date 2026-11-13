package io.github.jrtutts_web.looseleaf.core.session

import io.github.jrtutts_web.looseleaf.core.model.Blend
import io.github.jrtutts_web.looseleaf.core.model.DocumentModel
import io.github.jrtutts_web.looseleaf.core.model.FormKey
import io.github.jrtutts_web.looseleaf.core.model.HiddenText
import io.github.jrtutts_web.looseleaf.core.model.ImageStamp
import io.github.jrtutts_web.looseleaf.core.model.InkStroke
import io.github.jrtutts_web.looseleaf.core.model.LineMark
import io.github.jrtutts_web.looseleaf.core.model.PPoint
import io.github.jrtutts_web.looseleaf.core.model.PRect
import io.github.jrtutts_web.looseleaf.core.model.PageElement
import io.github.jrtutts_web.looseleaf.core.model.PageEntry
import io.github.jrtutts_web.looseleaf.core.model.RectMark
import io.github.jrtutts_web.looseleaf.core.model.ShapeKind
import io.github.jrtutts_web.looseleaf.core.model.ShapeMark
import io.github.jrtutts_web.looseleaf.core.model.TextBox
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The document model as JSON, used to keep unsaved edits on the device (see
 * SessionStore). Pictures placed on pages live as files in the session
 * directory; they are stored by file name and resolved against [dir] again
 * when read.
 */
object ModelJson {

    fun write(model: DocumentModel, dir: File): JSONObject = JSONObject().apply {
        put("pages", JSONArray().apply {
            for (p in model.pages) put(JSONObject().apply {
                put("key", p.key); put("source", p.source); put("index", p.index)
                put("w", p.width.toDouble()); put("h", p.height.toDouble()); put("rot", p.rotation)
                put("elements", JSONArray().apply { for (e in p.elements) put(element(e, dir)) })
            })
        })
        put("forms", JSONArray().apply {
            for ((k, v) in model.formValues) put(JSONObject().apply { put("s", k.source); put("n", k.name); put("v", v) })
        })
    }

    fun read(o: JSONObject, dir: File): DocumentModel {
        val pages = o.getJSONArray("pages").objects().map { p ->
            PageEntry(
                key = p.getLong("key"), source = p.getInt("source"), index = p.getInt("index"),
                width = p.f("w"), height = p.f("h"), rotation = p.getInt("rot"),
                elements = p.getJSONArray("elements").objects().mapNotNull { element(it, dir) },
            )
        }
        val forms = LinkedHashMap<FormKey, String>()
        for (f in o.getJSONArray("forms").objects()) forms[FormKey(f.getInt("s"), f.getString("n"))] = f.getString("v")
        return DocumentModel(pages, forms)
    }

    /** The largest id used anywhere in [model], so that new ids can start above it. */
    fun maxId(model: DocumentModel): Long =
        model.pages.maxOfOrNull { p -> maxOf(p.key, p.elements.maxOfOrNull { it.id } ?: 0L) } ?: 0L

    private fun element(e: PageElement, dir: File): JSONObject = JSONObject().apply {
        put("id", e.id); put("owner", e.owner)
        when (e) {
            is RectMark -> {
                put("t", "rect"); put("b", rect(e.bounds)); put("color", e.color)
                put("blend", e.blend.name); put("erase", e.erase)
            }
            is InkStroke -> { put("t", "ink"); put("pts", points(e.points)); put("color", e.color); put("width", e.width.toDouble()) }
            is LineMark -> {
                put("t", "line"); put("pts", points(listOf(e.start, e.end))); put("color", e.color)
                put("width", e.width.toDouble()); put("arrow", e.arrow)
            }
            is ShapeMark -> {
                put("t", "shape"); put("b", rect(e.bounds)); put("kind", e.kind.name); put("color", e.color)
                put("width", e.width.toDouble()); put("turns", e.turns)
            }
            is TextBox -> {
                put("t", "text"); put("b", rect(e.bounds)); put("text", e.text); put("size", e.fontSize.toDouble())
                put("color", e.color); put("turns", e.turns)
            }
            is ImageStamp -> {
                put("t", "image"); put("b", rect(e.bounds)); put("turns", e.turns)
                val f = File(e.imagePath)
                put("path", if (f.parentFile == dir) f.name else e.imagePath)
            }
            is HiddenText -> {
                put("t", "hidden"); put("frame", e.frameRotation)
                put("words", JSONArray().apply {
                    for (w in e.words) put(JSONArray().apply {
                        put(w.rect.left.toDouble()); put(w.rect.top.toDouble())
                        put(w.rect.right.toDouble()); put(w.rect.bottom.toDouble()); put(w.text)
                    })
                })
            }
        }
    }

    /** Null for an element this version does not know, or a picture whose file is gone. */
    private fun element(o: JSONObject, dir: File): PageElement? {
        val id = o.getLong("id")
        val owner = o.getString("owner")
        return when (o.getString("t")) {
            "rect" -> RectMark(id, owner, rect(o.getJSONArray("b")), o.getInt("color"), Blend.valueOf(o.getString("blend")), o.optBoolean("erase"))
            "ink" -> InkStroke(id, owner, points(o.getJSONArray("pts")), o.getInt("color"), o.f("width"))
            "line" -> points(o.getJSONArray("pts")).let { LineMark(id, owner, it[0], it[1], o.getInt("color"), o.f("width"), o.optBoolean("arrow")) }
            "shape" -> ShapeMark(id, owner, rect(o.getJSONArray("b")), ShapeKind.valueOf(o.getString("kind")), o.getInt("color"), o.f("width"), o.optInt("turns"))
            "text" -> TextBox(id, owner, rect(o.getJSONArray("b")), o.getString("text"), o.f("size"), o.getInt("color"), o.optInt("turns"))
            "image" -> {
                val path = o.getString("path")
                val file = if (path.startsWith("/")) File(path) else File(dir, path)
                if (file.exists()) ImageStamp(id, owner, rect(o.getJSONArray("b")), file.path, o.optInt("turns")) else null
            }
            "hidden" -> {
                val words = o.getJSONArray("words")
                HiddenText(id, owner, (0 until words.length()).map { i ->
                    val w = words.getJSONArray(i)
                    HiddenText.Word(PRect(w.f(0), w.f(1), w.f(2), w.f(3)), w.getString(4))
                }, o.optInt("frame"))
            }
            else -> null
        }
    }

    private fun rect(r: PRect) = JSONArray().apply {
        put(r.left.toDouble()); put(r.top.toDouble()); put(r.right.toDouble()); put(r.bottom.toDouble())
    }
    private fun rect(a: JSONArray) = PRect(a.f(0), a.f(1), a.f(2), a.f(3))

    private fun points(pts: List<PPoint>) = JSONArray().apply { for (p in pts) { put(p.x.toDouble()); put(p.y.toDouble()) } }
    private fun points(a: JSONArray) = (0 until a.length() / 2).map { PPoint(a.f(it * 2), a.f(it * 2 + 1)) }

    private fun JSONObject.f(name: String) = getDouble(name).toFloat()
    private fun JSONArray.f(i: Int) = getDouble(i).toFloat()
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
}
