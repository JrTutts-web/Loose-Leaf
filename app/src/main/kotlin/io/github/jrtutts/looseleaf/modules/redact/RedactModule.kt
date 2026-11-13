package io.github.jrtutts.looseleaf.modules.redact

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HideSource
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import io.github.jrtutts.looseleaf.R
import io.github.jrtutts.looseleaf.core.model.Blend
import io.github.jrtutts.looseleaf.core.model.Ids
import io.github.jrtutts.looseleaf.core.model.PPoint
import io.github.jrtutts.looseleaf.core.model.PRect
import io.github.jrtutts.looseleaf.core.model.PageElement
import io.github.jrtutts.looseleaf.core.model.PageEntry
import io.github.jrtutts.looseleaf.core.model.RectMark
import io.github.jrtutts.looseleaf.core.module.EditorHost
import io.github.jrtutts.looseleaf.core.module.EditorModule
import io.github.jrtutts.looseleaf.core.module.PageHit
import io.github.jrtutts.looseleaf.core.module.Tool
import io.github.jrtutts.looseleaf.core.ui.OptionHint

/**
 * Redaction: black boxes that really remove what they cover. The module only
 * adds boxes marked `erase` to the model; the writer then saves every page
 * that has one as a picture (core/pdf/PageRasterizer.kt).
 */
object RedactModule : EditorModule {
    override val id = "redact"
    override val title = R.string.redact_title
    override val description = R.string.redact_description
    override val icon = Icons.Filled.HideSource

    private val tool = object : Tool {
        override val id = "redact.box"
        override val label = R.string.redact_tool
        override val icon: ImageVector = Icons.Filled.HideSource
        override val dragDraws get() = true
        private var start = PPoint(0f, 0f)
        private var pageKey = 0L

        override fun onDragStart(host: EditorHost, hit: PageHit) {
            start = hit.point
            pageKey = hit.page.key
        }

        override fun onDrag(host: EditorHost, point: PPoint) {
            host.session.preview = pageKey to
                RectMark(Ids.next(), RedactModule.id, PRect.ofPoints(start, point), 0xFF000000.toInt(), Blend.NORMAL, erase = true)
        }

        override fun onDragEnd(host: EditorHost) {
            val box = host.session.preview?.second
            host.session.preview = null
            if (box != null && box.bounds.width > 2f && box.bounds.height > 2f) {
                host.session.edit { it.addElement(pageKey, box) }
            }
        }

        override fun onDragCancel(host: EditorHost) { host.session.preview = null }

        @Composable
        override fun Options(host: EditorHost) = OptionHint(stringResource(R.string.redact_hint))
    }

    override val tools = listOf(tool)

    @Composable
    override fun SelectionOptions(host: EditorHost, page: PageEntry, element: PageElement) =
        OptionHint(stringResource(R.string.redact_selected))
}
