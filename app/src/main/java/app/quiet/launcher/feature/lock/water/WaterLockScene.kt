package app.quiet.launcher.feature.lock.water

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import app.quiet.launcher.container
import app.quiet.launcher.core.data.NowPlayingRepository
import app.quiet.launcher.core.model.NowPlaying
import app.quiet.launcher.designsystem.Feel
import app.quiet.launcher.designsystem.Q
import app.quiet.launcher.designsystem.QType
import app.quiet.launcher.designsystem.QuietTheme
import app.quiet.launcher.designsystem.rememberHaptics
import app.quiet.launcher.feature.lock.LockBottomReserve
import app.quiet.launcher.feature.lock.LockClock
import app.quiet.launcher.feature.lock.LockContext
import app.quiet.launcher.feature.lock.LockLabel
import app.quiet.launcher.feature.lock.PaintingTint
import app.quiet.launcher.feature.lock.charge.LocalCharge
import app.quiet.launcher.feature.lock.charge.rememberPaintingSwatches
import app.quiet.launcher.feature.lock.readSensorSpot
import app.quiet.launcher.feature.lock.rememberFileBitmap
import app.quiet.launcher.feature.player.SeekLine
import app.quiet.launcher.feature.underwater.engine.GlassBox
import app.quiet.launcher.feature.underwater.engine.GlassStyle
import app.quiet.launcher.feature.underwater.engine.LocalWater
import app.quiet.launcher.feature.underwater.engine.WaterEngine
import app.quiet.launcher.feature.underwater.engine.WaterHost
import app.quiet.launcher.feature.underwater.engine.rememberWaterHost
import app.quiet.launcher.feature.underwater.engine.waterWorld
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * The live lock screen's handle on the Water theme, for [app.quiet.launcher.feature.lock.LockRoot]:
 * its gestures stay where they are and only report here (the unlock drag, the fingerprint touch,
 * the two-finger show-off). Null-safe: with no Water scene on screen every call does nothing.
 */
class WaterLockControl {
    internal var driver: WaterLockDriver? = null

    fun unlockDrag(lift: Float, h: Float, x: Float, dy: Float) { driver?.unlockDrag(lift, h, x, dy) }
    fun unlockEnd() { driver?.unlockEnd() }
    fun sensorPress(down: Boolean) { driver?.sensorPress(down) }
    fun unlocked() { driver?.unlocked() }
    /** The two-finger hold: Flood's plug-in moment without a charger. True when it started. */
    fun showOff(): Boolean = driver?.showOff() == true
}

private val Ink = Color(0xFFEDEBE6)

/**
 * The Water lock theme (Quiet Underwater): today's painting, full bleed, under a live water surface
 * that sits at the battery level, plugged in or not. Above the line the painting is dry; below it
 * the engine refracts, absorbs (in the painting's colours), lights caustics and draws the meniscus.
 * The clock floats just above the water and shimmers in it; a song floats its cover on the surface,
 * with a glass drop for the play orb. [control] is the live lock's handle (null in the gallery).
 */
