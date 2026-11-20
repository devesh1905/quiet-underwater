package app.quiet.launcher.feature.underwater.engine

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The glass: up to [n] SDF blobs (rounded rects, pills, circles), each a spring body. A body chases
 * its target centre and half size on a damped spring, squashes with its acceleration and stretches
 * along fast motion, and, when [floats], rides the local wave (bob and a slight roll). Bodies in the
 * same merge group (> 0) are smooth-min'd in the shader, so they fuse and split like metaballs; the
 * sim notices those moments and posts [EV_MERGE] / [EV_SPLIT] events (the host plays the haptics).
 * Struct-of-arrays, no allocation after construction. px, y down.
 */
internal class GlassBodies(val n: Int) {
    val used = BooleanArray(n)
    // Targets (set by the UI)
    val tx = FloatArray(n); val ty = FloatArray(n); val tw = FloatArray(n); val th = FloatArray(n)
    val corner = FloatArray(n)       // px; >= min(hw, hh) means pill / circle
    val bevel = FloatArray(n)        // px of dome at the rim
    val group = IntArray(n)          // 0: never merges; 1..3 merge groups
    val lens = FloatArray(n)         // 0 flat slab .. 1 strong magnifier
    val frost = FloatArray(n)        // 0 clear .. 1 frosted
    val floats = BooleanArray(n)
    val stir = BooleanArray(n)
    val tAlpha = FloatArray(n)
    val k = FloatArray(n)            // spring stiffness
    val zeta = FloatArray(n)         // damping ratio
    /** A finger holds it: the centre follows the target stiffly (drag). */
    val held = BooleanArray(n)
    /** Tracks its target exactly: no spring, no squash. */
    val rigid = BooleanArray(n)
    /** Condenses from alpha 0 on its first target (else appears at its target alpha). */
    val growIn = BooleanArray(n)
    /** Clear glass: only the bending at its edge shows (see GlassStyle.clear). */
    val clear = BooleanArray(n)

    // State: centre, half size, each with velocity
    val x = FloatArray(n); val vx = FloatArray(n)
    val y = FloatArray(n); val vy = FloatArray(n)
    val hw = FloatArray(n); val vhw = FloatArray(n)
    val hh = FloatArray(n); val vhh = FloatArray(n)
    val alpha = FloatArray(n); val valpha = FloatArray(n)
    val squash = FloatArray(n); val vsquash = FloatArray(n)
    val bob = FloatArray(n); val vbob = FloatArray(n)
    val rot = FloatArray(n); val vrot = FloatArray(n)
    /** 0..1 how deep the body sits in the water (bottom edge vs the waterline). */
    val sub = FloatArray(n)
    private val wasUnder = BooleanArray(n)
    private val fresh = BooleanArray(n)
    private val lastVy = FloatArray(n)
    private val lastVx = FloatArray(n)
    private val touching = Array(n) { BooleanArray(n) }

    // Events (type, a, b), a tiny ring buffer read by the host after each frame.
    val evType = IntArray(16); val evA = IntArray(16); val evB = IntArray(16)
    var evCount = 0
        private set

    fun obtain(): Int {
        for (i in 0 until n) if (!used[i]) {
            used[i] = true; fresh[i] = true; held[i] = false
            group[i] = 0; lens[i] = 0f; frost[i] = 0f; floats[i] = false; stir[i] = false
            tAlpha[i] = 1f; alpha[i] = 0f; valpha[i] = 0f
            k[i] = 260f; zeta[i] = 0.62f; rigid[i] = false; growIn[i] = true; clear[i] = false
            squash[i] = 0f; vsquash[i] = 0f; bob[i] = 0f; vbob[i] = 0f; rot[i] = 0f; vrot[i] = 0f
            tw[i] = 0f; th[i] = 0f
            for (j in 0 until n) { touching[i][j] = false; touching[j][i] = false }
            return i
        }
        return -1
    }

    /** Body [i] starts fused with [j] (no merge event; a split is posted when they part). */
    fun fuse(i: Int, j: Int) {
        if (i !in 0 until n || j !in 0 until n || i == j) return
        touching[i][j] = true; touching[j][i] = true
    }

