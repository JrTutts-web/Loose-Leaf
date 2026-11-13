package io.github.jrtutts.looseleaf.modules.signatures

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import io.github.jrtutts.looseleaf.R
import io.github.jrtutts.looseleaf.core.module.EditorHost
import io.github.jrtutts.looseleaf.core.ui.ColorDots
import io.github.jrtutts.looseleaf.core.ui.Palette
import java.io.File

private enum class Mode { LIST, DRAW, TYPE }

private val penColors = Palette.ink.take(2)

/** The bundled handwriting fonts (all SIL Open Font License; see /licenses). */
private val signatureFonts = listOf(R.font.sig_greatvibes, R.font.sig_allura, R.font.sig_sacramento, R.font.sig_kalam)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignaturesScreen(host: EditorHost, onPlace: (File) -> Unit) {
    val context = LocalContext.current
    val store = remember { SignatureStore(context) }
    var mode by remember { mutableStateOf(Mode.LIST) }
    var saved by remember { mutableStateOf(store.list()) }

    fun created(file: File) { saved = store.list(); onPlace(file) }

    BackHandler(enabled = mode != Mode.LIST) { mode = Mode.LIST }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(when (mode) {
                        Mode.LIST -> R.string.sign_screen_title
                        Mode.DRAW -> R.string.sign_draw
                        Mode.TYPE -> R.string.sign_type
                    }))
                },
                navigationIcon = {
                    IconButton(onClick = { if (mode == Mode.LIST) host.closeScreen() else mode = Mode.LIST }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (mode) {
                Mode.LIST -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { mode = Mode.DRAW }, Modifier.weight(1f)) {
                            Icon(Icons.Filled.Draw, null); Spacer(Modifier.padding(4.dp)); Text(stringResource(R.string.sign_draw))
                        }
                        OutlinedButton(onClick = { mode = Mode.TYPE }, Modifier.weight(1f)) {
                            Icon(Icons.Filled.Keyboard, null); Spacer(Modifier.padding(4.dp)); Text(stringResource(R.string.sign_type))
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    if (saved.isEmpty()) {
                        Text(
                            stringResource(R.string.sign_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    } else {
                        Text(
                            stringResource(R.string.sign_pick_hint),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    for (file in saved) {
                        Spacer(Modifier.height(12.dp))
                        SavedSignature(file, onPick = { onPlace(file) }, onDelete = { store.delete(file); saved = store.list() })
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(
                        stringResource(R.string.sign_privacy),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Mode.DRAW -> DrawPad { bmp -> created(store.save(bmp)) }
                Mode.TYPE -> TypePad { bmp -> created(store.save(bmp)) }
            }
        }
    }
}

@Composable
private fun SavedSignature(file: File, onPick: () -> Unit, onDelete: () -> Unit) {
    val bitmap = remember(file) { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .clickable(onClickLabel = stringResource(R.string.sign_place), onClick = onPick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(96.dp).padding(12.dp), contentAlignment = Alignment.Center) {
            if (bitmap != null) Image(bitmap, stringResource(R.string.sign_saved_one))
        }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, stringResource(R.string.sign_delete), tint = Color(0xFF555555)) }
    }
}

@Composable
private fun DrawPad(onSave: (android.graphics.Bitmap) -> Unit) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var color by remember { mutableIntStateOf(penColors[0].argb) }
    val widthPx = with(LocalDensity.current) { 3.dp.toPx() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.sign_draw_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.8f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { current = listOf(it) },
                        onDrag = { change, _ -> change.consume(); current = current + change.position },
                        onDragEnd = { strokes.add(current); current = emptyList() },
                        onDragCancel = { current = emptyList() },
                    )
                }
                .pointerInput(Unit) { detectTapGestures { strokes.add(listOf(it)) } },
        ) {
            // Baseline to sign on.
            drawLine(Color(0xFFCCCCCC), Offset(size.width * 0.06f, size.height * 0.72f), Offset(size.width * 0.94f, size.height * 0.72f), 2f)
            val style = Stroke(widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
            for (s in strokes + listOf(current)) {
                if (s.isEmpty()) continue
                val path = Path()
                path.moveTo(s[0].x, s[0].y)
                if (s.size == 1) path.lineTo(s[0].x + 0.1f, s[0].y)
                for (i in 1 until s.size) path.lineTo(s[i].x, s[i].y)
                drawPath(path, Color(color), style = style)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ColorDots(penColors, color, { color = it })
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { strokes.clear() }, enabled = strokes.isNotEmpty()) { Text(stringResource(R.string.sign_clear)) }
            Button(
                onClick = { SignatureStore.renderStrokes(strokes.toList(), color, widthPx)?.let(onSave) },
                enabled = strokes.isNotEmpty(),
            ) { Text(stringResource(R.string.sign_save_use)) }
        }
    }
}

@Composable
private fun TypePad(onSave: (android.graphics.Bitmap) -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var font by remember { mutableIntStateOf(signatureFonts[0]) }
    var color by remember { mutableIntStateOf(penColors[0].argb) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            label = { Text(stringResource(R.string.sign_name_label)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.sign_style), style = MaterialTheme.typography.titleSmall)
        val sample = name.ifBlank { stringResource(R.string.sign_sample) }
        for (f in signatureFonts) {
            val selected = f == font
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .border(
                        if (selected) 3.dp else 1.dp,
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(12.dp),
                    )
                    .selectable(selected, role = Role.RadioButton) { font = f }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(sample, fontFamily = FontFamily(Font(f)), fontSize = 30.sp, color = Color(color), maxLines = 1)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ColorDots(penColors, color, { color = it })
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    val typeface = ResourcesCompat.getFont(context, font) ?: return@Button
                    SignatureStore.renderText(name.trim(), typeface, color)?.let(onSave)
                },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.sign_save_use)) }
        }
    }
}
