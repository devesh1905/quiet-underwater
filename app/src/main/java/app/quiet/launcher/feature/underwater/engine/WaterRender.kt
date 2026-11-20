package app.quiet.launcher.feature.underwater.engine

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import app.quiet.launcher.designsystem.Haptics
import app.quiet.launcher.feature.lock.charge.FloatTex
import app.quiet.launcher.feature.player.tonePalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** The compiled water shader and its textures (API 33+). Arrays are reused; per frame only the RenderEffect wrapper is new. */
@RequiresApi(33)
internal class WaterGpu {
    val shader: RuntimeShader? = try { RuntimeShader(WaterShader.SRC) } catch (e: RuntimeException) {
        Log.w(TAG, "water shader rejected", e); null
    }
    private val surfTex = FloatTex(96, 1)
    private var hf: FloatTex? = null
    private var hfShader: Shader? = null
    private var hfZero = false
    /** Rows of the ripple texture above this are known to hold the neutral value (0.5). */
    private var hfNeutralTo = 0
    private val drops = FloatArray(WaterSim.MAX_DROPS * 4)
    private val rings = FloatArray(WaterSim.MAX_RINGS * 4)
    private val ga = FloatArray(WaterSim.MAX_GLASS * 4)
    private val gb = FloatArray(WaterSim.MAX_GLASS * 4)
    private val gc = FloatArray(WaterSim.MAX_GLASS * 4)
    private val gd = FloatArray(WaterSim.MAX_GLASS * 4)

    fun effect(c: WaterHost): RenderEffect? {
        val traced = Build.VERSION.SDK_INT >= 29 && android.os.Trace.isEnabled()
        if (traced) android.os.Trace.beginSection("Water.effect")
        try { return build(c) } finally { if (traced) android.os.Trace.endSection() }
    }

