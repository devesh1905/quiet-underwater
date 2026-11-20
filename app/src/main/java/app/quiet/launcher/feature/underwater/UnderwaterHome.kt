@file:OptIn(ExperimentalSharedTransitionApi::class)

package app.quiet.launcher.feature.underwater

import android.app.ActivityOptions
import android.graphics.Rect
import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.scale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlinx.coroutines.launch
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quiet.launcher.container
import app.quiet.launcher.core.model.AppEntry
import app.quiet.launcher.core.model.NowPlaying
import app.quiet.launcher.core.model.UsageToday
import app.quiet.launcher.core.platform.ChargeRate
import app.quiet.launcher.core.platform.Format
import app.quiet.launcher.core.platform.rememberChargeTier
import app.quiet.launcher.core.platform.SystemActions
import app.quiet.launcher.designsystem.*
import app.quiet.launcher.feature.gesture.hapticClock
import app.quiet.launcher.feature.gesture.rememberHapticClockState
import app.quiet.launcher.feature.home.UsageBlock
import app.quiet.launcher.feature.lock.rememberFileBitmap
import app.quiet.launcher.feature.player.NowPlayingCard
import app.quiet.launcher.feature.underwater.engine.GlassBox
import app.quiet.launcher.feature.underwater.engine.GlassStyle
import app.quiet.launcher.feature.underwater.engine.WaterHost
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The world behind the water: today's painting, full bleed, under a soft veil that keeps the type
 * readable (darker at the top for the clock). Before a painting is cached, a deep-water gradient in
 * the painting palette's colours (or a cool default).
 *
 * Depth: the painting sits a little behind the glass. It is overscaled 1.07x and shifts up to 13 dp
 * against the phone's tilt on a soft spring (the engine steps it with the gravity it already reads,
 * so it only redraws when the tilt changes), while the water sloshes the other way: a tank.
 *
 * A new painting: the old one sinks (it darkens, blurs as if seen through more water, and drifts
 * down) while the new one surfaces through the water, landing with a ring, a ripple and a Plunk.
 */