@Composable
fun WaterLockScene(
    ctx: LockContext,
    control: WaterLockControl?,
    np: NowPlaying?,
    repo: NowPlayingRepository,
    onArtLongPress: (() -> Unit)? = null,
    onMusicLongPress: () -> Unit = {},
) {
    val context = LocalContext.current
    val container = context.container
    val battery by container.device.battery.collectAsStateWithLifecycle()
    val swatches = rememberPaintingSwatches(ctx.art?.imagePath)
    // The water's height is the battery level, linearly: 71% fills 71% of the screen.
    val host = rememberWaterHost().also { it.fullTop = LOCK_FULL_TOP; it.emptyBottom = LOCK_EMPTY_BOTTOM }
    val driver = remember(host) { WaterLockDriver(host) }
    val still = Q.reducedMotion || !ctx.animate
    val resumed = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.RESUMED)
    // A song floats its cover on the water, so while one shows the water stands at the raft's line
    // (about a fifth of the cover under) instead of at the battery level, wherever the battery is:
    // at a low battery the cover would otherwise hang in the air, at a high one sink. It rises or
    // falls there on the waterline's own spring; the words under the clock still give the level.
    var worldH by remember { mutableIntStateOf(0) }
    var raftLine by remember { mutableStateOf(Float.NaN) }
    val waterLevel = if (np != null && !raftLine.isNaN() && worldH > 0)
        (1f - (raftLine / worldH - LOCK_FULL_TOP) / (LOCK_EMPTY_BOTTOM - LOCK_FULL_TOP)).coerceIn(0f, 1f)
    else battery.level
    // The last song, so the music layout can fade out with its own cover when the music stops.
    val lastSong = remember { mutableStateOf(np) }
    if (np != null) lastSong.value = np
    // The cover shapes the painting while a song plays (CoverLight.kt): a gentle grade, its colours in
    // the caustics, its light around the raft. In over ~1 s; out when the music stops or has rested
    // paused for about 3 minutes (timed from the pause, so a later wake still knows).
    var pausedLong by remember { mutableStateOf(false) }
    LaunchedEffect(np?.isPlaying, np?.track?.id) {
        val n = np
        if (n == null || n.isPlaying) { pausedLong = false; return@LaunchedEffect }
        val left = 180_000L - (SystemClock.elapsedRealtime() - n.positionUpdatedAt)
        if (left > 0) { pausedLong = false; kotlinx.coroutines.delay(left) }
        pausedLong = true
    }
    val coverOn = np != null && !pausedLong
    val coverFx by animateFloatAsState(
        if (coverOn) 1f else 0f,
        tween(if (Q.reducedMotion) 0 else if (coverOn) 1000 else 1400, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "coverFx",
    )
    val coverPalHeld = remember { mutableStateOf<IntArray?>(null) }
    np?.palette?.let { coverPalHeld.value = it }
    val coverPal = rememberBlendedPalette(coverPalHeld.value)
    var coverRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    WaterEngine(host, waterLevel, battery.charging, causticPalette(swatches, coverPal, coverFx), reduced = still, visible = true)

    DisposableEffect(control, driver) {
        control?.driver = driver
        onDispose { if (control?.driver === driver) control.driver = null; driver.release() }
    }
    SideEffect {
        driver.showingMusic = np != null
        driver.playing = np?.isPlaying == true && resumed
        driver.kick()
    }

    // Charging: the plug-in moment when it happens in front of us; already charging, a settled inflow.
    var seenCharging by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(battery.charging, control) {
        val prev = seenCharging
        seenCharging = battery.charging
        if (control == null) return@LaunchedEffect
        if (battery.charging) driver.plugIn(jet = prev == false) else if (prev == true) driver.unplug()
    }
    // A faint drizzle for a few seconds after wake, then it settles still.
    LaunchedEffect(resumed, still) { if (resumed && !still && control != null) driver.wakeDrizzle() }

    LaunchedEffect(driver, resumed) {
        if (!resumed) return@LaunchedEffect
        var last = 0L
        while (true) {
            if (!driver.wantsFrames(SystemClock.uptimeMillis())) { last = 0L; driver.wakeCh.receive(); continue }
            withFrameNanos { t ->
                val dt = if (last == 0L) 1f / 60f else ((t - last) / 1e9f).coerceIn(0.001f, 0.05f)
                last = t
                driver.frame(dt)
            }
        }
    }

    // Near-white, only warmed by the painting: the face's hairlines must survive a bright painting.
    val clockTint = Color(PaintingTint.mix(Ink.toArgb(), swatches?.getOrNull(3) ?: 0xFFBFE3EC.toInt(), 0.18f))
    val tint by androidx.compose.animation.animateColorAsState(clockTint, tween(if (Q.reducedMotion) 0 else 900), label = "waterTint")

    // The resting waterline, worked out exactly as the engine does (WaterHost.levelToY), so the
    // clock and the cover are placed right on the first frame instead of gliding in.
    val rest = if (worldH > 0) worldH * (LOCK_FULL_TOP + (LOCK_EMPTY_BOTTOM - LOCK_FULL_TOP) * (1f - waterLevel.coerceIn(0f, 1f))) else 0f

    QuietTheme(dark = true, reducedMotion = Q.reducedMotion) {
        CompositionLocalProvider(LocalWater provides host) {
            Box(
                Modifier
                    .fillMaxSize()
                    .waterWorld(host)
                    .onSizeChanged { worldH = it.height }
                    .background(Color.Black)
                    .pointerInput(driver) {
                        // Read only: a touch keeps the inflow awake. Never consumed.
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent(PointerEventPass.Initial)
                                if (e.changes.any { it.pressed }) driver.touched()
                            }
                        }
                    }
            ) {
                val song = lastSong.value
                PaintingUnderWater(ctx.art?.imagePath, swatches, gradeFilter(coverPal, coverFx))
                CoverGlow(host, driver, coverPal, rect = { coverRect }, amount = { coverFx })
                if (rest > 0f) {
                    Crossfade(targetState = np != null, animationSpec = tween(if (Q.reducedMotion) 0 else 700), label = "waterMode") { on ->
                        val s = if (on) np ?: song else null
                        if (s != null) WaterMusic(ctx, s, repo, host, driver, tint, onMusicLongPress, onCover = { coverRect = it }) { raftLine = it }
                        else WaterIdle(ctx, rest, tint, onArtLongPress)
                    }
                }
                if (control != null) SensorDrop(driver)
            }
        }
    }
}