    private fun build(c: WaterHost): RenderEffect? {
        val sh = shader ?: return null
        val s = c.sim
        val g = s.grid ?: return null
        if (c.w <= 0f) return null
        val dp = c.dp
        val amp = 200f * dp
        var vMax = 0f
        for (i in 0 until 96) {
            val v = (0.5f + (s.surf.h[i] + s.ambY(i / 95f)) / amp).coerceIn(0f, 1f)
            if (v > vMax) vMax = v
            surfTex.put(i, v)
        }
        // The highest the surface reaches anywhere (the texture is a linear blend of these samples),
        // less 3 px (the shader's own band) and 2 px for the half-float storage: above it, no water.
        sh.setFloatUniform("uTopY", s.line[0] - (vMax - 0.5f) * amp - 5f)
        sh.setInputShader("uSurf", surfTex.upload())
        // The ripple field: gradient and laplacian per cell. Skipped while the grid rests (one zero upload).
        val t = hf?.takeIf { it.w == g.gw && it.h == g.gh } ?: FloatTex(g.gw, g.gh).also { hf = it; hfShader = null; hfZero = false; hfNeutralTo = 0 }
        if (s.gridLive || hfShader == null || !hfZero) {
            val gain = 0.07f
            val gw = g.gw; val gh = g.gh; val hh = g.h
            // The grid is flat (0) above the waterline, so only the rows from one above the highest
            // surface cell down are computed; the rows above are written neutral once.
            var minTop = gh
            for (x in 0 until gw) if (g.top[x] < minTop) minTop = g.top[x]
            val start = (minTop - 1).coerceIn(0, gh)
            if (start > hfNeutralTo) for (i in hfNeutralTo * gw until start * gw) t.put(i, 0.5f, 0.5f, 0.5f)
            hfNeutralTo = start
            for (y in start until gh) {
                val row = y * gw
                for (x in 0 until gw) {
                    val i = row + x
                    val cc = hh[i]
                    val l = if (x > 0) hh[i - 1] else cc
                    val r = if (x < gw - 1) hh[i + 1] else cc
                    val u = if (y > 0) hh[i - gw] else cc
                    val d = if (y < gh - 1) hh[i + gw] else cc
                    t.put(i, 0.5f + (r - l) * 0.125f * gain * 2f, 0.5f + (d - u) * 0.125f * gain * 2f, 0.5f + (l + r + u + d - 4f * cc) * gain * 0.5f)
                }
            }
            hfShader = t.upload()
            hfZero = !s.gridLive
        }
        sh.setInputShader("uHF", hfShader!!)
        sh.setFloatUniform("uGridOn", if (s.gridLive || !hfZero) 1f else 0f)
        sh.setFloatUniform("uRes", c.w, c.h)
        sh.setFloatUniform("uGrid", g.gw.toFloat(), g.gh.toFloat())
        sh.setFloatUniform("uSurfN", 96f)
        sh.setFloatUniform("uSurfAmp", amp)
        sh.setFloatUniform("uWater", s.line[0])
        sh.setFloatUniform("uTime", c.time)
        sh.setFloatUniform("uDp", dp)
        sh.setFloatUniform("uRefr", 34f * dp)
        sh.setFloatUniform("uCaus", 9f)
        sh.setFloatUniform("uCalm", if (c.still) 0.35f else 1f)
        sh.setFloatUniform("uAmb", if (c.still) 0f else s.amb)
        for (i in 0 until WaterSim.MAX_DROPS) {
            val vy = s.dropVy[i]
            drops[i * 4] = s.dropX[i]; drops[i * 4 + 1] = s.dropY[i]; drops[i * 4 + 2] = s.dropR[i]
            drops[i * 4 + 3] = (kotlin.math.abs(vy) / (2200f * dp)).coerceIn(0f, 0.6f)
        }
        sh.setFloatUniform("uDrop", drops)
        sh.setFloatUniform("uDrops", if (s.dropsAlive > 0) 1f else 0f)
        for (i in 0 until WaterSim.MAX_RINGS) {
            rings[i * 4] = s.ringX[i]; rings[i * 4 + 1] = s.ringY[i]; rings[i * 4 + 2] = s.ringR[i]; rings[i * 4 + 3] = s.ringA[i].coerceAtLeast(0f)
        }
        sh.setFloatUniform("uRing", rings)
        sh.setFloatUniform("uRings", if (s.ringsAlive > 0) 1f else 0f)
        // Glass: packed in slot order; count = last slot that shows + 1.
        val b = s.glass
        var count = 0
        // The reach of every body (bounding circle + 64 dp, as in the shader): pixels outside skip the glass.
        val reach = 64f * dp
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (i in 0 until b.n) {
            val on = b.alpha[i] > 0.002f && b.hw[i] > 0.5f
            val o = i * 4
            val cy = b.y[i] + b.bob[i]
            ga[o] = b.x[i]; ga[o + 1] = cy; ga[o + 2] = b.hw[i]; ga[o + 3] = b.hh[i]
            gb[o] = b.corner[i]; gb[o + 1] = b.group[i].toFloat(); gb[o + 2] = if (on) b.alpha[i] else 0f; gb[o + 3] = b.lens[i]
            gc[o] = b.frost[i]; gc[o + 1] = b.rot[i]; gc[o + 2] = b.squash[i]
            gc[o + 3] = minOf(b.bevel[i], minOf(b.hw[i], b.hh[i])).coerceAtLeast(1f)
            // Rotation as cos/sin, and the bounding radius of the squashed box.
            val sq = 1f + b.squash[i]
            val r = kotlin.math.hypot(maxOf(b.hw[i] * sq, 0.5f), maxOf(b.hh[i] / sq, 0.5f))
            gd[o] = kotlin.math.cos(b.rot[i]); gd[o + 1] = kotlin.math.sin(b.rot[i]); gd[o + 2] = r; gd[o + 3] = if (b.clear[i]) 1f else 0f
            if (on) {
                count = i + 1
                val e = r + reach
                x0 = minOf(x0, b.x[i] - e); x1 = maxOf(x1, b.x[i] + e)
                y0 = minOf(y0, cy - e); y1 = maxOf(y1, cy + e)
            }
        }
        sh.setFloatUniform("uGA", ga); sh.setFloatUniform("uGB", gb); sh.setFloatUniform("uGC", gc); sh.setFloatUniform("uGD", gd)
        if (count > 0) sh.setFloatUniform("uGBox", x0, y0, x1, y1) else sh.setFloatUniform("uGBox", 0f, 0f, -1f, -1f)
        sh.setFloatUniform("uGN", count.toFloat())
        sh.setFloatUniform("uDbg", WaterDebug.shaderMode.toFloat())
        sh.setFloatUniform("uMergeK", 26f * dp)
        sh.setFloatUniform("uDk", c.pDk); sh.setFloatUniform("uAc", c.pAc); sh.setFloatUniform("uMd", c.pMd)
        sh.setFloatUniform("uLt", c.pLt); sh.setFloatUniform("uSheen", c.pSheen)
        return RenderEffect.createRuntimeShaderEffect(sh, "content")
    }