    /** At its target exactly, at rest (rigid bodies, placeGlass). Bob and roll are kept. */
    fun place(i: Int) {
        x[i] = tx[i]; y[i] = ty[i]; hw[i] = tw[i]; hh[i] = th[i]
        vx[i] = 0f; vy[i] = 0f; vhw[i] = 0f; vhh[i] = 0f
        squash[i] = 0f; vsquash[i] = 0f; lastVx[i] = 0f; lastVy[i] = 0f
    }

    fun free(i: Int) { if (i in 0 until n) { used[i] = false; alpha[i] = 0f } }

    /** Place a body at its target immediately (first layout, reduced motion). */
    fun snap(i: Int) {
        x[i] = tx[i]; y[i] = ty[i]; hw[i] = tw[i]; hh[i] = th[i]
        vx[i] = 0f; vy[i] = 0f; vhw[i] = 0f; vhh[i] = 0f
        alpha[i] = tAlpha[i]; valpha[i] = 0f
    }

    private fun post(t: Int, a: Int, b: Int) {
        if (evCount >= 16) return
        evType[evCount] = t; evA[evCount] = a; evB[evCount] = b; evCount++
    }
    fun clearEvents() { evCount = 0 }

    fun energy(dp: Float): Float {
        var e = 0f
        for (i in 0 until n) {
            if (!used[i] && alpha[i] <= 0.002f) continue
            val dx = x[i] - tx[i]; val dy = y[i] - ty[i]
            e += (vx[i] * vx[i] + vy[i] * vy[i] + vhw[i] * vhw[i] + vhh[i] * vhh[i] + vbob[i] * vbob[i]) / (dp * dp) * 0.01f
            e += (dx * dx + dy * dy + (hw[i] - tw[i]).let { it * it } + (hh[i] - th[i]).let { it * it }) / (dp * dp) * 0.2f
            e += (vsquash[i] * vsquash[i] + valpha[i] * valpha[i] + vrot[i] * vrot[i]) * 30f
            e += abs(alpha[i] - (if (used[i]) tAlpha[i] else 0f)) * 30f
            if (held[i]) e += 100f
        }
        return e
    }

    private fun spring(v: Float, pos: Float, target: Float, kk: Float, z: Float, dt: Float): Float {
        val c = 2f * sqrt(kk) * z
        return v + (-kk * (pos - target) - c * v) * dt
    }