@Composable
fun WaterBackdrop(imagePath: String?, palette: IntArray?, covered: () -> Boolean = { false }, gallery: GalleryHang? = null) {
    val host = app.quiet.launcher.feature.underwater.engine.LocalWater.current
    val bmp = rememberFileBitmap(imagePath, maxSide = 1400)
    val show = remember { Animatable(0f) }
    val reduced by rememberUpdatedState(Q.reducedMotion)
    // The painting on top (cur) and the one sinking away under it (old).
    var cur by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var old by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var curSrc by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var oldSrc by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val sink = remember { Animatable(1f) }
    val rise = remember { Animatable(1f) }
    LaunchedEffect(bmp) {
        val b = bmp ?: return@LaunchedEffect
        if (b === curSrc) return@LaunchedEffect
        val first = cur == null
        val prevSrc = curSrc
        curSrc = b
        if (gallery != null && b.height > 0) gallery.aspect = b.width.toFloat() / b.height
        if (first || host == null || reduced) {
            cur = b.asImageBitmap(); old = null
            if (first) show.animateTo(1f, tween(700))
            return@LaunchedEffect
        }
        old = cur; oldSrc = prevSrc; cur = b.asImageBitmap()
        sink.snapTo(0f); rise.snapTo(0f)
        // The old painting goes down through the water: rings spread as it sinks.
        host.ring(host.w / 2f, host.restLine + 40f * host.dp, 0.8f)
        host.ripple(host.w * 0.3f, host.restLine + 90f * host.dp, 0.9f)
        host.ripple(host.w * 0.72f, host.restLine + 150f * host.dp, 0.8f)
        launch { sink.animateTo(1f, tween(900, easing = androidx.compose.animation.core.FastOutLinearInEasing)) }
        kotlinx.coroutines.delay(260)
        var landed = false
        rise.animateTo(1f, tween(980, easing = androidx.compose.animation.core.LinearOutSlowInEasing)) {
            if (!landed && value > 0.62f) {
                landed = true
                // It breaks the surface: a ring, a splash and a plunk.
                host.ring(host.w / 2f, host.waterY(host.w / 2f), 1f)
                host.ripple(host.w / 2f, host.waterY(host.w / 2f) + 12f * host.dp, 1.3f)
                host.feel(Feel.Plunk)
            }
        }
        old = null; oldSrc = null
    }
    val deep = palette?.getOrNull(0)?.let { Color(it) } ?: Color(0xFF0B1A22)
    val mid = palette?.getOrNull(2)?.let { Color(it) } ?: Color(0xFF1E3B47)
    val density = LocalDensity.current
    val shift = with(density) { 13.dp.toPx() }
    val drift = with(density) { 110.dp.toPx() }
    val surfaceFrom = with(density) { 80.dp.toPx() }
    val blurMax = with(density) { 16.dp.toPx() }
    val deepBrush = remember(deep, mid) {
        Brush.verticalGradient(0f to Color(0xFF050708), 0.45f to lerpC(deep, Color.Black, 0.35f), 1f to lerpC(mid, Color.Black, 0.55f))
    }
    // At rest (one painting, fully shown, nothing surfacing) the painting and its veil are one draw:
    // the bitmap through a shader with the same crop, overscale and tilt shift as the Image layer, the
    // veil composed over it in the shader. One full-screen pass a frame instead of two.
    val atRest: () -> Boolean = {
        cur != null && old == null && show.value >= 1f && rise.value >= 1f &&
            app.quiet.launcher.feature.underwater.engine.WaterDebug.hide and 3 == 0
    }
    val restPaint = remember { RestPainting() }
    // Hung on the gallery wall (Home in front): how far, 0 = the full-bleed backdrop as before.
    val hang: () -> Float = { if (gallery == null || gallery.art.isEmpty) 0f else smooth(gallery.front().coerceIn(0f, 1f)) }
    val hangPaint = remember { HangPainter() }
    val wallColor = remember(deep) { lerpC(deep, Color(0xFF0A0908), 0.86f).let { android.graphics.Color.rgb(it.red, it.green, it.blue) } }
    val placeholder = remember(deep, mid) { lerpC(mid, Color.Black, 0.45f).let { android.graphics.Color.rgb(it.red, it.green, it.blue) } }
    Box(
        Modifier.fillMaxSize()
            // [covered]: something opaque (the player) is over all of it: draw nothing.
            .drawWithContent { if (!covered()) drawContent() }
            .drawBehind {
                val e = hang()
                if (e > 0f && gallery != null) {
                    // The gallery: the wall, the picture light, the framed painting (the old one sinking
                    // into the water, the new one rising out of it), opening out to full bleed as e -> 0.
                    var tx = 0f; var ty = 0f
                    if (host != null) { host.parTick; tx = host.parX * shift; ty = host.parY * shift }
                    val lx = tx * HANG_LEAN; val ly = ty * HANG_LEAN
                    val a0 = gallery.art
                    val dpx = density.density
                    val sc = coverScale(size.width, size.height, tx, ty, OVERSCALE)
                    drawIntoCanvas { cv ->
                        val nc = cv.nativeCanvas
                        hangPaint.wall(nc, size.width, size.height, wallColor, a0, lx, ly, dpx, e)
                        val os = oldSrc
                        if (old != null && os != null) {
                            val k = sink.value
                            hangPaint.painting(nc, 1, os, size.width, size.height, a0.translate(lx, ly + drift * k * k), e, 1f - smooth(k), 0.6f * k, tx, ty + drift * k * k, sc, dpx, placeholder)
                        }
                        val k = 1f - rise.value
                        val a = if (cur == null) 1f else show.value * (1f - smooth(k))
                        hangPaint.painting(nc, 0, curSrc.takeIf { cur != null }, size.width, size.height, a0.translate(lx, ly + surfaceFrom * k), e, a, 0f, tx, ty + surfaceFrom * k, sc, dpx, placeholder)
                    }
                    if (e < 1f) drawRect(VeilBrush, alpha = 1f - e)
                    return@drawBehind
                }
                // The deep-water gradient only shows where the painting doesn't cover: before it is
                // cached, while it fades in, and while a new one surfaces. Under an opaque painting at
                // rest it is skipped (a full-screen fill every water frame).
                val src = curSrc
                val rest = atRest()
                val opaque = rest && src != null && !src.hasAlpha()
                if (!opaque) drawRect(deepBrush)
                val c = cur
                if (rest && src != null && c != null) {
                    var tx = 0f; var ty = 0f; var sc = 1f
                    if (host != null) { host.parTick; tx = host.parX * shift; ty = host.parY * shift; sc = coverScale(size.width, size.height, tx, ty, OVERSCALE) }
                    drawIntoCanvas { restPaint.draw(it.nativeCanvas, src, size.width, size.height, tx, ty, sc) }
                }
            }
    ) {
        // No painting yet: soft pools of light, so the water still has something to bend.
        if (cur == null) androidx.compose.foundation.Canvas(Modifier.fillMaxSize().drawWithContent { if (hang() == 0f) drawContent() }) {
            val lt = palette?.getOrNull(3)?.let { Color(it) } ?: Color(0xFF9FD3E0)
            val ac = palette?.getOrNull(1)?.let { Color(it) } ?: Color(0xFF3F7F9A)
            drawCircle(Brush.radialGradient(listOf(ac.copy(alpha = 0.28f), Color.Transparent), center = Offset(size.width * 0.15f, size.height * 0.62f), radius = size.width * 0.8f), size.width * 0.8f, Offset(size.width * 0.15f, size.height * 0.62f))
            drawCircle(Brush.radialGradient(listOf(lt.copy(alpha = 0.16f), Color.Transparent), center = Offset(size.width * 0.9f, size.height * 0.85f), radius = size.width * 0.6f), size.width * 0.6f, Offset(size.width * 0.9f, size.height * 0.85f))
            // Faint shafts of light slanting down into the water.
            for (i in 0 until 7) {
                val x0 = size.width * (0.08f + i * 0.15f)
                drawLine(lt.copy(alpha = 0.035f + 0.02f * (i % 3)), Offset(x0, 0f), Offset(x0 - size.width * 0.25f, size.height), strokeWidth = size.width * (0.02f + 0.012f * (i % 2)))
            }
        }
        val o = old
        if (o != null) {
            Image(
                o, null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
                    .drawWithContent { if (hang() == 0f) drawContent() }
                    .graphicsLayer {
                        val k = sink.value
                        var tx = 0f; var ty = 0f
                        if (host != null) { host.parTick; tx = host.parX * shift; ty = host.parY * shift }
                        ty += drift * k * k
                        cover(tx, ty, OVERSCALE + 0.04f * k)
                        alpha = 1f - smooth(k)
                        val br = blurMax * k
                        renderEffect = if (br > 0.5f) androidx.compose.ui.graphics.BlurEffect(br, br) else null
                    }
                    .drawWithContent { drawContent(); drawRect(Color.Black.copy(alpha = 0.6f * sink.value)) },
            )
        }
        val c = cur
        if (c != null && app.quiet.launcher.feature.underwater.engine.WaterDebug.hide and 1 == 0) {
            Image(
                c, null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().drawWithContent { if (!atRest() && hang() == 0f) drawContent() }.graphicsLayer {
                    val k = 1f - rise.value
                    alpha = show.value * (1f - smooth(k))
                    if (host != null) {
                        host.parTick
                        cover(host.parX * shift, host.parY * shift + surfaceFrom * k, OVERSCALE * (1f + 0.04f * k))
                    }
                    val br = blurMax * 0.85f * k
                    renderEffect = if (br > 0.5f) androidx.compose.ui.graphics.BlurEffect(br, br) else null
                },
            )
        }
        if (app.quiet.launcher.feature.underwater.engine.WaterDebug.hide and 2 == 0) Box(
            Modifier.fillMaxSize().drawWithContent { if (!atRest() && hang() == 0f) drawContent() }.background(VeilBrush)
        )
    }
}

