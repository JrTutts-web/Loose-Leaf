package io.github.jrtutts.looseleaf.modules.ocr

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import com.googlecode.tesseract.android.TessBaseAPI
import io.github.jrtutts.looseleaf.R
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * The languages the OCR engine can read. English ships inside the app; others
 * are Tesseract `.traineddata` files the user brings along and adds through
 * the system file picker (the app cannot download anything). They are kept in
 * the app's private storage next to the unpacked English data.
 */
/** A language file was refused; [reason] is a string resource that says why. */
class LanguageFileException(@StringRes val reason: Int) : IOException()

object OcrLanguages {
    const val BUNDLED = "eng"
    private const val SUFFIX = ".traineddata"
    private val validName = Regex("[A-Za-z][A-Za-z0-9_]{1,30}")

    fun root(context: Context) = File(context.filesDir, "ocr")
    fun dataDir(context: Context) = File(root(context), "tessdata")

    /** English first, then whatever has been added, in alphabetical order. */
    fun installed(context: Context): List<String> {
        val added = dataDir(context).listFiles()
            ?.filter { it.name.endsWith(SUFFIX) }
            ?.map { it.name.removeSuffix(SUFFIX) }
            ?.filter { it != BUNDLED }
            ?.sorted()
            .orEmpty()
        return listOf(BUNDLED) + added
    }

    private fun prefs(context: Context) = context.getSharedPreferences("ocr", Context.MODE_PRIVATE)

    /** The languages to recognise, limited to what is installed. Never empty. */
    fun selected(context: Context): List<String> {
        val installed = installed(context)
        val wanted = prefs(context).getStringSet("languages", null) ?: setOf(BUNDLED)
        return installed.filter { it in wanted }.ifEmpty { listOf(BUNDLED) }
    }

    fun select(context: Context, languages: Collection<String>) {
        prefs(context).edit().putStringSet("languages", languages.toSet()).apply()
    }

    /** "German (deu)" where Android knows the code, else just the code. */
    fun displayName(code: String): String {
        val base = code.substringBefore('_')
        val name = runCatching { Locale(base).getDisplayLanguage(Locale.getDefault()) }.getOrNull()
        return if (name.isNullOrEmpty() || name.equals(base, ignoreCase = true)) code
        else "${name.replaceFirstChar { it.titlecase(Locale.getDefault()) }} ($code)"
    }

    /**
     * Copy the language file at [uri] (called [fileName]) into the app and
     * check that the engine accepts it. Returns the language code.
     */
    @Throws(LanguageFileException::class)
    fun add(context: Context, uri: Uri, fileName: String): String {
        if (!fileName.endsWith(SUFFIX)) throw LanguageFileException(R.string.ocr_lang_bad_name)
        val code = fileName.removeSuffix(SUFFIX)
        if (!validName.matches(code)) throw LanguageFileException(R.string.ocr_lang_bad_name)
        if (code == BUNDLED) throw LanguageFileException(R.string.ocr_lang_bundled)
        val dir = dataDir(context).apply { mkdirs() }
        val target = File(dir, code + SUFFIX)
        val backup = File(dir, "$code.old")
        if (target.exists()) target.renameTo(backup)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw LanguageFileException(R.string.ocr_lang_unreadable)
            input.use { i -> target.outputStream().use { i.copyTo(it) } }
            val api = TessBaseAPI()
            val ok = try { api.init(root(context).absolutePath, code) } finally { api.recycle() }
            if (!ok) throw LanguageFileException(R.string.ocr_lang_rejected)
            backup.delete()
            return code
        } catch (t: Throwable) {
            target.delete()
            if (backup.exists()) backup.renameTo(target)
            throw t as? LanguageFileException ?: LanguageFileException(R.string.ocr_lang_unreadable)
        }
    }

    fun remove(context: Context, code: String) {
        if (code == BUNDLED) return
        File(dataDir(context), code + SUFFIX).delete()
        select(context, selected(context) - code)
    }
}