// ------------------------------------------------------------------------------ the painting

/** Today's painting, full bleed, with a soft veil only where type sits (the clock, the label). */
@Composable
private fun PaintingUnderWater(path: String?, palette: IntArray?, grade: androidx.compose.ui.graphics.ColorFilter? = null) {
    val bmp = rememberFileBitmap(path, maxSide = 1600)
    val deep = palette?.getOrNull(0)?.let { Color(it) } ?: Color(0xFF0B1A22)
    val mid = palette?.getOrNull(2)?.let { Color(it) } ?: Color(0xFF1E3B47)
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0xFF050708), 0.5f to deep.copy(alpha = 1f), 1f to mid))) {
        if (bmp == null) Canvas(Modifier.fillMaxSize()) {
            // No painting yet: pools and shafts of light, so the water still has something to bend.
            val lt = palette?.getOrNull(3)?.let { Color(it) } ?: Color(0xFF9FD3E0)
            val ac = palette?.getOrNull(1)?.let { Color(it) } ?: Color(0xFF3F7F9A)
            drawCircle(Brush.radialGradient(listOf(ac.copy(alpha = 0.3f), Color.Transparent), center = Offset(size.width * 0.15f, size.height * 0.62f), radius = size.width * 0.8f), size.width * 0.8f, Offset(size.width * 0.15f, size.height * 0.62f))
            drawCircle(Brush.radialGradient(listOf(lt.copy(alpha = 0.18f), Color.Transparent), center = Offset(size.width * 0.9f, size.height * 0.85f), radius = size.width * 0.6f), size.width * 0.6f, Offset(size.width * 0.9f, size.height * 0.85f))
            for (i in 0 until 7) {
                val x0 = size.width * (0.08f + i * 0.15f)
                drawLine(lt.copy(alpha = 0.04f + 0.02f * (i % 3)), Offset(x0, 0f), Offset(x0 - size.width * 0.25f, size.height), strokeWidth = size.width * (0.02f + 0.012f * (i % 2)))
            }
        }
        Crossfade(targetState = bmp, animationSpec = tween(700), label = "waterPainting") { b ->
            if (b != null) {
                val img = remember(b) { b.asImageBitmap() }
                Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, colorFilter = grade)
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color(0x99000000), 0.24f to Color(0x47000000), 0.5f to Color(0x0F000000),
                    0.72f to Color(0x1A000000), 1f to Color(0x8C000000),
                )
            )
        )
    }
}

