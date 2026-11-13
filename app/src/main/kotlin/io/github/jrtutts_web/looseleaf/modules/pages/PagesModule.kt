package io.github.jrtutts_web.looseleaf.modules.pages

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import io.github.jrtutts_web.looseleaf.R
import io.github.jrtutts_web.looseleaf.core.module.EditorModule
import io.github.jrtutts_web.looseleaf.core.module.MenuAction

/** Page thumbnails: reorder, rotate, delete, extract to a new PDF, and merge another PDF in. */
object PagesModule : EditorModule {
    override val id = "pages"
    override val title = R.string.pages_title
    override val description = R.string.pages_description
    override val icon = Icons.Filled.GridView

    override val menuActions = listOf(
        MenuAction(R.string.pages_menu, Icons.Filled.GridView) { host -> host.showScreen { PagesScreen(host) } },
    )
}
