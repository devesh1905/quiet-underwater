package app.quiet.launcher.feature.underwater.engine

import app.quiet.launcher.feature.lock.charge.RippleGrid
import app.quiet.launcher.feature.lock.charge.SpringSurface
import app.quiet.launcher.feature.lock.charge.springStep
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The CPU side of Quiet Underwater, reusing Flood's two simulations (lock/charge/ChargeSims.kt):
 *  - the waterline: a [SpringSurface] of 96 columns at the battery level, levelled by the tilt;
 *  - the body: a [RippleGrid] height field under it (free surface at the waterline);
 * plus falling glass drops, analytic refraction rings and the glass bodies ([GlassBodies]).
 * Everything is stepped by the host at a fixed 120 Hz; nothing here allocates after construction.
 * Units are px in the world layer (y down).
 */
internal class WaterSim {
    val surf = SpringSurface(96).apply { k = 14f; spread = 30f * 30f; damp = 0.9f }
    var grid: RippleGrid? = null
    val glass = GlassBodies(MAX_GLASS)
    var w = 0f; var h = 0f; var dp = 1f
    var time = 0f

    /** Fill as a waterline y (px), on a soft spring toward [targetY]. [value, velocity]. */
    val line = FloatArray(2)
    var targetY = 0f
    private var lineReady = false

    /** Grid has motion worth stepping / uploading. */
    var gridLive = false
        private set
    private var gridQuietSteps = 0

    // ---- drops: x, y, vx, vy, r, landed flag. r <= 0 free.
    val dropX = FloatArray(MAX_DROPS); val dropY = FloatArray(MAX_DROPS)
    val dropVx = FloatArray(MAX_DROPS); val dropVy = FloatArray(MAX_DROPS)
    val dropR = FloatArray(MAX_DROPS); val dropG = FloatArray(MAX_DROPS)
    /** Set when a drop lands (index); the host turns it into a callback. */
    val dropLanded = BooleanArray(MAX_DROPS)
    val dropLandX = FloatArray(MAX_DROPS); val dropLandY = FloatArray(MAX_DROPS)
    var dropsAlive = 0

    // ---- rings: x, y, radius, amp; amp <= 0 free.
    val ringX = FloatArray(MAX_RINGS); val ringY = FloatArray(MAX_RINGS)
    val ringR = FloatArray(MAX_RINGS); val ringA = FloatArray(MAX_RINGS)
    private val ringKicked = BooleanArray(MAX_RINGS)
    var ringsAlive = 0

    fun resize(w: Float, h: Float, dp: Float) {
        if (this.w == w && this.h == h && grid != null) return
        this.w = w; this.h = h; this.dp = dp
        val gw = 90
        val gh = (gw * h / w).toInt().coerceIn(120, 210)
        grid = RippleGrid(gw, gh).apply { c2 = 38f * 38f; damp = 0.85f }
        if (!lineReady) { line[0] = targetY.takeIf { it > 0f } ?: (h * 0.6f); line[1] = 0f; lineReady = true }
    }

    fun waterY(x: Float): Float { val u = if (w > 0f) x / w else 0.5f; return line[0] - surf.at(u) - ambY(u) }

    // ---- the ambient swell: a slow wave travelling along the waterline in sets every few seconds,
    // analytic (no spring energy, so it never asks for the display rate). [amb] eases toward
    // [ambTarget] (Home sets it while it is in front); the shader reads the same curve via uSurf.
    var amb = 0f
    var ambTarget = 0f
    private var ambT = 0f
    private var ambA = 0f

    /** The swell's rise (px, up) at [u] (0..1 across); 0 while the ambient is off. */
    fun ambY(u: Float): Float {
        if (ambA <= 0f) return 0f
        val t = ambT
        val a = 6.2831855f
        return ambA * (0.68f * kotlin.math.sin(a * (u * 0.72f - t / 3.6f)) + 0.32f * kotlin.math.sin(a * (u * 1.55f + t / 2.45f) + 1.3f))
    }

    private fun stepAmbient(dt: Float) {
        // Eases in over ~1 s; out over ~2 s to a fully still surface (then the loop stops).
        amb += (ambTarget - amb) * (1f - exp(-dt / (if (ambTarget > amb) 0.9f else 0.55f)))
        if (ambTarget <= 0f && amb < 0.012f) amb = 0f
        if (amb <= 0f) { ambA = 0f; return }
        ambT += dt
        // Sets: the swell builds and fades every ~6.5 s, never quite flat.
        val s = 0.5f + 0.5f * kotlin.math.sin(6.2831855f * ambT / 6.5f - 1.4f)
        ambA = 4.2f * dp * amb * (0.22f + 0.78f * s * s)
    }

    /** The ring fade rate (amp per second) of each ring. */
    val ringF = FloatArray(MAX_RINGS)

    /** Local surface slope (dy/dx) around [x], over [span] px. */
    fun slope(x: Float, span: Float): Float {
        val a = waterY(x - span / 2f); val b = waterY(x + span / 2f)
        return (b - a) / max(span, 1f)
    }

    fun cellX(x: Float, g: RippleGrid) = x / w * g.gw
    fun cellY(y: Float, g: RippleGrid) = y / h * g.gh

    // ---------------------------------------------------------------- events

    /** A splash ring in the water at (x, y) (px): only below the surface; above it the surface is kicked. */
    fun ripple(x: Float, y: Float, amount: Float, radiusCells: Float = 2f) {
        val g = grid ?: return
        val wy = waterY(x)
        if (y >= wy - 2f * dp) {
            g.splash(cellX(x, g), cellY(max(y, wy + 1f), g) + 1f, radiusCells, amount)
            gridLive = true; gridQuietSteps = 0
        }
        if (abs(y - wy) < 60f * dp) surf.kick(x / w, -amount * 0.35f * dp, 2.2f)
    }

