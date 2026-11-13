package io.github.jrtutts.looseleaf.core.settings

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import io.github.jrtutts.looseleaf.core.module.EditorModule

/** On-device preferences. Today that is just which modules are switched on. */
class Settings(context: Context, private val modules: List<EditorModule>) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val enabled = mutableStateMapOf<String, Boolean>().apply {
        for (m in modules) put(m.id, prefs.getBoolean(key(m), m.enabledByDefault))
    }

    fun isEnabled(module: EditorModule) = enabled[module.id] ?: false

    fun setEnabled(module: EditorModule, on: Boolean) {
        enabled[module.id] = on
        prefs.edit().putBoolean(key(module), on).apply()
    }

    /** The modules that are switched on, in registry order. */
    val enabledModules: List<EditorModule> get() = modules.filter { isEnabled(it) }

    private fun key(m: EditorModule) = "module.${m.id}"
}
