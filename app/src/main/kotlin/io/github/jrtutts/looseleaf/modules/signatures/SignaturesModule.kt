package io.github.jrtutts.looseleaf.modules.signatures

import android.graphics.BitmapFactory
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Draw
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import io.github.jrtutts.looseleaf.R
import io.github.jrtutts.looseleaf.core.model.Ids
import io.github.jrtutts.looseleaf.core.model.ImageStamp
import io.github.jrtutts.looseleaf.core.model.PRect
import io.github.jrtutts.looseleaf.core.model.PageElement
import io.github.jrtutts.looseleaf.core.model.PageEntry
import io.github.jrtutts.looseleaf.core.module.EditorHost
import io.github.jrtutts.looseleaf.core.module.EditorModule
import io.github.jrtutts.looseleaf.core.module.Tool
import io.github.jrtutts.looseleaf.core.ui.OptionHint
import java.io.File

/** Draw or type a signature once, keep it on the device, and place copies of it on pages. */
object SignaturesModule : EditorModule {
    override val id = "signatures"
    override val title = R.string.sign_title
    override val description = R.string.sign_description
    override val icon = Icons.Filled.Draw

    private val tool = object : Tool {
        override val id = "signatures.place"
        override val label = R.string.sign_tool
        override val icon: ImageVector = Icons.Filled.Draw

        // Not a mode: opens the list of signatures, and choosing one puts it on the page.
        override fun onActivated(host: EditorHost): Boolean {
            host.showScreen { SignaturesScreen(host, onPlace = { place(host, it) }) }
            return false
        }
    }

    override val tools = listOf(tool)

    private fun place(host: EditorHost, signature: File) {
        host.closeScreen()
        val hit = host.viewCenter() ?: return
        val page = hit.page
        // The document gets its own copy, so deleting a saved signature later cannot break it.
        val copy = File(host.session.dir, "stamp-${signature.name}")
        if (!copy.exists()) signature.copyTo(copy)
        val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(copy.path, size)
        if (size.outWidth <= 0 || size.outHeight <= 0) return
        val turns = page.uprightTurns
        val w = page.shownWidth * 0.35f
        val h = (w * size.outHeight / size.outWidth).coerceAtMost(page.shownHeight * 0.3f)
        val fittedW = h * size.outWidth / size.outHeight
        val (bw, bh) = if (turns % 2 == 0) fittedW to h else h to fittedW
        val bounds = PRect(hit.point.x - bw / 2f, hit.point.y - bh / 2f, hit.point.x + bw / 2f, hit.point.y + bh / 2f)
            .movedInside(page.bounds)
        val stamp = ImageStamp(Ids.next(), id, bounds, copy.path, turns)
        host.session.edit { it.addElement(page.key, stamp) }
        host.session.selection = stamp.id
    }

    @Composable
    override fun SelectionOptions(host: EditorHost, page: PageEntry, element: PageElement) =
        OptionHint(stringResource(R.string.sign_selected_hint))
}
