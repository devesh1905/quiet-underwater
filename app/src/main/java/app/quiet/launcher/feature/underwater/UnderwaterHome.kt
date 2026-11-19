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
fun WaterBackdrop(imagePath: String?, palette: IntArray?, covered: () -> Boolean = { false }) {
    val host = app.quiet.launcher.feature.underwater.engine.LocalWater.current
    val bmp = rememberFileBitmap(imagePath, maxSide = 1400)
    val show = remember { Animatable(0f) }
    val reduced by rememberUpdatedState(Q.reducedMotion)
    // The painting on top (cur) and the one sinking away under it (old).
    var cur by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var old by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var curSrc by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val sink = remember { Animatable(1f) }
    val rise = remember { Animatable(1f) }
    LaunchedEffect(bmp) {
        val b = bmp ?: return@LaunchedEffect
        if (b === curSrc) return@LaunchedEffect
        val first = cur == null
        curSrc = b
        if (first || host == null || reduced) {
            cur = b.asImageBitmap(); old = null
            if (first) show.animateTo(1f, tween(700))
            return@LaunchedEffect
        }
        old = cur; cur = b.asImageBitmap()
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
        old = null
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
    Box(
        Modifier.fillMaxSize()
            // [covered]: something opaque (the player) is over all of it: draw nothing.
            .drawWithContent { if (!covered()) drawContent() }
            .drawBehind {
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
        if (cur == null) androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
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
                modifier = Modifier.fillMaxSize().drawWithContent { if (!atRest()) drawContent() }.graphicsLayer {
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
            Modifier.fillMaxSize().drawWithContent { if (!atRest()) drawContent() }.background(VeilBrush)
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
fun launchWithDrop(host: WaterHost, view: View, app: AppEntry, from: Offset) {
    host.feel(Feel.Clink)
    val t0 = android.os.SystemClock.uptimeMillis()
    var done = false
    val go = go@{ x: Float, y: Float ->
        if (done) return@go
        done = true
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
    view.postDelayed({ go(from.x, host.waterY(from.x)) }, 420)
}

/** Home with water and glass: the same content as classic Home, laid out around the waterline. */
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
    val c = Q.colors
    val density = LocalDensity.current
    val clock = rememberHapticClockState(Q.reducedMotion)
    val scope = rememberCoroutineScope()
    val boxCoords = remember { arrayOfNulls<LayoutCoordinates>(1) }

    /**
     * A long press on empty Home (not the dock, a bubble, glass or words: those take their own
     * touches) brings another painting: Clink, a ring where the finger is, and the backdrop sinks
     * the old one and surfaces the new. The music menu belongs to the music card alone (its own long
     * press), so this works while music plays too. The classic colour-glow menu has nothing to act
     * on here (there is no glow under water), so it is never opened from this edition's Home.
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

    // Measured (px, this box): where the world text ends, how tall the box and the bottom stack are.
    var boxTopWorld by remember { mutableFloatStateOf(0f) }
    var boxH by remember { mutableIntStateOf(0) }
    var textBottom by remember { mutableIntStateOf(0) }
    var bottomH by remember { mutableIntStateOf(0) }
    val rowH = 50.dp
    val pad = 10.dp
    val gap = 18.dp
    val rowPx = with(density) { rowH.toPx() }
    val padPx = with(density) { pad.toPx() }
    val gapPx = with(density) { gap.toPx() }
    // The battery label (measured: its world left edge and height) and the tier it shows.
    val tier by rememberChargeTier(charging, batteryLevel)
    var labelLeft by remember { mutableFloatStateOf(Float.NaN) }
    var labelH by remember { mutableIntStateOf(0) }
    // Notification bubbles rest in a row ON the dock's top edge. While there are any, the dock keeps
    // that row clear of Home's words above it, and the battery label shares the row at the right end
    // (centred on the bubbles), or, when the bubbles need its room too, lifts to sit just above them.
    val bubR = 21f * host.dp
    // Derived, not read directly: the label's and the dock's world x move on every frame of a pager
    // swipe (the page slides and scales), which would recompose all of Home each frame.
    val lifted by remember {
        derivedStateOf { dock.shelf > 0f && !labelLeft.isNaN() && dock.shelfWidth > labelLeft - dock.dockLeft - 12f * host.dp }
    }
    LaunchedEffect(dock) {
        snapshotFlow { if (dock.shelf > 0f && !lifted && !labelLeft.isNaN()) labelLeft - 10f * host.dp else Float.NaN }
            .collect { dock.shelfRight = it }
    }
    val reserveWant = when {
        dock.shelf <= 0f -> gapPx
        lifted -> dock.shelf + labelH + 6f * host.dp
        else -> maxOf(gapPx, dock.shelf + 4f * host.dp)
    }
    val reserve by androidx.compose.animation.core.animateFloatAsState(reserveWant, spring(0.82f, 260f), label = "shelf")
    // From the dock's top up to the label's top.
    val pinWant = when {
        dock.shelf <= 0f -> 24f * host.dp
        lifted -> dock.shelf - 4f * host.dp + labelH
        else -> 5f * host.dp + bubR + labelH / 2f
    }
    val pin by androidx.compose.animation.core.animateFloatAsState(pinWant, spring(0.82f, 260f), label = "pin")
    val avail = boxH - bottomH - textBottom - gapPx - reserveWant - 2 * padPx
    val fit = if (boxH == 0) 5 else (avail / rowPx).toInt().coerceIn(1, 7)
    val shown = items.take(fit)
    // The slab's height follows the rows as they open and close (more bubbles or music: a row folds away).
    val rowsShown by androidx.compose.animation.core.animateFloatAsState(shown.size.coerceAtLeast(1).toFloat(), spring(0.86f, 320f), label = "rows")
    val slabH = rowsShown * rowPx + 2 * padPx
    val ready = boxH > 0 && textBottom > 0
    val slabLaid by remember { derivedStateOf { dock.slabTop >= 0f } }

    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 24.dp)
            .onGloballyPositioned { boxCoords[0] = it; boxTopWorld = host.toWorld(it).y; boxH = it.size.height }
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
        // ---- the world: clock, date, the day. It stays above the water at most levels; under it,
        // it bends and the bright type keeps reading through.
        // While a card is up (an open folder, a notification) the words fade back: nothing reads through it.
        Column(Modifier.padding(horizontal = 8.dp).onSizeChanged { textBottom = it.height }.graphicsLayer { alpha = 1f - 0.9f * dock.dim.value }) {
            Spacer(Modifier.height(56.dp))
            BasicText(
                Format.clock(minute, is24h),
                style = QType.clock(112.sp).copy(color = c.ink),
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
            Spacer(Modifier.height(18.dp))
            BasicText(Format.longDate(minute), style = QType.body(17.sp).copy(color = c.muted, shadow = WaterTextShadow))
            if (oneThing.isNotBlank()) {
                Spacer(Modifier.height(24.dp))
                BasicText(oneThing, style = QType.question(22.sp).copy(color = c.ink, shadow = WaterTextShadow), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (showUsage) {
                Spacer(Modifier.height(28.dp))
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

        // ---- the battery, written on the waterline at the right edge (it rides the surface): the
        // charge state from the measured rate ("Fast charging · 62%"), just "62%" unplugged. A new
        // state crossfades in (the old one fades up and away, the new rises into place).
        val dockTopNow = remember { floatArrayOf(0f) }
        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 8.dp)
                .onGloballyPositioned { labelLeft = host.toWorld(it).x; labelH = it.size.height }
                .graphicsLayer {
                    host.tick
                    // The dock's top as laid out now (box px); before that, where it will go.
                    dockTopNow[0] = if (dock.slabTop >= 0f) dock.slabTop - boxTopWorld
                    else (host.restLine - boxTopWorld - slabH * 0.3f).coerceIn(textBottom + reserve, maxOf(textBottom + reserve, boxH - bottomH - gapPx - slabH))
                    val y = minOf(host.waterY(host.w - 40f * host.dp) - boxTopWorld - 22f * host.dp, dockTopNow[0] - pin)
                    val lo = textBottom.toFloat()
                    translationY = y.coerceIn(lo, maxOf(lo, (boxH - bottomH).toFloat() - 20f * host.dp))
                    alpha = 1f - 0.9f * dock.dim.value
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
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

        // ---- the glass dock: favourites on a slab resting on the water. It sits where the waterline
        // is (a third of it above the surface), kept between the world text (and the bubbles' row
        // above the dock, when there are bubbles) and the bottom.
        val minTop = textBottom + reserve
        val maxTop = boxH - bottomH - gapPx - slabH
        GlassBox(
            style = GlassStyle(corner = 26.dp, bevel = 14.dp, floats = true, stiffness = 520f, damping = 0.7f),
            modifier = Modifier
                .fillMaxWidth()
                .offset {
                    val want = host.restLine - boxTopWorld - slabH * 0.3f
                    IntOffset(0, want.coerceIn(minTop, maxOf(minTop, maxTop)).roundToInt())
                }
                .onGloballyPositioned {
                    val p = host.toWorld(it)
                    dock.dockLeft = p.x; dock.dockRight = p.x + it.size.width
                    dock.slabBottom = p.y + it.size.height
                    if (abs(p.y - dock.slabTop) > 1f) dock.slabTop = p.y
                },
        ) {
            Column(Modifier.fillMaxWidth().padding(vertical = pad, horizontal = 20.dp)) {
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
                        BasicText("Long press any app in the list to add it here.", style = QType.secondary(15.sp).copy(color = c.muted))
                    }
                }
            }
        }

        // ---- bottom: now playing (when music plays) over two small glass buttons.
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
                            // The cover card floats on the water: a glass raft that bobs and rolls with
                            // the local wave and bends the light at its rim; the card rides it.
                            GlassBox(
                                style = GlassStyle(corner = 24.dp, bevel = 14.dp, floats = true, stir = true, stiffness = 300f, damping = 0.52f),
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
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp, start = 4.dp, end = 4.dp)) {
                GlassButton(host, QIcon.Phone) { SystemActions.dialer(ctx) }
                Spacer(Modifier.weight(1f))
                GlassButton(host, QIcon.Camera) { SystemActions.camera(ctx) }
            }
        }

        // ---- above everything in the world: notification bubbles beading on the dock, the label of
        // a lifted drop and an open folder's card.
        // Only once the dock is really laid out (the box knows its height): bubbles never aim at a
        // first-pass dock position.
        if (slabLaid && boxH > 0 && textBottom > 0) BubbleLayer(
            dock = dock, active = active && !playerOpen,
            reduced = Q.reducedMotion, modifier = Modifier.matchParentSize(),
        )
        DockOverlay(dock, view, Modifier.matchParentSize())
    }
}

@Composable
private fun GlassButton(host: WaterHost, icon: QIcon, onClick: () -> Unit) {
    GlassBox(
        style = GlassStyle(corner = 26.dp, bevel = 22.dp, floats = true, stiffness = 300f, damping = 0.5f),
        modifier = Modifier.size(52.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxSize().pressable { host.feel(Feel.Clink); onClick() },
            contentAlignment = Alignment.Center,
        ) { QIconView(icon, Q.colors.ink) }
    }
}