/** A soft dark halo under small type that sits over the water: it reads on any painting, never a hard drop shadow. */
internal val WaterTextShadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 1f), 8f)

/** How much bigger than the screen the painting is drawn, so the tilt shift (up to ~13 dp) has room. */
private const val OVERSCALE = 1.07f

/** The veil over the painting (darker at the top for the clock). */
private val VeilStops = floatArrayOf(0f, 0.22f, 0.5f, 1f)
private val VeilColors = intArrayOf(0xCC000000.toInt(), 0x8A000000.toInt(), 0x66000000, 0x9E000000.toInt())
private val VeilBrush = Brush.verticalGradient(*Array(4) { VeilStops[it] to Color(VeilColors[it]) })

/**
 * The painting at rest and its veil in one draw. The bitmap is mapped exactly as the Image draws it
 * (ContentScale.Crop, centred) and then as its layer moves it (scale [sc] about the centre, then
 * shifted by tx, ty); the veil gradient is screen-fixed and composed over it (src-over) in the shader.
 */
private class RestPainting {
    private var bmp: android.graphics.Bitmap? = null
    private var bs: android.graphics.BitmapShader? = null
    private var veilH = -1f
    private var shader: android.graphics.Shader? = null
    private val m = android.graphics.Matrix()
    private val paint = android.graphics.Paint().apply { isFilterBitmap = true; isDither = true }