    fun step(dt: Float, s: WaterSim) {
        val dp = s.dp
        for (i in 0 until n) {
            val live = used[i]
            if (!live && alpha[i] <= 0.002f) continue
            if (fresh[i] && tw[i] > 0f) { snap(i); if (growIn[i]) alpha[i] = 0f; fresh[i] = false }
            valpha[i] = spring(valpha[i], alpha[i], if (live) tAlpha[i] else 0f, 140f, 0.9f, dt)
            alpha[i] = (alpha[i] + valpha[i] * dt).coerceIn(0f, 1f)
            if (rigid[i] && live) place(i) else {
                val kk = if (held[i]) 900f else k[i]
                val z = if (held[i]) 0.9f else zeta[i]
                vx[i] = spring(vx[i], x[i], tx[i], kk, z, dt); x[i] += vx[i] * dt
                vy[i] = spring(vy[i], y[i], ty[i], kk, z, dt); y[i] += vy[i] * dt
                // Size is a little softer than position, so a resize reads as liquid, not as a jump.
                vhw[i] = spring(vhw[i], hw[i], tw[i], kk * 0.8f, 0.55f, dt); hw[i] += vhw[i] * dt
                vhh[i] = spring(vhh[i], hh[i], th[i], kk * 0.8f, 0.55f, dt); hh[i] += vhh[i] * dt
                // Squash: vertical deceleration flattens it, fast sideways motion stretches it.
                val ay = (vy[i] - lastVy[i]) / dt; val ax = (vx[i] - lastVx[i]) / dt
                lastVy[i] = vy[i]; lastVx[i] = vx[i]
                val sTarget = ((abs(vx[i]) - abs(vy[i])) * 0.00009f / dp).coerceIn(-0.16f, 0.16f)
                vsquash[i] += (ay - ax * 0.3f) * 0.000004f / dp * dt * 60f
                vsquash[i] = spring(vsquash[i], squash[i], sTarget, 320f, 0.28f, dt)
                squash[i] = (squash[i] + vsquash[i] * dt).coerceIn(-0.24f, 0.24f)
            }
            // Buoyancy: how deep the bottom edge sits, then bob with the wave and roll with its slope.
            val wy = s.waterY(x[i])
            val bottom = y[i] + hh[i]
            sub[i] = ((bottom - wy) / max(2f * hh[i], 1f)).coerceIn(0f, 1f)
            if (floats[i]) {
                val wet = smooth(0f, 0.12f, sub[i]) * (1f - 0.55f * smooth(0.9f, 1f, sub[i]))
                val wave = (wy - s.line[0]).coerceIn(-26f * dp, 26f * dp)
                vbob[i] = spring(vbob[i], bob[i], wave * 0.85f * wet, 70f, 0.32f, dt); bob[i] += vbob[i] * dt
                val sl = s.slope(x[i], max(hw[i] * 1.6f, 8f * dp))
                val rt = (atan(sl) * 0.55f * wet).coerceIn(-0.06f, 0.06f)
                vrot[i] = spring(vrot[i], rot[i], rt, 60f, 0.4f, dt); rot[i] += vrot[i] * dt
            } else {
                vbob[i] = spring(vbob[i], bob[i], 0f, 120f, 0.8f, dt); bob[i] += vbob[i] * dt
                vrot[i] = spring(vrot[i], rot[i], 0f, 120f, 0.8f, dt); rot[i] += vrot[i] * dt
            }
            // Crossing the waterline while held: the host plays a rising texture.
            val under = y[i] > wy
            if (live && held[i] && under != wasUnder[i]) post(EV_CROSS, i, if (under) 1 else 0)
            wasUnder[i] = under
            // Moving glass in the water stirs it.
            if (live && stir[i] && under) {
                val sp = hypot(vx[i], vy[i]) / dp
                if (sp > 60f) s.ripple(x[i], y[i] + bob[i], min(sp * 0.25f, 120f) * dt * 20f, 1.6f)
            }
        }
        // Fuse and split within merge groups.
        for (i in 0 until n) {
            if (!used[i] || group[i] == 0) continue
            for (j in i + 1 until n) {
                if (!used[j] || group[j] != group[i]) continue
                val gap = gapOf(i, j)
                val t = touching[i][j]
                val r = 6f * dp
                if (!t && gap < -r * 0.2f) { touching[i][j] = true; post(EV_MERGE, i, j) }
                else if (t && gap > r * 1.6f) { touching[i][j] = false; post(EV_SPLIT, i, j) }
            }
        }
    }

    /** Approximate gap between two rounded boxes (px; negative = overlapping). */
    fun gapOf(i: Int, j: Int): Float {
        val dx = abs(x[i] - x[j]) - hw[i] - hw[j]
        val dy = abs((y[i] + bob[i]) - (y[j] + bob[j])) - hh[i] - hh[j]
        val ci = min(corner[i], min(hw[i], hh[i])); val cj = min(corner[j], min(hw[j], hh[j]))
        val c = min(ci, cj)
        val ox = max(dx + c, 0f); val oy = max(dy + c, 0f)
        return sqrt(ox * ox + oy * oy) + min(max(dx, dy) + c, 0f) - c
    }

    fun settle(s: WaterSim) {
        for (i in 0 until n) {
            if (!used[i]) { alpha[i] = 0f; continue }
            snap(i); squash[i] = 0f; vsquash[i] = 0f; bob[i] = 0f; vbob[i] = 0f; rot[i] = 0f; vrot[i] = 0f
            val wy = s.waterY(x[i])
            sub[i] = ((y[i] + hh[i] - wy) / max(2f * hh[i], 1f)).coerceIn(0f, 1f)
        }
    }

    private fun smooth(a: Float, b: Float, v: Float): Float {
        val t = ((v - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3f - 2f * t)
    }

    companion object {
        const val EV_MERGE = 1
        const val EV_SPLIT = 2
        const val EV_CROSS = 3
    }
}
