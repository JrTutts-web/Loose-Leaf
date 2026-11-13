package io.github.jrtutts_web.looseleaf

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import io.github.jrtutts_web.looseleaf.core.model.DocumentModel
import io.github.jrtutts_web.looseleaf.core.model.EditorSession
import io.github.jrtutts_web.looseleaf.core.model.Ids
import io.github.jrtutts_web.looseleaf.core.model.PageEntry
import io.github.jrtutts_web.looseleaf.core.model.SourceFile
import io.github.jrtutts_web.looseleaf.core.pdf.PdfFiles
import io.github.jrtutts_web.looseleaf.core.pdf.PdfRenderEngine
import io.github.jrtutts_web.looseleaf.core.session.ModelJson
import io.github.jrtutts_web.looseleaf.core.session.SessionStore
import io.github.jrtutts_web.looseleaf.core.session.StoredSession
import io.github.jrtutts_web.looseleaf.core.settings.Settings
import io.github.jrtutts_web.looseleaf.core.ui.EditorController
import io.github.jrtutts_web.looseleaf.modules.ModuleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A password-protected file waiting for its password. */
class PasswordRequest(val dir: File, val name: String, val uri: Uri, val file: File, val wrong: Boolean = false)

/** App state that outlives the activity: settings and the open document. */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    val modules = ModuleRegistry.all
    val settings = Settings(app, modules)

    var editor by mutableStateOf<EditorController?>(null); private set
    var opening by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var passwordRequest by mutableStateOf<PasswordRequest?>(null)
    var showSettings by mutableStateOf(false)

    /** A module's own screen opened from the home screen (see EditorModule.homeActions). */
    var homeScreen by mutableStateOf<(@Composable () -> Unit)?>(null)

    /** A file the user asked to open while another one has unsaved changes. */
    var pendingOpen by mutableStateOf<Uri?>(null)

    /** Unsaved edits left behind by an earlier run of the app, offered on the home screen. */
    var recoverable by mutableStateOf<StoredSession?>(null); private set

    init {
        // List what is there before anything new can be created, then sort it out in the background.
        val left = SessionStore.existing(app)
        if (left.isNotEmpty()) viewModelScope.launch {
            recoverable = withContext(Dispatchers.IO) { SessionStore.recoverable(left) }
        }
    }

    fun restoreRecoverable() {
        val stored = recoverable ?: return
        if (opening) return
        val app = getApplication<Application>()
        viewModelScope.launch {
            opening = true
            val engine = PdfRenderEngine()
            try {
                closeEditor()
                val sources = stored.sources.mapIndexed { i, name -> SourceFile(i, File(stored.dir, name)) }
                for (s in sources) engine.open(s.id, s.file)
                Ids.ensureAbove(ModelJson.maxId(stored.model))
                val session = EditorSession(
                    stored.dir, stored.name, stored.sourceUri?.let(Uri::parse), sources, stored.model, restoredDirty = true,
                )
                recoverable = null
                editor = EditorController(app, session, engine, settings, viewModelScope)
            } catch (e: Exception) {
                engine.close()
                discardRecoverable()
                error = app.getString(R.string.recover_failed)
            } finally {
                opening = false
            }
        }
    }

    fun discardRecoverable() {
        val dir = recoverable?.dir ?: return
        recoverable = null
        Thread { dir.deleteRecursively() }.start()
    }

    fun requestOpen(uri: Uri) {
        if (editor?.session?.isDirty == true) pendingOpen = uri else open(uri)
    }

    fun open(uri: Uri) {
        if (opening) return
        pendingOpen = null
        showSettings = false
        val app = getApplication<Application>()
        viewModelScope.launch {
            opening = true
            try {
                closeEditor()
                val dir = SessionStore.newDir(app)
                val file = File(dir, "src0.pdf")
                val name = withContext(Dispatchers.IO) {
                    dir.mkdirs()
                    val input = app.contentResolver.openInputStream(uri) ?: throw java.io.IOException("unreadable")
                    input.use { i -> file.outputStream().use { i.copyTo(it) } }
                    EditorController.displayName(app, uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document.pdf"
                }
                finishOpen(dir, name, uri, file)
            } catch (e: Exception) {
                error = app.getString(R.string.open_failed)
            } finally {
                opening = false
            }
        }
    }

    private suspend fun finishOpen(dir: File, name: String, uri: Uri, file: File) {
        val app = getApplication<Application>()
        val engine = PdfRenderEngine()
        val sizes = try {
            engine.openWithSizes(0, file)
        } catch (e: SecurityException) {
            engine.close()
            passwordRequest = PasswordRequest(dir, name, uri, file)
            return
        } catch (e: Exception) {
            engine.close()
            dir.deleteRecursively()
            error = app.getString(R.string.open_failed)
            return
        }
        if (sizes.isEmpty()) {
            engine.close()
            dir.deleteRecursively()
            error = app.getString(R.string.open_failed)
            return
        }
        val model = DocumentModel(sizes.mapIndexed { i, (w, h) -> PageEntry(Ids.next(), 0, i, w, h) })
        val session = EditorSession(dir, name, uri, listOf(SourceFile(0, file)), model)
        editor = EditorController(app, session, engine, settings, viewModelScope)
    }

    fun submitPassword(password: String) {
        val req = passwordRequest ?: return
        passwordRequest = null
        viewModelScope.launch {
            opening = true
            try {
                val unlocked = File(req.dir, "src0-unlocked.pdf")
                withContext(Dispatchers.IO) { PdfFiles.decrypt(req.file, password, unlocked) }
                finishOpen(req.dir, req.name, req.uri, unlocked)
            } catch (e: InvalidPasswordException) {
                passwordRequest = PasswordRequest(req.dir, req.name, req.uri, req.file, wrong = true)
            } catch (e: Exception) {
                req.dir.deleteRecursively()
                error = getApplication<Application>().getString(R.string.open_failed)
            } finally {
                opening = false
            }
        }
    }

    fun cancelPassword() {
        passwordRequest?.dir?.deleteRecursively()
        passwordRequest = null
    }

    fun closeEditor() {
        val e = editor ?: return
        editor = null
        e.close()
        e.renderer.close()
        val dir = e.session.dir
        Thread { dir.deleteRecursively() }.start()
    }

    override fun onCleared() {
        closeEditor()
    }
}