    fun draw(c: android.graphics.Canvas, b: android.graphics.Bitmap, w: Float, h: Float, tx: Float, ty: Float, sc: Float) {
        if (b !== bmp || h != veilH) {
            val s = android.graphics.BitmapShader(b, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
            if (android.os.Build.VERSION.SDK_INT >= 33) s.filterMode = android.graphics.BitmapShader.FILTER_MODE_LINEAR
            val veil = android.graphics.LinearGradient(0f, 0f, 0f, h, VeilColors, VeilStops, android.graphics.Shader.TileMode.CLAMP)
            bmp = b; bs = s; veilH = h
            shader = android.graphics.ComposeShader(s, veil, android.graphics.PorterDuff.Mode.SRC_OVER)
        }
        val bw = b.width.toFloat(); val bh = b.height.toFloat()
        val k = maxOf(w / bw, h / bh)
        m.setScale(k, k)
        m.postTranslate(((w - bw * k) / 2f).let { kotlin.math.round(it) }, ((h - bh * k) / 2f).let { kotlin.math.round(it) })
        m.postScale(sc, sc, w / 2f, h / 2f)
        m.postTranslate(tx, ty)
        bs!!.setLocalMatrix(m)
        paint.shader = shader
        c.drawRect(0f, 0f, w, h, paint)
    }
}

/** The scale [cover] gives for a shift ([tx], [ty]) at [sc] on a [w] x [h] box. */
private fun coverScale(w: Float, h: Float, tx: Float, ty: Float, sc: Float): Float =
    if (w > 0f && h > 0f) maxOf(sc, 1f + 2f * (abs(tx) + 1f) / w, 1f + 2f * (abs(ty) + 1f) / h) else sc

/**
 * Moves the painting by ([tx], [ty]) at scale [sc] (about its centre) and never lets an edge show:
 * the scale grows just enough to cover the shift (the Image is clipped to its bounds before this
 * layer scales it, so only the overscale itself is margin). At rest and for ordinary tilts that is
 * [sc] itself; only a hard tilt plus the sinking/surfacing drift zooms a hair further.
 */
private fun androidx.compose.ui.graphics.GraphicsLayerScope.cover(tx: Float, ty: Float, sc: Float) {
    val w = size.width; val h = size.height
    val s = if (w > 0f && h > 0f) maxOf(sc, 1f + 2f * (abs(tx) + 1f) / w, 1f + 2f * (abs(ty) + 1f) / h) else sc
    scaleX = s; scaleY = s
    translationX = tx; translationY = ty
}

private fun lerpC(a: Color, b: Color, t: Float) = Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f)

/**
 * Starts [app] out of a splash: a clink as the glass is touched, a drop falls from the name into the
 * water (~190 ms), lands with a plunk and the app opens out of the splash (a clip reveal from it).
 * The launch never waits more than ~420 ms: a failsafe fires it if the drop never lands.
 */
fun launchWithDrop(host: WaterHost, view: View, app: AppEntry, from: Offset, holdMs: Long = 0L) {
    host.feel(Feel.Clink)
    val t0 = android.os.SystemClock.uptimeMillis()
    var done = false
    var go: (Float, Float) -> Unit = { _, _ -> }
    go = go@{ x: Float, y: Float ->
        if (done) return@go
        // Held at least [holdMs] (the tap's splash and the drop are seen first), however fast it lands.
        val wait = holdMs - (android.os.SystemClock.uptimeMillis() - t0)
        if (wait > 8L) { view.postDelayed({ go(x, y) }, wait); return@go }
        done = true
        LaunchEcho.at(x, y)
        val p = host.toWindow(Offset(x, y))
        val s = (48 * host.dp).toInt()
        val inWindow = IntArray(2).also { view.getLocationInWindow(it) }
        val onScreen = IntArray(2).also { view.getLocationOnScreen(it) }
        val l = p.x.toInt() - s / 2; val t = p.y.toInt() - s / 2
        val opts = runCatching { ActivityOptions.makeClipRevealAnimation(view, l - inWindow[0], t - inWindow[1], s, s).toBundle() }.getOrNull()
        val dx = onScreen[0] - inWindow[0]; val dy = onScreen[1] - inWindow[1]
        view.context.container.apps.launch(app.key, Rect(l + dx, t + dy, l + dx + s, t + dy + s), opts)
        android.util.Log.d("QuietWater", "drop launch after ${android.os.SystemClock.uptimeMillis() - t0} ms")
    }
    host.drop(from.x, from.y, go)
    // With a hold the launch comes at the hold (the drop is still falling, already seen); else the failsafe.
    view.postDelayed({ go(from.x, host.waterY(from.x)) }, if (holdMs > 0L) holdMs else 420L)
}

/** Where the last drop launched an app: back on Home the water there is still settling. */
internal object LaunchEcho {
    var x = 0f; var y = 0f
    var pending = false
    var at = 0L
    fun at(x: Float, y: Float) { this.x = x; this.y = y; pending = true; at = android.os.SystemClock.uptimeMillis() }
}

/**
 * Home in this edition: no chrome at rest. Today's painting hangs framed on a dark gallery wall (the
 * backdrop draws it where this lays it out, see [GalleryHang]) and the water floods the room over the
 * lower part of the frame. The favourites are words written straight on the water, the waterline
 * crossing the list: names under it bend and waver, names above stay crisp.
 */
