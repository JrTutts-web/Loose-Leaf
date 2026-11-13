package io.github.jrtutts.looseleaf.modules.compress

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.jrtutts.looseleaf.R
import io.github.jrtutts.looseleaf.core.module.EditorHost
import io.github.jrtutts.looseleaf.core.module.EditorModule
import io.github.jrtutts.looseleaf.core.module.MenuAction
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

private enum class Level(val title: Int, val text: Int, val maxSide: Int, val quality: Float) {
    LIGHT(R.string.compress_light, R.string.compress_light_text, 2400, 0.80f),
    BALANCED(R.string.compress_balanced, R.string.compress_balanced_text, 1600, 0.60f),
    STRONG(R.string.compress_strong, R.string.compress_strong_text, 1000, 0.40f),
}

private class Result(val level: Level, val file: File, val before: Long, val after: Long, val images: Int)

/** Make a smaller copy of the document by re-encoding its images. */
object CompressModule : EditorModule {
    override val id = "compress"
    override val title = R.string.compress_title
    override val description = R.string.compress_description
    override val icon = Icons.Filled.Compress

    override val menuActions = listOf(
        MenuAction(R.string.compress_menu, Icons.Filled.Compress) { host -> host.showScreen { CompressScreen(host) } },
    )

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun CompressScreen(host: EditorHost) {
        var level by remember { mutableStateOf(Level.BALANCED) }
        var result by remember { mutableStateOf<Result?>(null) }
        val context = host.context
        fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)

        fun run() = host.scope.launch {
            val chosen = level
            try {
                result = host.busy(context.getString(R.string.compress_working)) {
                    // "Before" is the document as it is now, edits included, so the comparison is fair.
                    val plain = File(host.session.dir, "compress-before.pdf")
                    val small = File(host.session.dir, "compress-after.pdf")
                    host.export(plain)
                    var images = 0
                    host.export(small) { doc ->
                        images = ImageRecompressor(doc, chosen.maxSide, chosen.quality).also { it.run() }.replaced
                    }
                    val before = plain.length()
                    plain.delete()
                    Result(chosen, small, before, small.length(), images)
                }
            } catch (e: Exception) {
                host.message(context.getString(R.string.compress_failed, e.message ?: e.javaClass.simpleName))
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.compress_menu)) },
                    navigationIcon = {
                        IconButton(onClick = { host.closeScreen() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(stringResource(R.string.compress_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                for (l in Level.entries) {
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(l == level, role = Role.RadioButton) { level = l; result = null }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = l == level, onClick = null)
                        Column(Modifier.padding(start = 16.dp)) {
                            Text(stringResource(l.title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(l.text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                val r = result?.takeIf { it.level == level }
                if (r == null) {
                    Button(onClick = { run() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.compress_start)) }
                } else {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Row(Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.compress_before), style = MaterialTheme.typography.labelLarge)
                                    Text(size(r.before), style = MaterialTheme.typography.headlineSmall)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.compress_after), style = MaterialTheme.typography.labelLarge)
                                    Text(size(r.after), style = MaterialTheme.typography.headlineSmall)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            val saved = if (r.before > 0) ((1.0 - r.after.toDouble() / r.before) * 100).roundToInt() else 0
                            Text(
                                when {
                                    r.images == 0 -> stringResource(R.string.compress_nothing)
                                    saved <= 0 -> stringResource(R.string.compress_no_gain)
                                    else -> pluralStringResource(R.plurals.compress_saved, r.images, saved, r.images)
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            host.saveCopy(context.getString(R.string.compress_name, host.session.baseName)) { out ->
                                r.file.copyTo(out, overwrite = true)
                            }
                        },
                        Modifier.fillMaxWidth(),
                        enabled = r.after < r.before,
                    ) { Text(stringResource(R.string.compress_save)) }
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(onClick = { host.closeScreen() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.close)) }
                }
            }
        }
    }
}