    companion object { const val TAG = "QuietWater" }
}

/** Any shader error turns the water off (classic look) instead of crashing Home. */
internal var waterBroken = false

private fun WaterHost.gpuOrNull(): WaterGpu? {
    if (Build.VERSION.SDK_INT < 33 || waterBroken) return null
    return (gpu as? WaterGpu) ?: WaterGpu().also { gpu = it }
}

@Composable
fun rememberWaterHost(): WaterHost {
    val ctx = LocalContext.current
    return remember { WaterHost().also { it.haptics = Haptics.get(ctx) } }
}

/**
 * The world layer: everything drawn inside this composable is the scene the water and glass bend.
 * Also watches touches (without consuming them) so fingers stir the water.
 */
fun Modifier.waterWorld(host: WaterHost?): Modifier {
    if (host == null || Build.VERSION.SDK_INT < 33) return this
    return this
        .onGloballyPositioned { host.root = it; host.wake() }
        .pointerInput(host) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    for (ch in e.changes) {
                        if (ch.pressed && !ch.previousPressed) host.fingerDown(ch.position.x, ch.position.y)
                        else if (!ch.pressed && ch.previousPressed) host.fingerUp()
                        else if (ch.pressed) {
                            val dx = ch.position.x - ch.previousPosition.x; val dy = ch.position.y - ch.previousPosition.y
                            if (dx != 0f || dy != 0f) host.fingerMove(ch.position.x, ch.position.y, dx, dy)
                        }
                    }
                }
            }
        }
        .graphicsLayer {
            host.tick
            renderEffect = if (waterBroken || WaterDebug.hide and 4 != 0) null else try {
                host.gpuOrNull()?.effect(host)?.asComposeRenderEffect()
            } catch (e: RuntimeException) {
                Log.w(WaterGpu.TAG, "water failed; classic look", e); waterBroken = true; null
            }
        }
}

/**
 * Runs the water: battery level and palette in, the gravity sensor only while it animates, the
 * fixed 120 Hz simulation, and the battery rules: full rate only while something moves, 30 fps for
 * the short shimmer after a touch, otherwise the frame holds until a touch, tilt, level or event;
 * nothing while not [visible] or not resumed; one still frame with reduced motion or battery saver.
 */
@Composable
fun WaterEngine(host: WaterHost, level: Float, charging: Boolean, palette: IntArray?, reduced: Boolean, visible: Boolean) {
    val ctx = LocalContext.current
    host.dp = LocalDensity.current.density
    val resumed = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.RESUMED)
    val powerSave = remember(resumed) { ctx.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true }
    val enabled = Build.VERSION.SDK_INT >= 33 && visible && resumed
    val still = reduced || powerSave || WaterDebug.forceStill
    val tones = remember(palette) { tonePalette(palette) }
    SideEffect {
        host.setPalette(tones, palette?.getOrNull(3))
        host.setLevel(level, charging)
        host.still = still
    }

    // Tilt. While the water moves: the fused gravity sensor (smooth, uses the gyro) at the UI rate.
    // While it holds: the plain accelerometer at 5 Hz, which costs next to nothing and is enough to
    // notice the phone being tilted or picked up (that wakes the loop, which switches back).
    val sensing = enabled && !still
    val fast = host.awake
    DisposableEffect(sensing, fast) {
        if (!sensing) return@DisposableEffect onDispose { }
        val sm = ctx.getSystemService(SensorManager::class.java)
        val accel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val sensor = (if (fast) sm?.getDefaultSensor(Sensor.TYPE_GRAVITY) else null) ?: accel
        val l = if (sensor === accel && !fast) object : SensorEventListener {
            // Low-pass the raw accelerometer toward gravity, so hand jitter does not wake the water.
            private var fx = Float.NaN; private var fy = 0f
            override fun onSensorChanged(e: SensorEvent) {
                if (fx.isNaN()) { fx = e.values[0]; fy = e.values[1] } else { fx += (e.values[0] - fx) * 0.5f; fy += (e.values[1] - fy) * 0.5f }
                host.onGravity(fx, fy)
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        } else object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { host.onGravity(e.values[0], e.values[1]) }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(l, sensor, if (fast) SensorManager.SENSOR_DELAY_UI else 200_000)
        onDispose { sm?.unregisterListener(l) }
    }

    val view = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(enabled, still) {
        host.running = enabled && !still
        host.releaseFingers()
        if (!enabled) { host.awake = false; return@LaunchedEffect }
        if (still) {
            host.awake = false
            while (true) {
                if (host.takeStaticDirty()) androidx.compose.runtime.withFrameNanos { host.drawStatic() }
                host.wakeCh.receive()
            }
        }
        var last = 0L
        var slow = false
        try {
            while (true) {
                val now = SystemClock.uptimeMillis()
                if (!host.wantsFrames(now)) {
                    // Hold: no frames, no timers. A touch, tilt, level change or event wakes it.
                    last = 0L
                    if (slow) { slow = false; voteFrameRate(view, false) }
                    host.awake = false
                    host.wakeCh.receive()
                    continue
                }
                // A lone redraw (a wake with nothing moving) keeps the sensor as it is; the ambient
                // life uses the low-power one (a tilt still wakes it).
                when (host.reason) { 1 -> {}; 7 -> if (host.awake) host.awake = false; else -> if (!host.awake) host.awake = true }
                androidx.compose.runtime.withFrameNanos { t ->
                    val dt = if (last == 0L) 1f / 60f else ((t - last) / 1e9f).coerceIn(0.001f, 0.05f)
                    last = t
                    host.frame(dt)
                    host.tick = t
                }
                // 30 fps for the idle shimmer (and the display may drop its rate); a wake (touch) cuts the wait short.
                val full = host.fullRate(SystemClock.uptimeMillis())
                if (full == slow) { slow = !full; voteFrameRate(view, slow) }
                // The ambient life alone: ~25 fps.
                if (!full) withTimeoutOrNull(if (host.reason == 7) 34L else 24L) { host.wakeCh.receive() }
            }
        } finally {
            if (slow) voteFrameRate(view, false)
            host.awake = false
        }
    }
    DisposableEffect(Unit) { onDispose { host.running = false } }
}