@Composable
fun UnderwaterHomeContent(
    host: WaterHost,
    minute: Long, is24h: Boolean, batteryLevel: Float, charging: Boolean,
    usage: UsageToday, usagePerm: Boolean, showUsage: Boolean, favourites: List<AppEntry>, oneThing: String,
    nowPlaying: NowPlaying?, playerOpen: Boolean,
    onOpenPlayer: () -> Unit, onCardLongPress: () -> Unit, onBackgroundLongPress: () -> Unit,
    onOpenUsage: () -> Unit,
    onAppLongPress: (AppEntry) -> Unit,
    swipeUp: Boolean, swipeDown: Boolean,
    sharedScope: SharedTransitionScope,
    contentScope: AnimatedVisibilityScope? = null,
    onClockLongPress: () -> Unit = {},
    hapticClockOn: Boolean = false,
    /** False while Home is not in front (another page, the player): bubbles free their glass, folders close. */
    active: Boolean = true,
) {
    val ctx = LocalContext.current
    val view = LocalView.current
    // Favourites folded into folders (underwater only), the dock's drag/folder state, the bubbles.
    val dockFolders = remember { DockFolders.get(ctx) }
    val folderList by dockFolders.folders.collectAsState()
    val items = remember(favourites, folderList) { dockFolders.items(favourites, folderList) }
    val dock = rememberDockState(host, onAppLongPress)
    dock.items = items
    LaunchedEffect(active, playerOpen) { if (!active || playerOpen) dock.reset() }
    val gallery = LocalGallery.current
    val art by ctx.container.art.today.collectAsStateWithLifecycle()
    val c = Q.colors
    val density = LocalDensity.current
    val clock = rememberHapticClockState(Q.reducedMotion)
    val scope = rememberCoroutineScope()
    val boxCoords = remember { arrayOfNulls<LayoutCoordinates>(1) }

    /**
     * A long press on empty Home (not the list, a bubble or words: those take their own touches)
     * brings another painting: Clink, a ring where the finger is, and the backdrop sinks the old
     * one and surfaces the new. The music menu belongs to the music card alone (its own long press).
     */
    fun backgroundLongPress(p: Offset) {
        val k = boxCoords[0]
        val w = if (k != null && k.isAttached) host.toWorld(k, p) else p
        val pad = 8f * host.dp
        val onDock = w.x in (dock.dockLeft - pad)..(dock.dockRight + pad) && w.y in (dock.slabTop - pad)..(dock.slabBottom + pad)
        if (dock.focused || onDock) return
        host.feel(Feel.Clink)
        host.ring(w.x, w.y, 0.7f)
        host.ripple(w.x, w.y, 1.1f)
        scope.launch { runCatching { ctx.container.art.skip() } }
    }

    // ---- the first look after an unlock: one gentle ripple across the whole screen from the bottom.
    LaunchedEffect(Unit) { UnlockWatch.ensure(ctx) }
    val resumed = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateAsState().value
        .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    val present = UnlockWatch.present
    LaunchedEffect(resumed, active, playerOpen, present) {
        if (!resumed || !active || playerOpen || !UnlockWatch.armed) return@LaunchedEffect
        if (ctx.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true) return@LaunchedEffect
        // Let the unlock animation land (and the water measure itself) first.
        kotlinx.coroutines.delay(240)
        var n = 0
        while (host.w <= 0f && n++ < 20) kotlinx.coroutines.delay(50)
        if (!UnlockWatch.armed || host.w <= 0f) return@LaunchedEffect
        UnlockWatch.armed = false
        LaunchEcho.pending = false
        host.liven()
        host.ring(host.w / 2f, host.h - 4f * host.dp, 0.8f, fade = 0.42f)
        host.ripple(host.w / 2f, host.h - 40f * host.dp, 1.1f)
    }

    // Back from an app opened by a drop: the water where it fell is still settling (the grid kept its
    // state while Home was away; a soft remnant of the splash makes it plain the water moved).
    LaunchedEffect(resumed, active) {
        if (!resumed || !active || !LaunchEcho.pending) return@LaunchedEffect
        LaunchEcho.pending = false
        if (android.os.SystemClock.uptimeMillis() - LaunchEcho.at > 10 * 60_000L) return@LaunchedEffect
        kotlinx.coroutines.delay(90)
        host.liven()
        host.ripple(LaunchEcho.x, LaunchEcho.y + 6f * host.dp, 0.75f)
        host.ring(LaunchEcho.x, LaunchEcho.y, 0.32f, fade = 0.9f)
    }

    // Measured (px, this box): where the world text ends, how tall the box and the bottom stack are.
    var boxTopWorld by remember { mutableFloatStateOf(0f) }
    var boxH by remember { mutableIntStateOf(0) }
    var boxW by remember { mutableIntStateOf(0) }
    var textBottom by remember { mutableIntStateOf(0) }
    var bottomH by remember { mutableIntStateOf(0) }
    var museumH by remember { mutableIntStateOf(0) }
    val rowH = 42.dp
    val gap = 18.dp
    val rowPx = with(density) { rowH.toPx() }
    val gapPx = with(density) { gap.toPx() }
    val dpx = density.density
    val tier by rememberChargeTier(charging, batteryLevel)
    // Notification bubbles rest in a row ON the list's top edge: while there are any, the list keeps
    // that row clear under the museum label. The whole row is theirs (the battery is up by the date).
    LaunchedEffect(dock) { dock.shelfRight = Float.NaN }
    val reserveWant = if (dock.shelf <= 0f) gapPx else maxOf(gapPx, dock.shelf + 4f * host.dp)
    val reserve by androidx.compose.animation.core.animateFloatAsState(reserveWant, spring(0.82f, 260f), label = "shelf")
    val ready = boxH > 0 && textBottom > 0
    val slabLaid by remember { derivedStateOf { dock.slabTop >= 0f } }

    // Where this box sits in the world at rest (the pager moves it while swiping; this never does).
    val layoutDir = androidx.compose.ui.platform.LocalLayoutDirection.current
    val originX = WindowInsets.systemBars.getLeft(density, layoutDir) + 24f * dpx
    val originY = WindowInsets.systemBars.getTop(density).toFloat()

    // ---- a gallery wall, top to bottom: the words; the framed painting, centred, its museum label
    // right under it; then the favourites, on the water. Nothing is written on the canvas. The
    // water stands across the lower part of the frame, so the label and the list are under it.
    val fw = FRAME_W.value * dpx
    // Room for the label: a fixed block (one line each), so the layout never chases its own measurement.
    val labelBlock = if (art != null) maxOf(museumH.toFloat(), 52f * dpx) + 10f * dpx else 0f
    val top0 = textBottom + 18f * dpx
    val bottom0 = boxH - bottomH - 8f * dpx
    val minFrame = 120f * dpx
    // Every favourite keeps its place (up to seven): resting bubbles take their row from the painting
    // first (it eases a little smaller with the shelf), and only below its smallest size from the list.
    val nRows = items.size.coerceIn(1, 7)
    val aspect = gallery?.aspect?.takeIf { it > 0f } ?: 0.8f
    val maxW = boxW * 0.84f - 2f * fw
    val maxH = (bottom0 - top0 - labelBlock - reserve - nRows * rowPx - 2f * fw).coerceIn(minFrame, maxOf(minFrame, minOf(boxW * 0.92f, 260f * dpx)))
    var iw = maxW
    var ih = iw / aspect
    if (ih > maxH) { ih = maxH; iw = ih * aspect }
    // A very wide painting would hang as a thin strip: it crops its sides a little instead.
    if (ih < iw * 0.6f) ih = minOf(maxH, iw * 0.6f)
    ih = ih.coerceAtLeast(0f)
    val group = ih + 2f * fw + labelBlock + reserve + nRows * rowPx
    // Any spare height goes mostly below the list (it reads as more water), a little above the frame.
    val start = top0 + maxOf(0f, bottom0 - top0 - group) * 0.3f
    val innerLeft = (boxW - iw) / 2f
    val innerTop = start + fw
    val frameBottom = innerTop + ih + fw
    val hangs = gallery != null && ready && boxW > 0 && maxH > 100f * dpx && maxW > 120f * dpx
    val artWorld = if (hangs) androidx.compose.ui.geometry.Rect(originX + innerLeft, originY + innerTop, originX + innerLeft + iw, originY + innerTop + ih) else androidx.compose.ui.geometry.Rect.Zero
    SideEffect {
        if (gallery != null && gallery.art != artWorld) gallery.art = artWorld
        // The waterline: fixed by this layout (never the battery), 70% down the frame.
        if (hangs) host.homeLine = originY + start + 0.7f * (ih + 2f * fw)
    }
    DisposableEffect(gallery) { onDispose { gallery?.art = androidx.compose.ui.geometry.Rect.Zero } }
    val listTopWant = if (hangs) frameBottom + labelBlock else top0
    // The rows that fit under the label (and the bubbles' row, when they rest there).
    val fit = if (boxH == 0) 5 else ((bottom0 - listTopWant - reserveWant) / rowPx).toInt().coerceIn(1, 7)
    val listBottomMax = bottom0
    val leanPx = 13f * dpx * HANG_LEAN

    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 24.dp)
            .onGloballyPositioned { boxCoords[0] = it; boxTopWorld = host.toWorld(it).y; boxH = it.size.height; boxW = it.size.width }
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { p -> backgroundLongPress(p) }, onDoubleTap = {
                    if (ctx.container.settings.settings.value.doubleTapLock &&
                        !app.quiet.launcher.feature.focus.LockController.lockNow(ctx)) {
                        app.quiet.launcher.core.platform.Permissions.launch(ctx, app.quiet.launcher.feature.focus.LockController.adminIntent(ctx))
                    }
                })
            }
            .pointerInput(swipeUp, swipeDown) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onVerticalDrag = { _, dy -> total += dy },
                    onDragEnd = {
                        if (total < -60.dp.toPx() && swipeUp) SystemActions.googleSearch(ctx)
                        else if (total > 60.dp.toPx() && swipeDown) SystemActions.expandNotifications(ctx)
                    },
                )
            },
    ) {
        // ---- on the wall above the painting: clock, date, the day. While a card is up (an open
        // folder, a notification) the words fade back: nothing reads through it.
        Column(Modifier.padding(horizontal = 8.dp).onSizeChanged { textBottom = it.height }.graphicsLayer { alpha = 1f - 0.9f * dock.dim.value }) {
            Spacer(Modifier.height(18.dp))
            BasicText(
                Format.clock(minute, is24h),
                style = QType.clock(96.sp).copy(color = c.ink),
                modifier = Modifier
                    .then(
                        if (contentScope != null) with(sharedScope) {
                            Modifier.sharedBounds(
                                rememberSharedContentState(app.quiet.launcher.feature.quick.ClockKey), contentScope,
                                boundsTransform = { _, _ -> spring(dampingRatio = 0.85f, stiffness = 300f) },
                                resizeMode = SharedTransitionScope.ResizeMode.ScaleToBounds(),
                            )
                        } else Modifier
                    )
                    .then(
                        if (hapticClockOn) Modifier.hapticClock(clock, c.ink, c.edge) { SystemActions.alarms(ctx) }
                        else Modifier.pressable(pressedScale = 0.99f, pressedAlpha = 0.7f, onLongPress = onClockLongPress) { SystemActions.alarms(ctx) }
                    ),
            )
            Spacer(Modifier.height(10.dp))
            // The date, and the battery at the other end of its line (on the wall, clear of the painting).
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BasicText(Format.longDate(minute), style = QType.body(17.sp).copy(color = c.muted, shadow = WaterTextShadow), modifier = Modifier.weight(1f), maxLines = 1)
                BatteryLine(tier, batteryLevel)
            }
            if (oneThing.isNotBlank()) {
                Spacer(Modifier.height(24.dp))
                BasicText(oneThing, style = QType.question(22.sp).copy(color = c.ink, shadow = WaterTextShadow), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (showUsage) {
                Spacer(Modifier.height(14.dp))
                if (usagePerm) UsageBlock(usage, compact = true, onClick = onOpenUsage, shadow = WaterTextShadow)
                else BasicText(
                    "Allow usage access to see your day here.",
                    style = QType.secondary(15.sp).copy(color = c.muted, shadow = WaterTextShadow),
                    modifier = Modifier.pressable {
                        app.quiet.launcher.core.platform.Permissions.launch(ctx, app.quiet.launcher.core.platform.Permissions.usageAccessIntent(ctx))
                    },
                )
            }
        }

        // ---- the painting's museum label, right under the frame (under the water: it wavers with
        // it). It leans with the painting.
        val a = art
        if (a != null && hangs) {
            Box(
                Modifier
                    .offset { IntOffset((innerLeft - fw).roundToInt(), (frameBottom + 12f * dpx).roundToInt()) }
                    .width(with(density) { (iw + 2f * fw).toDp() })
                    .onSizeChanged { museumH = it.height }
                    .graphicsLayer {
                        host.parTick
                        translationX = host.parX * leanPx; translationY = host.parY * leanPx
                        alpha = 1f - 0.9f * dock.dim.value
                    },
            ) {
                MuseumLabel(
                    title = a.title, line2 = a.artist,
                    line3 = listOfNotNull(a.year, a.museum).joinToString(", ").ifBlank { null },
                    titleStyle = QType.labelTitle(14.sp).copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, lineHeight = 18.sp, shadow = WaterTextShadow),
                    line2Style = QType.secondary(12.sp).copy(shadow = WaterTextShadow),
                    line3Style = QType.small(11.sp).copy(shadow = WaterTextShadow),
                    ink = c.ink.copy(alpha = 0.92f), muted = c.muted,
                    gap = 1.dp, titleLines = 1,
                )
            }
        }

        // ---- the favourites: names written on the water under the painting, no card.
        Box(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, minOf(listTopWant + reserve, listBottomMax - items.take(fit).size * rowPx).coerceAtLeast(listTopWant).roundToInt()) }
                .onGloballyPositioned {
                    val p = host.toWorld(it)
                    dock.dockLeft = p.x; dock.dockRight = p.x + it.size.width
                    dock.slabBottom = p.y + it.size.height
                    if (abs(p.y - dock.slabTop) > 1f) dock.slabTop = p.y
                },
        ) {
            Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp)) {
                items.take(7).forEachIndexed { i, item ->
                    key(item.key) {
                        if (!ready) { if (i < fit) DockRow(dock, view, item, rowH) }
                        else AnimatedVisibility(
                            visible = i < fit,
                            enter = fadeIn(tween(260, 120)) + expandVertically(spring(0.86f, 320f), expandFrom = Alignment.Top),
                            exit = fadeOut(tween(140)) + shrinkVertically(spring(0.86f, 320f), shrinkTowards = Alignment.Top),
                        ) { DockRow(dock, view, item, rowH) }
                    }
                }
                if (favourites.isEmpty()) {
                    Box(Modifier.height(rowH), contentAlignment = Alignment.CenterStart) {
                        BasicText("Long press any app in the list to add it here.", style = QType.secondary(15.sp).copy(color = c.muted, shadow = WaterTextShadow))
                    }
                }
            }
        }

        // ---- bottom: now playing (when music plays) over two small glyphs riding the water.
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { bottomH = it.height }) {
            AnimatedVisibility(
                visible = nowPlaying != null,
                enter = fadeIn(tween(360, 120)) + expandVertically(tween(360)),
                exit = fadeOut(tween(160)) + shrinkVertically(tween(260)),
            ) {
                val np = nowPlaying
                if (np != null) {
                    with(sharedScope) {
                        AnimatedVisibility(visible = !playerOpen, enter = fadeIn(tween(200, 200)), exit = fadeOut(tween(1))) {
                            // The cover card floats on the water: clear glass that bobs and rolls with
                            // the local wave and bends the light at its edge; the card rides it.
                            GlassBox(
                                style = GlassStyle(corner = 24.dp, bevel = 14.dp, floats = true, stir = true, stiffness = 300f, damping = 0.52f, clear = true),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                            ) {
                                NowPlayingCard(
                                    np = np, visibility = this, opening = playerOpen,
                                    onOpen = onOpenPlayer, onPlayPause = { ctx.container.nowPlaying.playPause() },
                                    onLongPress = onCardLongPress,
                                    modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                                )
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                WaterGlyph(host, QIcon.Phone) { SystemActions.dialer(ctx) }
                Spacer(Modifier.weight(1f))
                WaterGlyph(host, QIcon.Camera) { SystemActions.camera(ctx) }
            }
        }

        // ---- above everything in the world: notification bubbles beading on the list, the label of
        // a lifted drop and an open folder's card. Only once the list is really laid out.
        if (slabLaid && boxH > 0 && textBottom > 0) BubbleLayer(
            dock = dock, active = active && !playerOpen,
            reduced = Q.reducedMotion, modifier = Modifier.matchParentSize(),
        )
        DockOverlay(dock, view, Modifier.matchParentSize())
    }
}

/**
 * The battery on the date's line: the charge state from the measured rate ("Fast charging · 62%"),
 * just "62%" unplugged. A new state crossfades in (the old fades up and away, the new rises in).
 */
@Composable
private fun BatteryLine(tier: ChargeRate.Tier, batteryLevel: Float) {
    val c = Q.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        val style = QType.secondary(13.sp).copy(color = c.muted, shadow = WaterTextShadow)
        AnimatedContent(
            targetState = tier.label,
            transitionSpec = {
                (fadeIn(tween(520, 140, QMotion.EaseOutQuiet)) + slideInVertically(tween(620, 140, QMotion.EaseOutQuiet)) { it / 3 }) togetherWith
                    (fadeOut(tween(260)) + slideOutVertically(tween(360)) { -it / 4 }) using
                    SizeTransform(clip = false) { _, _ -> spring(0.9f, 300f) }
            },
            contentAlignment = Alignment.CenterEnd,
            label = "charge",
        ) { label ->
            if (label.isNotEmpty()) BasicText("$label · ", style = style.copy(color = if (label == ChargeRate.Tier.FULL.label) c.ink.copy(alpha = 0.86f) else c.muted))
        }
        BasicText(app.quiet.launcher.core.platform.HyperCharge.percent((batteryLevel * 100).toInt(), tier.charging), style = style)
    }
}

/** A small plain glyph on the water (no glass): it rises and falls a little as the swell passes over. */
@Composable
private fun WaterGlyph(host: WaterHost, icon: QIcon, onClick: () -> Unit) {
    val at = remember { floatArrayOf(Float.NaN) }
    Box(
        Modifier
            .size(52.dp)
            .onGloballyPositioned { at[0] = host.toWorld(it, Offset(it.size.width / 2f, it.size.height / 2f)).x }
            .graphicsLayer {
                host.tick
                val x = at[0]
                if (!x.isNaN() && host.restLine > 0f) translationY = ((host.waterY(x) - host.restLine) * 0.7f).coerceIn(-8f * host.dp, 8f * host.dp)
            }
            .drawBehind {
                drawCircle(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.30f), Color.Transparent), center = center, radius = size.minDimension * 0.6f), size.minDimension * 0.6f)
            }
            .pressable { host.feel(Feel.Clink); onClick() },
        contentAlignment = Alignment.Center,
    ) { QIconView(icon, Q.colors.ink.copy(alpha = 0.92f), size = 22.dp, stroke = 1.4f) }
}
