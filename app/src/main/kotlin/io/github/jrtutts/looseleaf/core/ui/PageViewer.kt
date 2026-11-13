package io.github.jrtutts.looseleaf.core.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import io.github.jrtutts.looseleaf.core.pdf.PageGeometry
import io.github.jrtutts.looseleaf.core.model.ResizeMode
import io.github.jrtutts.looseleaf.core.render.ElementPainter
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

private data class RenderRequest(val layout: PageLayout, val first: Int, val last: Int)
private data class PatchRequest(
    val layout: PageLayout, val zoom: Float, val x: Float, val y: Float, val w: Int, val h: Int, val busy: Boolean,
)

/**
 * The scrolling, zoomable page view. Everything is drawn on one canvas: page
 * bitmaps from the render engine, then the model's elements on top as vectors,
 * then module overlays and the selection. All one-finger and two-finger
 * gestures are recognised here and handed to the [EditorController].
 */
@Composable
fun PageViewer(ctl: EditorController, modifier: Modifier = Modifier) {
    val vs = ctl.viewer
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Float>()
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    val backdrop = MaterialTheme.colorScheme.surfaceContainerHighest.toArgb()

    LaunchedEffect(ctl, density) {
        ctl.density = density
        ctl.uiScope = scope
        vs.margin = 8f * density
        snapshotFlow { ctl.session.model.pages to vs.viewW }.collect { (pages, _) -> vs.relayout(pages) }
    }

    // Page bitmaps at zoom 1 for the visible pages and their neighbours.
    LaunchedEffect(ctl) {
        snapshotFlow { RenderRequest(vs.layout, vs.firstVisibleIndex(), vs.lastVisibleIndex()) }.collectLatest { req ->
            val slots = req.layout.slots
            if (slots.isEmpty()) { ctl.pageBitmaps.clear(); return@collectLatest }
            val keep = slots.subList(max(0, req.first - 3), minOf(slots.size, req.last + 4)).map { it.page.key }.toSet()
            ctl.pageBitmaps.keys.retainAll(keep)
            val order = (req.first..req.last) + listOf(req.last + 1, req.first - 1)
            for (i in order) {
                val s = slots.getOrNull(i) ?: continue
                val w = s.width.roundToInt().coerceAtLeast(1)
                val have = ctl.pageBitmaps[s.page.key]
                if (have != null && have.rotation == s.page.rotation && have.forWidth == w) continue
                // Very long pages are rendered a little smaller so one page cannot exhaust memory.
                val shrink = minOf(1f, sqrt(12_000_000f / (s.width * s.height)))
                val bmp = Bitmap.createBitmap(
                    (s.width * shrink).roundToInt().coerceAtLeast(1),
                    (s.height * shrink).roundToInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888,
                )
                bmp.eraseColor(Color.WHITE)
                ctl.renderer.render(
                    s.page.source, s.page.index, bmp,
                    PageGeometry.displayMatrix(s.page, s.scale * shrink, 0f, 0f),
                )
                ctl.pageBitmaps[s.page.key] = PageBitmap(bmp, s.page.rotation, w)
            }
        }
    }

    // When zoomed in and at rest, render exactly what is on screen at full sharpness.
    LaunchedEffect(ctl) {
        snapshotFlow { PatchRequest(vs.layout, vs.zoom, vs.scrollX, vs.scrollY, vs.viewW, vs.viewH, ctl.gestureActive) }
            .collectLatest { req ->
                if (req.zoom <= 1.02f) { ctl.patch = null; return@collectLatest }
                if (req.busy || req.w == 0 || req.h == 0) return@collectLatest
                delay(140)
                val bmp = Bitmap.createBitmap(req.w, req.h, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                val white = Paint().apply { color = Color.WHITE }
                for (s in vs.visibleSlots()) {
                    val l = vs.screenLeft(s); val t = vs.screenTop(s)
                    val clip = Rect(
                        l.roundToInt().coerceAtLeast(0), t.roundToInt().coerceAtLeast(0),
                        (l + s.width * req.zoom).roundToInt().coerceAtMost(req.w),
                        (t + s.height * req.zoom).roundToInt().coerceAtMost(req.h),
                    )
                    if (clip.isEmpty) continue
                    canvas.drawRect(clip, white)
                    ctl.renderer.render(s.page.source, s.page.index, bmp, vs.matrix(s), clip)
                }
                ctl.patch = Patch(bmp, req.zoom, req.x, req.y, req.layout.signature)
            }
    }

    val paints = remember { ViewerPaints() }
    val fling = remember { arrayOfNulls<Job>(1) }

    Canvas(
        modifier
            .fillMaxSize()
            .onSizeChanged { vs.setViewSize(it.width, it.height) }
            .pointerInput(ctl) {
                val slop = viewConfiguration.touchSlop
                val longPress = viewConfiguration.longPressTimeoutMillis
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fling[0]?.cancel()
                    down.consume()
                    ctl.gestureActive = true
                    val start = down.position
                    val downTime = down.uptimeMillis
                    var lastTime = downTime
                    var mode: DragMode? = null      // null: not decided yet
                    var longPressed = false
                    var transformed = false
                    val velocity = VelocityTracker()
                    velocity.addPosition(downTime, start)
                    try {
                        while (true) {
                            val undecided = mode == null && !longPressed && !transformed
                            val event = if (undecided) {
                                withTimeoutOrNull((downTime + longPress - lastTime).coerceAtLeast(1)) { awaitPointerEvent() }
                            } else {
                                awaitPointerEvent()
                            }
                            if (event == null) {
                                longPressed = true
                                ctl.onLongPress(start)
                                continue
                            }
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) {
                                event.changes.forEach { it.consume() }
                                break
                            }
                            lastTime = pressed[0].uptimeMillis
                            if (pressed.size >= 2) {
                                // Two fingers always pan and zoom, even in the middle of drawing.
                                mode?.let { ctl.onDragEnd(it, cancelled = true) }
                                mode = null
                                transformed = true
                                val centroid = event.calculateCentroid(useCurrent = false)
                                val pan = event.calculatePan()
                                vs.zoomBy(event.calculateZoom(), centroid.x, centroid.y)
                                vs.scrollBy(-pan.x, -pan.y)
                            } else {
                                val change = pressed[0]
                                val pos = change.position
                                if (transformed) {
                                    val d = change.positionChange()
                                    vs.scrollBy(-d.x, -d.y)
                                } else if (!longPressed) {
                                    if (mode == null && (pos - start).getDistance() > slop) {
                                        mode = ctl.onDragBegin(start)
                                    }
                                    when (mode) {
                                        null -> Unit
                                        DragMode.SCROLL -> {
                                            val d = change.positionChange()
                                            vs.scrollBy(-d.x, -d.y)
                                            velocity.addPosition(change.uptimeMillis, pos)
                                        }
                                        else -> ctl.onDrag(mode, pos)
                                    }
                                }
                            }
                            event.changes.forEach { it.consume() }
                        }
                        when {
                            mode == DragMode.SCROLL -> {
                                val v = velocity.calculateVelocity()
                                fling[0] = scope.launch {
                                    coroutineScope {
                                        launch {
                                            var last = 0f
                                            AnimationState(0f, -v.x).animateDecay(decay) { vs.scrollBy(value - last, 0f); last = value }
                                        }
                                        launch {
                                            var last = 0f
                                            AnimationState(0f, -v.y).animateDecay(decay) { vs.scrollBy(0f, value - last); last = value }
                                        }
                                    }
                                }
                            }
                            mode != null -> ctl.onDragEnd(mode, cancelled = false)
                            !longPressed && !transformed -> ctl.onTap(start, lastTime)
                        }
                        mode = null
                    } finally {
                        mode?.let { ctl.onDragEnd(it, cancelled = true) }
                        ctl.gestureActive = false
                    }
                }
            },
    ) {
        val c = drawContext.canvas.nativeCanvas
        c.drawColor(backdrop)
        val layout = vs.layout
        val zoom = vs.zoom
        val visible = vs.visibleSlots()
        val model = ctl.session.model
        val rect = paints.rect

        for (s in visible) {
            val l = vs.screenLeft(s); val t = vs.screenTop(s)
            rect.set(l, t, l + s.width * zoom, t + s.height * zoom)
            c.drawRect(rect, paints.white)
            val bmp = ctl.pageBitmaps[s.page.key]
            if (bmp != null && bmp.rotation == s.page.rotation) c.drawBitmap(bmp.bitmap, null, rect, paints.bitmap)
        }

        val patch = ctl.patch
        if (patch != null && patch.signature == layout.signature) {
            val k = zoom / patch.zoom
            val l = patch.scrollX * k - vs.scrollX
            val t = patch.scrollY * k - vs.scrollY
            rect.set(l, t, l + patch.bitmap.width * k, t + patch.bitmap.height * k)
            c.drawBitmap(patch.bitmap, null, rect, paints.bitmap)
        }

        val preview = ctl.session.preview
        val modules = ctl.modules
        for (s in visible) {
            // Use the newest version of the page; the layout can be one frame behind the model.
            val page = model.page(s.page.key) ?: s.page
            c.save()
            c.concat(vs.matrix(s, paints.matrix))
            c.clipRect(0f, 0f, page.width, page.height)
            for (e in page.elements) ElementPainter.draw(c, e)
            for (m in modules) m.drawPageOverlay(c, page, ctl)
            if (preview != null && preview.first == page.key) ElementPainter.draw(c, preview.second)
            c.restore()
        }

        // Selection outline and handles.
        val sel = ctl.selected()
        if (sel != null) {
            val (slot, e) = sel
            val m = vs.matrix(slot, paints.matrix)
            val b = e.bounds
            val pad = 3f * density / vs.pixelsPerPoint(slot)
            val corners = floatArrayOf(
                b.left - pad, b.top - pad, b.right + pad, b.top - pad,
                b.right + pad, b.bottom + pad, b.left - pad, b.bottom + pad,
            )
            m.mapPoints(corners)
            val path = paints.path
            path.rewind()
            path.moveTo(corners[0], corners[1])
            for (i in 1..3) path.lineTo(corners[i * 2], corners[i * 2 + 1])
            path.close()
            paints.outline.color = accent
            paints.outline.strokeWidth = 1.5f * density
            // A line shows only its two end handles; a box around it would just be clutter.
            if (e.resize != ResizeMode.ENDS) c.drawPath(path, paints.outline)
            val pts = FloatArray(2)
            paints.handleRing.color = accent
            paints.handleRing.strokeWidth = 2f * density
            for ((_, p) in ctl.handlesOf(e, ctl.handleOutset(slot))) {
                pts[0] = p.x; pts[1] = p.y
                m.mapPoints(pts)
                c.drawCircle(pts[0], pts[1], 7f * density, paints.white)
                c.drawCircle(pts[0], pts[1], 7f * density, paints.handleRing)
            }
        }
    }
}

private class ViewerPaints {
    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    val bitmap = Paint(Paint.FILTER_BITMAP_FLAG)
    val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    val rect = RectF()
    val path = Path()
    val matrix = android.graphics.Matrix()
}