    fun touch(x: Float, y: Float, dx: Float, dy: Float): Boolean {
        val g = grid ?: return false
        val wy = waterY(x)
        val sp = sqrt(dx * dx + dy * dy) / dp
        if (y >= wy) {
            g.splash(cellX(x, g), cellY(y, g), 2.2f, (40f + sp * 14f).coerceAtMost(320f))
            gridLive = true; gridQuietSteps = 0
            if (y - wy < 40f * dp) surf.kick(x / w, -min(sp, 30f) * 3f * dp, 2.5f)
            return true
        }
        // Just above the surface: the finger drags the meniscus with it.
        if (wy - y < 36f * dp) { surf.kick(x / w, dy * 0.6f, 3f); return true }
        return false
    }

    fun drop(x: Float, y: Float, r: Float, vx: Float, vy: Float, g: Float): Int {
        for (i in 0 until MAX_DROPS) if (dropR[i] <= 0f) {
            dropX[i] = x; dropY[i] = y; dropVx[i] = vx; dropVy[i] = vy; dropR[i] = r; dropG[i] = g
            dropLanded[i] = false
            return i
        }
        return -1
    }

    fun ring(x: Float, y: Float, amp: Float, fade: Float = 0.9f) {
        var slot = 0; var weakest = Float.MAX_VALUE
        for (i in 0 until MAX_RINGS) {
            if (ringA[i] <= 0f) { slot = i; weakest = -1f; break }
            if (ringA[i] < weakest) { weakest = ringA[i]; slot = i }
        }
        ringX[slot] = x; ringY[slot] = y; ringR[slot] = 0f; ringA[slot] = amp; ringF[slot] = fade; ringKicked[slot] = false
    }

    // ---------------------------------------------------------------- step

    fun energy(): Float {
        val ge = if (gridLive) (grid?.energy() ?: 0f) * 40f else 0f
        return surf.energy() / (dp * dp) + ge + abs(line[1]) / dp * 8f
    }

    fun step(dt: Float, tilt: Float) {
        val g = grid ?: return
        time += dt
        springStep(line, targetY, 3.2f, 0.62f, dt)
        stepAmbient(dt)
        surf.setTilt(tilt, w)
        surf.step(dt)
        // ---- drops: ballistic, splash where they meet the surface.
        dropsAlive = 0
        for (i in 0 until MAX_DROPS) {
            if (dropR[i] <= 0f) continue
            dropVy[i] += dropG[i] * dt
            dropX[i] += dropVx[i] * dt; dropY[i] += dropVy[i] * dt
            val sy = waterY(dropX[i])
            if (dropVy[i] > 0f && (dropY[i] + dropR[i] * 0.4f >= sy || dropY[i] > h)) {
                val ly = min(sy, h - 2f)
                g.splash(cellX(dropX[i], g), cellY(ly, g) + 1.5f, 1.6f + dropR[i] / (6f * dp), 230f)
                surf.kick(dropX[i] / w, -dropR[i] * 16f, 2.2f)
                gridLive = true; gridQuietSteps = 0
                dropLanded[i] = true; dropLandX[i] = dropX[i]; dropLandY[i] = ly
                dropR[i] = 0f
                continue
            }
            dropsAlive++
        }
        // ---- rings: expand and fade; kick the waterline once when they reach it.
        ringsAlive = 0
        for (i in 0 until MAX_RINGS) {
            if (ringA[i] <= 0f) continue
            ringR[i] += dt * 900f * dp * (0.55f + 0.45f * exp(-ringR[i] / (220f * dp)))
            ringA[i] -= dt * ringF[i]
            val wy = waterY(ringX[i])
            if (!ringKicked[i] && ringR[i] >= abs(wy - ringY[i])) {
                ringKicked[i] = true
                surf.kick(ringX[i] / w, -60f * dp * ringA[i], 6f)
            }
            if (ringA[i] <= 0f || ringR[i] > h * 1.4f) { ringA[i] = 0f; continue }
            ringsAlive++
        }
        // ---- glass: springs, squash, buoyancy on the surface; moving glass stirs the water.
        glass.step(dt, this)
        // ---- the grid's free surface follows the waterline
        for (x in 0 until g.gw) g.top[x] = cellY(waterY((x + 0.5f) / g.gw * w), g).toInt().coerceIn(0, g.gh)
        if (gridLive) {
            g.step(dt)
            // Quiet for half a second: stop stepping (and uploading) until something splashes.
            if (g.energy() < 1e-3f) { if (++gridQuietSteps > 60) { g.clear(); gridLive = false } } else gridQuietSteps = 0
        }
    }

    /** Reduced motion / battery saver: the water at rest at its level. */
    fun settle() {
        line[0] = targetY; line[1] = 0f
        amb = 0f; ambA = 0f
        surf.clearBump(); surf.setTilt(0f, w); surf.flatten()
        grid?.clear(); gridLive = false
        for (i in 0 until MAX_DROPS) { dropR[i] = 0f }
        for (i in 0 until MAX_RINGS) ringA[i] = 0f
        dropsAlive = 0; ringsAlive = 0
        glass.settle(this)
    }

    companion object {
        /** Glass bodies in total across every screen in the tree (the shader arrays are this size). */
        const val MAX_GLASS = 16
        const val MAX_DROPS = 6
        const val MAX_RINGS = 4
    }
}