// ------------------------------------------------------------------------------ no music

/**
 * The clock floats just above the waterline (it rides the level down to about a third of the
 * screen) and its reflection shimmers in the water under it; the painting's label sits low, in the water.
 */
@Composable
private fun WaterIdle(ctx: LockContext, rest: Float, tint: Color, onArtLongPress: (() -> Unit)?) {
    val density = LocalDensity.current
    val reduced = Q.reducedMotion
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val hPx = constraints.maxHeight.toFloat()
        val topInset = WindowInsets.systemBars.getTop(density).toFloat()
        var clockH by remember { mutableIntStateOf(0) }
        val gap = with(density) { 18.dp.toPx() }
        val nat = topInset + with(density) { 36.dp.toPx() }
        val want = (rest - gap - clockH).coerceIn(nat, max(nat, hPx * 0.30f))
        val top = settling(want, ready = clockH > 0, reduced = reduced)
        Box(
            Modifier.fillMaxWidth().offset { IntOffset(0, top.roundToInt()) }
                .graphicsLayer { alpha = if (clockH > 0) 1f else 0f }
                .clockHalo(),
            contentAlignment = Alignment.TopCenter,
        ) {
            LockClock(ctx, Modifier.onSizeChanged { clockH = it.height }, tint = tint)
        }
        // The reflection: the clock mirrored about the surface, fading with depth. It lives in the
        // water, so the ripples and the swell bend it; when the clock rides high, it sits at the line.
        // The painting's label (top, px in this box): the reflection fades out before it, never over it.
        var labelTop by remember { mutableStateOf(Float.MAX_VALUE) }
        // High water reaches the clock block: no mirror then, or it would sit on top of the date.
        if (clockH > 0 && rest >= top + clockH + gap * 0.5f) {
            val reflTop = rest + (rest - (top + clockH)).coerceIn(2f, gap)
            val fade = with(density) { 36.dp.toPx() }
            val room = labelTop - with(density) { 10.dp.toPx() } - reflTop
            Box(
                Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(0, reflTop.roundToInt()) }
                    .graphicsLayer {
                        scaleY = -1f
                        alpha = 0.55f
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color.Black.copy(alpha = 0.3f), 1f to Color.Black),
                            blendMode = BlendMode.DstIn,
                        )
                        // Flipped: content y maps to reflTop + (height - y); keep only what lies above the label.
                        val cut = size.height - room
                        if (cut > 0f) drawRect(
                            Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = cut, endY = cut + fade),
                            blendMode = BlendMode.DstIn,
                        )
                    },
                contentAlignment = Alignment.TopCenter,
            ) {
                CompositionLocalProvider(LocalCharge provides null) { LockClock(ctx, tint = tint) }
            }
        }
        val art = ctx.art
        if (art != null) {
            val press by rememberUpdatedState(onArtLongPress)
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(horizontal = 32.dp)
                    .padding(bottom = LockBottomReserve)
                    .onGloballyPositioned { labelTop = it.positionInParent().y }
            ) {
                LockLabel(
                    ctx, art.title, art.artist,
                    listOfNotNull(art.year, art.museum).joinToString(", ").ifBlank { null },
                    Modifier.fillMaxWidth().then(
                        if (onArtLongPress != null) Modifier.pointerInput(Unit) {
                            // A long press that stays put; moving (the unlock swipe starts here too) cancels it.
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val slop = viewConfiguration.touchSlop
                                var moved = false
                                val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                    while (true) {
                                        val e = awaitPointerEvent()
                                        val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!ch.pressed) break
                                        if ((ch.position - down.position).getDistance() > slop) { moved = true; break }
                                    }
                                    true
                                }
                                if (ended == null && !moved) press?.invoke()
                            }
                        } else Modifier
                    ),
                )
            }
        }
    }
}

