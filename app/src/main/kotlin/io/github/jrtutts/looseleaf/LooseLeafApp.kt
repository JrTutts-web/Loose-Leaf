package io.github.jrtutts.looseleaf

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import io.github.jrtutts.looseleaf.core.render.Fonts
import java.io.File

class LooseLeafApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(this)
        Fonts.init(this)
        // Sessions used to live in the cache; they are in filesDir now (see SessionStore).
        Thread { File(cacheDir, "sessions").deleteRecursively() }.start()
    }
}