/**
 * Android 15+: while only the slow shimmer draws, tell the system this view is fine at a low rate,
 * so the panel can leave 120 Hz; back to no preference for anything that moves. Touch and other
 * views still raise it.
 */
private fun voteFrameRate(view: android.view.View, slow: Boolean) {
    if (Build.VERSION.SDK_INT < 35) return
    try {
        view.requestedFrameRate = if (slow) android.view.View.REQUESTED_FRAME_RATE_CATEGORY_LOW else android.view.View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
    } catch (_: Throwable) { }
}

/**
 * Makes [this] composable's bounds a glass body (outset by [style].outset). Content inside should
 * ride the glass: put it in [glassFollow] with the same id, or simply use [GlassBox].
 * [alpha] is read every frame (e.g. a page's fade). Returns the body id via [onId] once allocated.
 */
fun Modifier.glass(host: WaterHost?, id: Int, alpha: (() -> Float)? = null): Modifier {
    if (host == null || id < 0) return this
    return onGloballyPositioned { host.anchor(id, it, alpha) }
}

/** Content that rides glass body [id]: follows its spring offset, squash and roll. */
fun Modifier.glassFollow(host: WaterHost?, id: Int, scale: Boolean = false): Modifier {
    if (host == null || id < 0) return this
    return graphicsLayer {
        host.tick
        translationX = host.glassDx(id)
        translationY = host.glassDy(id)
        rotationZ = host.glassRotation(id)
        if (scale) { scaleX = host.glassScaleX(id); scaleY = host.glassScaleY(id) }
    }
}

/** A glass body for the lifetime of the composition (-1 without water or when all are in use). */
@Composable
fun rememberGlass(host: WaterHost?, style: GlassStyle = GlassStyle()): Int {
    if (host == null) return -1
    val id = remember(host) { host.obtainGlass(style) }
    SideEffect { if (id >= 0) host.styleGlass(id, style) }
    DisposableEffect(host, id) { onDispose { host.freeGlass(id) } }
    return id
}

/**
 * A box whose bounds are a glass body, its [content] riding the glass. Without water (classic look)
 * it is a plain Box with [modifier].
 */
@Composable
fun GlassBox(
    style: GlassStyle = GlassStyle(),
    modifier: Modifier = Modifier,
    alpha: (() -> Float)? = null,
    contentAlignment: androidx.compose.ui.Alignment = androidx.compose.ui.Alignment.TopStart,
    content: @Composable () -> Unit,
) {
    val host = LocalWater.current
    val id = rememberGlass(host, style)
    Box(modifier.glass(host, id, alpha), propagateMinConstraints = true) {
        Box(Modifier.glassFollow(host, id), contentAlignment = contentAlignment) { content() }
    }
}