/** A soft dark halo under the time, so its hairlines read on any painting. */
private fun Modifier.clockHalo(): Modifier = drawBehind {
    val r = size.width * 0.52f
    drawRect(
        Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.42f), Color.Black.copy(alpha = 0.16f), Color.Transparent), center = center, radius = r),
        topLeft = Offset(0f, center.y - r), size = androidx.compose.ui.geometry.Size(size.width, r * 2f),
    )
}

// ------------------------------------------------------------------------------ music

/**
 * A song playing: the cover floats on the water as a glass raft (it bobs and rolls with the waves,
 * light bends at its rim), the play orb is a glass drop, and the beat sends soft rings out from it.
 * Tap the cover or the drop to pause, drag the drop sideways to change song, seek on the line.
 */
@Composable
private fun WaterMusic(
    ctx: LockContext, np: NowPlaying, repo: NowPlayingRepository, host: WaterHost, driver: WaterLockDriver,
    tint: Color, onLongPress: () -> Unit, onCover: (androidx.compose.ui.geometry.Rect) -> Unit, onRaftLine: (Float) -> Unit,
) {
    val c = Q.colors
    val reduced = Q.reducedMotion
    val density = LocalDensity.current
    val t = updateTransition(!np.isPlaying, label = "waterPaused")
    val spec = if (reduced) tween<Float>(0) else spring<Float>(0.8f, 300f)
    val clockScale by t.animateFloat({ spec }, label = "clock") { if (it) 1f else 0.72f }
    val dim by t.animateFloat({ if (reduced) tween(0) else tween(600) }, label = "dim") { if (it) 1f else 0f }
    val progAlpha by t.animateFloat({ if (reduced) tween(0) else tween(400) }, label = "prog") { if (it) 0.5f else 1f }
    SideEffect {
        driver.onSongChange = { dir -> if (dir < 0) repo.next() else repo.previous() }
    }

    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        var clockH by remember { mutableIntStateOf(0) }
        LockClock(
            ctx,
            Modifier.onSizeChanged { clockH = it.height }.graphicsLayer {
                scaleX = clockScale; scaleY = clockScale
                transformOrigin = TransformOrigin(0.5f, 0f)
            }.clockHalo(),
            tint = tint,
        )
        Spacer(Modifier.height(12.dp + with(density) { (clockH * (clockScale - 1f)).toDp() }))
        // The stage: the cover floats at the waterline (about a fifth of it under), kept inside the stage.
        var stage by remember { mutableStateOf<LayoutCoordinates?>(null) }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).onGloballyPositioned { stage = it }) {
            val side = min(maxWidth.value * 0.9f, maxHeight.value * 0.94f).dp
            val sidePx = with(density) { side.toPx() }
            val stageH = constraints.maxHeight.toFloat()
            val stageTop = stage?.let { host.toWorld(it).y } ?: 0f
            // The cover sits in the middle of its stage; the water comes to it (see WaterLockScene).
            val wantCy = (stageH / 2f).coerceIn(sidePx / 2f, max(sidePx / 2f, stageH - sidePx / 2f))
            val line = stageTop + wantCy + sidePx * 0.28f
            if (stage != null) SideEffect { onRaftLine(line) }
            val cy = settling(wantCy, ready = stage != null, reduced = reduced)
            val away = { driver.away() }
            GlassBox(
                style = GlassStyle(corner = 6.dp, bevel = 14.dp, floats = true, stir = true, stiffness = 230f, damping = 0.55f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, (cy - sidePx / 2f).roundToInt()) }
                    .size(side)
                    .onGloballyPositioned { c ->
                        // Where the cover's light comes from (world px, before its sideways ride).
                        val p = host.toWorld(c)
                        onCover(androidx.compose.ui.geometry.Rect(p.x, p.y, p.x + c.size.width, p.y + c.size.height))
                    }
                    .graphicsLayer {
                        translationX = driver.coverX
                        alpha = if (stage != null) 1f - 0.8f * away() else 0f
                    },
                alpha = { 1f - 0.85f * away() },
            ) {
                CoverOnWater(np, dim, repo, driver, onLongPress)
            }
        }
        Spacer(Modifier.height(14.dp))
        AnimatedContent(np.track, transitionSpec = { fadeIn(tween(300, 160)) togetherWith fadeOut(tween(160)) }, label = "waterLabel") { tr ->
            LockLabel(ctx, tr.title, tr.artist, listOfNotNull(tr.album, tr.year).joinToString(", ").ifBlank { null }, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(6.dp))
        SeekLine(np, repo::seekTo, c.edgeOnField, 13.sp, Modifier.graphicsLayer { alpha = progAlpha })
        OrbDrop(np, repo, host, driver)
        Spacer(Modifier.height(ctx.bottomReserve))
    }
}

@Composable
private fun CoverOnWater(np: NowPlaying, dim: Float, repo: NowPlayingRepository, driver: WaterLockDriver, onLongPress: () -> Unit) {
    val c = Q.colors
    val longPress by rememberUpdatedState(onLongPress)
    Box(
        Modifier.fillMaxSize()
            .clip(RoundedCornerShape(6.dp))
            .background(c.mat)
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { longPress() }, onTap = { repo.playPause(); driver.orbTap() })
            },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = np.track.id to np.track.art, animationSpec = tween(600), label = "waterCover") { (_, art) ->
            if (art != null) {
                val img = remember(art) { art.asImageBitmap() }
                Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                BasicText(np.track.title, style = QType.labelTitle(22.sp).copy(color = c.ink), modifier = Modifier.padding(24.dp))
            }
        }
        if (dim > 0.001f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f * dim)))
    }
}

/**
 * The play orb as a glass drop: a body in the engine (it bobs on the water, stretches when flung,
 * stirs the water it moves through), with the play / pause glyph drawn in the world under it so the
 * lens magnifies it. Tap: play or pause (Clink). Drag sideways: the cover comes along; past a fifth
 * of the width or flung, the drop flies off, the song changes and a new drop comes in from the other side.
 */
@Composable
private fun OrbDrop(np: NowPlaying, repo: NowPlayingRepository, host: WaterHost, driver: WaterLockDriver) {
    val density = LocalDensity.current
    val haptics = rememberHaptics()
    val reduced = Q.reducedMotion
    val paused by animateFloatAsState(if (np.isPlaying) 0f else 1f, if (reduced) tween(0) else tween(260), label = "glyph")
    SideEffect { driver.orbR = with(density) { 30.dp.toPx() } }
    DisposableEffect(driver) { onDispose { driver.rowCoords = null; driver.releaseOrb() } }
    val playing by rememberUpdatedState(np.isPlaying)
    Box(
        Modifier
            .fillMaxWidth()
            .height(104.dp)
            .onGloballyPositioned { driver.rowCoords = it; driver.kick() }
            .semantics {
                role = Role.Button
                contentDescription = if (playing) "Pause. Drag sideways to change song" else "Play. Drag sideways to change song"
                onClick { repo.playPause(); true }
            }
            .pointerInput(driver) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val r = 30.dp.toPx()
                    // Only the drop (and a little around it) is touchable; elsewhere the lock's own gestures run.
                    if (abs(down.position.x - (size.width / 2f + driver.orbDx)) > r * 1.8f) return@awaitEachGesture
                    down.consume()
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    val start = awaitHorizontalTouchSlopOrCancellation(down.id) { ch, _ -> ch.consume() }
                    if (start == null) {
                        val up = currentEvent.changes.firstOrNull { it.id == down.id }
                        if (up != null && !up.pressed && !up.isConsumed && up.uptimeMillis - down.uptimeMillis < 450) {
                            repo.playPause(); driver.orbTap()
                        }
                        return@awaitEachGesture
                    }
                    val base = driver.orbDx
                    val w = size.width.toFloat()
                    var armed = false
                    driver.beginDrag()
                    fun follow(px: Float) {
                        val raw = base + px - down.position.x
                        val lim = w * 0.42f
                        driver.dragOff = if (abs(raw) < lim) raw else sign(raw) * (lim + (abs(raw) - lim) * 0.3f)
                        val past = abs(driver.dragOff) > w * 0.22f
                        if (past != armed) { armed = past; if (past) haptics.play(Feel.Tick) }
                        driver.kick()
                    }
                    follow(start.position.x)
                    tracker.addPosition(start.uptimeMillis, start.position)
                    horizontalDrag(start.id) { ch ->
                        tracker.addPosition(ch.uptimeMillis, ch.position)
                        follow(ch.position.x)
                        ch.consume()
                    }
                    driver.endDrag(tracker.calculateVelocity().x)
                }
            },
    ) {
        // The glyph under the glass: two bars while playing, a triangle while paused.
        Canvas(Modifier.fillMaxSize()) {
            host.tick
            val cx = size.width / 2f + driver.orbDx
            val cy = size.height * 0.46f + driver.orbDy
            val u = 3.6.dp.toPx()   // stays inside the drop's flat centre, so the lens reads it, not the rim
            val ink = Ink.copy(alpha = 0.9f)
            val bars = 1f - paused
            if (bars > 0.01f) {
                val a = ink.copy(alpha = 0.9f * bars)
                drawLine(a, Offset(cx - u * 0.9f, cy - u * 1.5f), Offset(cx - u * 0.9f, cy + u * 1.5f), u * 0.8f, StrokeCap.Round)
                drawLine(a, Offset(cx + u * 0.9f, cy - u * 1.5f), Offset(cx + u * 0.9f, cy + u * 1.5f), u * 0.8f, StrokeCap.Round)
            }
            if (paused > 0.01f) {
                val p = Path().apply {
                    moveTo(cx - u * 1.0f, cy - u * 1.6f); lineTo(cx + u * 1.7f, cy); lineTo(cx - u * 1.0f, cy + u * 1.6f); close()
                }
                drawPath(p, ink.copy(alpha = 0.9f * paused))
            }
        }
    }
}

// ------------------------------------------------------------------------------ fingerprint drop

/** The fingerprint ring as a small glass drop on the sensor, over a faint print that it magnifies. */
@Composable
private fun SensorDrop(driver: WaterLockDriver) {
    var spot by remember { mutableStateOf<app.quiet.launcher.feature.lock.SensorSpot?>(null) }
    Box(Modifier.fillMaxSize().onSizeChanged { s -> spot = readSensorSpot(s.width, s.height) }) {
        val sp = spot ?: return@Box
        LaunchedEffect(sp) { driver.sensor(sp.cx, sp.cy, sp.radius * 0.62f) }
        Canvas(Modifier.fillMaxSize()) {
            val r = sp.radius * 0.34f
            val ink = Ink.copy(alpha = 0.32f)
            val st = 1.1.dp.toPx()
            for (i in 0 until 4) {
                val rr = r * (0.3f + i * 0.24f)
                drawArc(
                    ink, 150f + i * 6f, 240f - i * 14f, false,
                    topLeft = Offset(sp.cx - rr, sp.cy - rr * 1.08f),
                    size = androidx.compose.ui.geometry.Size(rr * 2, rr * 2.16f),
                    style = Stroke(st, cap = StrokeCap.Round),
                )
            }
        }
    }
}

/**
 * A position that snaps to its first [ready] value and springs to later ones (a level change moves
 * the clock and the cover slowly; nothing glides in when the lock screen comes up).
 */
@Composable
private fun settling(target: Float, ready: Boolean, reduced: Boolean): Float {
    val a = remember { androidx.compose.animation.core.Animatable(target) }
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(target, ready) {
        if (!ready) { a.snapTo(target); return@LaunchedEffect }
        if (!settled || reduced) { a.snapTo(target); settled = true }
        else a.animateTo(target, spring(0.9f, 40f))
    }
    return if (!settled) target else a.value
}

/** Linear water level on the lock screen: 100% reaches the top, 0% is empty. */
private const val LOCK_FULL_TOP = 0f
private const val LOCK_EMPTY_BOTTOM = 1f
