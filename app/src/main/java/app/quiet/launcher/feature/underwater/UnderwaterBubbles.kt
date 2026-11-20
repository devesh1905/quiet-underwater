package app.quiet.launcher.feature.underwater

import android.content.Context
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import app.quiet.launcher.designsystem.Feel
import app.quiet.launcher.designsystem.Q
import app.quiet.launcher.designsystem.QType
import app.quiet.launcher.designsystem.pressable
import app.quiet.launcher.feature.notifications.BubbleNote
import app.quiet.launcher.feature.notifications.NotificationBubbles
import app.quiet.launcher.feature.underwater.engine.GlassBodies
import app.quiet.launcher.feature.underwater.engine.GlassStyle
import app.quiet.launcher.feature.underwater.engine.WaterHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/** Bubbles shown at most; more notifications fold into the newest-but-one bubble (shown as a count). */
private const val MAX_BUBBLES = 4

// Modes of a bubble.
private const val RISE = 0   // out of the USB port, up through the water
private const val REST = 1   // beaded on the dock top (the engine's spring holds it there)
private const val CARD = 2   // opened into the frosted card
private const val HELD = 3   // under a finger
private const val FREE = 4   // thrown: gravity in air, buoyancy in water, bounces
private const val HOP = 5    // springing home along its own path (after the surface, or out of the water)
private const val GONE = 6

// The bubble is a thin ring of water around the app's icon: clear glass (only the bending at its
// edge shows; no rim, specular, dome shading or shadow) with almost no magnification.
private val RISING = GlassStyle(corner = 999.dp, bevel = 9.dp, lens = 0.1f, stir = true, stiffness = 1100f, damping = 0.85f, clear = true)
private val RESTING = GlassStyle(corner = 999.dp, bevel = 9.dp, lens = 0.1f, stiffness = 210f, damping = 0.42f, clear = true)
/** Moving under our own physics: a stiff follow, so the engine's squash shows every bounce. */
private val FLYING = GlassStyle(corner = 999.dp, bevel = 9.dp, lens = 0.1f, stir = true, stiffness = 1300f, damping = 0.72f, clear = true)
/** Near another bubble while held: the merge group, so the two fuse like drops (the engine plays Plunk). */
private const val MERGE_GROUP = 3
private const val PI_F = Math.PI.toFloat()
/** Fully frosted: the type on the top layer reads over a blur, never over sharp Home text. */
private val CARD_GLASS = GlassStyle(corner = 26.dp, bevel = 16.dp, frost = 1f, stiffness = 240f, damping = 0.66f)
/** Black over the frost (the scrim under it adds ~0.4 more; Home's words fade out): ink and 72 % ink stay well above 4.5:1. */
private const val CARD_TINT = 0.64f

/** One bubble on Home: one notification or several merged. World px. */
internal class Bubble(val uid: Long) {
    /** Notification keys in it (feed order, newest first). */
    val keys = mutableStateListOf<String>()
    var id = -1
    var x = 0f; var y = 0f; var vx = 0f; var vy = 0f
    var mode by mutableIntStateOf(REST)
    /** Where it rests on the dock top (world x); NaN: its slot. */
    var homeX = Float.NaN
    var phase = ((uid % 628) / 100f)
    /** Waiting to rise once Home shows (it arrived while Home was hidden). */
    var risePending = false
    // Impact squash (decays), the swell while held, and a merge pulse.
    var squash = 0f; var squashY = true
    var swell by mutableFloatStateOf(0f)
    var pulse = 0f
    var calm = 0f
    var wet = false
    var age = 0f
    var grab = Offset.Zero
    var group = 0
    /** The app it is from (bubbles of one app merge; of different apps they bounce). */
    var pkg = ""
    /** Radius (px): a bubble holding more notes is a little bigger, and heavier (mass ~ radius²). */
    var rad = 0f
    /** The icon's roll inside the glass (radians) and its spin, from glancing hits. */
    var spin by mutableFloatStateOf(0f)
    var vspin = 0f
    var dragT = 0L
}

private class BubbleCardState(val bubble: Bubble) {
    var rect by mutableStateOf(Rect.Zero)
    var closing by mutableStateOf(false)
    val fade = Animatable(0f)
}

/**
 * New notifications rise from the USB port as glass bubbles carrying the app's icon: buoyancy against
 * drag, a sway that grows with speed, a stirred trail, then a ripple and a ring where they break the
 * surface, and they spring up to bead on the dock. They are glass you can play with: grab one (Clink),
 * drag it anywhere (it follows on a spring and squashes), flick it: it flies with its momentum, falls
 * in the air, bounces off the screen edges and the dock (a tick), splashes into the water, bobs back
 * up and hops home. Bubbles of different apps are glass balls to each other: they collide (mass ~ r²,
 * restitution 0.75, a little friction that spins the icons, a squash along the hit, a Clink as hard
 * as the hit, a ripple under water), in flight, rising, and on the dock where they shove each other
 * aside. Bubbles of the same app fuse instead (Plunk) into one bubble holding both. Flicked off the
 * bottom of the screen it is dismissed with a splash. A tap pops it open into a frosted card.
 */
@Composable
internal fun BubbleLayer(dock: DockState, active: Boolean, reduced: Boolean, modifier: Modifier) {
    val host = dock.host
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val notes by NotificationBubbles.items.collectAsState()
    val bubbles = remember { mutableStateListOf<Bubble>() }
    val kick = remember { Channel<Unit>(Channel.CONFLATED) }
    var card by remember { mutableStateOf<BubbleCardState?>(null) }
    var nextUid by remember { mutableLongStateOf(1L) }
    val dp = host.dp
    val r = 21f * dp
    val hidden = !active || dock.open != null
    val hiddenNow by rememberUpdatedState(hidden)
    val reducedNow by rememberUpdatedState(reduced)

    // ---- where bubbles rest: read live (the dock moves with the battery level and the layout)
    fun restY() = dock.slabTop - r - 5f * dp
    fun restY(b: Bubble) = dock.slabTop - b.rad - 5f * dp
    fun radOf(b: Bubble) = r * min(1.3f, 1f + 0.1f * (b.keys.size - 1).coerceAtLeast(0))
    fun minX() = dock.dockLeft + r + 8f * dp
    /** The right end of the bubbles' row: the dock's edge, or short of the battery label sharing the row. */
    fun maxX() = max(minX(), min(dock.dockRight - r - 8f * dp, dock.shelfRight.let { if (it.isNaN()) Float.MAX_VALUE else it - r }))
    fun slotX(i: Int) = dock.dockLeft + 16f * dp + r + i * (2f * r + 11f * dp)

    /** Home spots for the resting bubbles: their own spot or slot, pushed apart along the dock top. */
    fun homes(): Map<Bubble, Float> {
        val list = bubbles.filter { it.mode != GONE }
        val want = list.mapIndexed { i, b -> b to (if (b.homeX.isNaN()) slotX(i) else b.homeX).coerceIn(minX(), maxX()) }
            .sortedBy { it.second }.toMutableList()
        fun gap(i: Int, j: Int) = want[i].first.rad + want[j].first.rad + 8f * dp
        for (pass in 0 until 3) {
            for (i in 1 until want.size) if (want[i].second - want[i - 1].second < gap(i, i - 1)) want[i] = want[i].first to want[i - 1].second + gap(i, i - 1)
            for (i in want.size - 1 downTo 0) {
                val hi = if (i == want.size - 1) maxX() else want[i + 1].second - gap(i, i + 1)
                if (want[i].second > hi) want[i] = want[i].first to hi
            }
        }
        return want.toMap()
    }

    fun aim(b: Bubble, x: Float, y: Float) {
        val id = b.id
        if (id < 0) return
        val q = b.squash; val p = 1f + b.pulse + b.swell * 0.08f
        val rb = if (b.rad > 0f) b.rad else r
        val hw = rb * p * (if (b.squashY) 1f + q else 1f - q)
        val hh = rb * p * (if (b.squashY) 1f - q else 1f + q)
        host.moveGlass(id, x, y, hw, hh)
    }

    fun settleHomes() {
        val h = homes()
        for (b in bubbles) {
            if (b.mode != REST || b.id < 0) continue
            val hx = h[b] ?: continue
            b.x = hx; b.y = restY(b)
            aim(b, b.x, b.y)
        }
    }

    /**
     * Tells Home how much room the resting row needs above the dock (so the dock keeps it clear of
     * Home's words) and how wide it is (so the battery label beside it never sits under a bubble).
     */
    fun publishShelf() {
        // Hidden (another page, a sheet) keeps the room: Home doesn't shift each time it comes back.
        val live = bubbles.filter { it.mode != GONE }
        if (live.isEmpty()) { dock.shelf = 0f; dock.shelfWidth = 0f; return }
        val big = live.maxOf { if (it.rad > 0f) it.rad else r }
        dock.shelf = 2f * big + 5f * dp + 10f * dp
        dock.shelfWidth = 16f * dp + live.sumOf { 2.0 * (if (it.rad > 0f) it.rad else r) }.toFloat() + 8f * dp * (live.size - 1) + 8f * dp
    }

    fun setGroup(b: Bubble, g: Int) {
        if (b.group == g || b.id < 0) return
        b.group = g
        val base = when (b.mode) { REST -> RESTING; RISE -> RISING; else -> FLYING }
        host.styleGlass(b.id, base.copy(group = g))
    }

    fun release(b: Bubble, pop: Boolean) {
        val id = b.id
        b.id = -1
        if (id < 0) return
        if (pop) { host.ring(host.glassCx(id), host.glassCy(id), 0.35f); host.feel(Feel.Pop) }
        host.moveGlass(id, host.glassCx(id), host.glassCy(id), r * 0.3f, r * 0.3f)
        host.glassAlpha(id, 0f)
        scope.launch { delay(420); host.freeGlass(id) }
    }

    /** Gives [b] a glass body: rising out of the port, or straight at home. */
    fun appear(b: Bubble, rise: Boolean) {
        if (b.id >= 0) return
        b.group = 0
        b.id = host.obtainGlass(if (rise) RISING else RESTING)
        if (b.id < 0) { b.mode = REST; b.x = homes()[b] ?: slotX(0); b.y = restY(b); return }
        if (rise) {
            b.x = host.w / 2f + ((b.uid % 5) - 2) * 3f * dp; b.y = host.h + r
            b.vx = 0f; b.vy = -260f * dp; b.mode = RISE; b.age = 0f
            host.moveGlass(b.id, b.x, b.y, r * 0.7f, r * 0.7f)
            kick.trySend(Unit)
        } else {
            b.mode = REST
            b.x = homes()[b] ?: slotX(0); b.y = restY(b)
            aim(b, b.x, b.y)
        }
    }

    // ---- reconcile the bubbles with the feed
    LaunchedEffect(notes, hidden) {
        val live = notes.map { it.key }.toSet()
        val openKey = card?.bubble
        // Notes that went away leave their bubble; an empty bubble pops.
        bubbles.toList().forEach { b ->
            b.keys.retainAll { it in live }
            if (b.keys.isEmpty() && b !== openKey && b.mode != GONE) {
                b.mode = GONE; release(b, pop = !hidden); bubbles.remove(b)
            }
        }
        // New notes: into their app's bubble if it has one, else a bubble of their own while there
        // is room, else (more apps than room) into the last bubble as a stack.
        val have = bubbles.flatMap { it.keys }.toSet()
        for (n in notes.asReversed()) {
            if (n.key in have) continue
            val mine = bubbles.firstOrNull { it.mode != GONE && it.mode != CARD && it.pkg == n.packageName }
            if (mine != null) {
                mine.keys.add(0, n.key)
                mine.pulse = 0.16f
                kick.trySend(Unit)
            } else if (bubbles.count { it.mode != GONE } < MAX_BUBBLES) {
                val b = Bubble(nextUid++)
                b.keys.add(n.key)
                b.pkg = n.packageName
                b.risePending = !reduced
                bubbles.add(b)
            } else {
                val into = bubbles.lastOrNull { it.mode != GONE && it.mode != CARD } ?: bubbles.last()
                into.keys.add(0, n.key)
                into.pulse = 0.16f
                kick.trySend(Unit)
            }
        }
        // Keys in feed order (newest first) inside every bubble.
        val order = notes.withIndex().associate { it.value.key to it.index }
        bubbles.forEach { b -> val sorted = b.keys.sortedBy { order[it] ?: Int.MAX_VALUE }; if (sorted != b.keys.toList()) { b.keys.clear(); b.keys.addAll(sorted) } }
        val pkgOf = notes.associate { it.key to it.packageName }
        bubbles.forEach { b -> b.keys.firstOrNull()?.let { k -> pkgOf[k]?.let { b.pkg = it } }; b.rad = radOf(b) }
        if (hidden) {
            bubbles.forEach { b -> if (b.id >= 0 && b.mode != CARD) release(b, pop = false); if (b.mode != CARD) b.mode = REST }
            card?.let { c -> card = null; c.bubble.mode = REST; if (c.bubble.id >= 0) release(c.bubble, false) }
            dock.cardOpen = false
            publishShelf()
            return@LaunchedEffect
        }
        bubbles.forEach { b ->
            if (b.id < 0 && b.mode != GONE && b.mode != CARD) { appear(b, rise = b.risePending && !reduced); b.risePending = false }
        }
        settleHomes()
        publishShelf()
    }
    // The dock moved (battery level, layout) or the label beside the row changed width: the resting bubbles follow.
    LaunchedEffect(Unit) {
        snapshotFlow { listOf(dock.slabTop, dock.dockLeft, dock.dockRight, dock.shelfRight) }.collect { if (!hiddenNow) settleHomes() }
    }
    DisposableEffect(Unit) { onDispose { bubbles.forEach { b -> if (b.id >= 0) { host.freeGlass(b.id); b.id = -1 } }; dock.shelf = 0f; dock.shelfWidth = 0f } }

    // ---- merging: a held bubble fuses with the one it is dragged into (the engine posts EV_MERGE)
    fun merge(from: Bubble, into: Bubble) {
        if (from.mode == GONE || into.mode == GONE) return
        val keys = from.keys.toList()
        into.keys.addAll(0, keys.filter { it !in into.keys })
        val order = NotificationBubbles.items.value.withIndex().associate { it.value.key to it.index }
        val sorted = into.keys.sortedBy { order[it] ?: Int.MAX_VALUE }; into.keys.clear(); into.keys.addAll(sorted)
        from.mode = GONE
        val id = from.id; from.id = -1
        if (id >= 0) {
            host.holdGlass(id, false)
            host.moveGlass(id, host.glassCx(into.id), host.glassCy(into.id), r * 0.45f, r * 0.45f)
            host.glassAlpha(id, 0f)
            scope.launch { delay(380); host.freeGlass(id) }
        }
        bubbles.remove(from)
        into.rad = radOf(into)
        publishShelf()
        into.pulse = 0.2f
        host.feel(Feel.Plunk)
        host.ring(host.glassCx(into.id), host.glassCy(into.id), 0.45f)
        host.ripple(host.glassCx(into.id), host.glassCy(into.id), 0.5f)
        scope.launch { delay(260); setGroup(into, 0) }
        kick.trySend(Unit)
    }
    DisposableEffect(host) {
        val l: (Int, Int, Int) -> Unit = { type, a, b ->
            if (type == GlassBodies.EV_MERGE) {
                val ba = bubbles.firstOrNull { it.id == a }; val bb = bubbles.firstOrNull { it.id == b }
                // Only one app's bubbles fuse; different apps bounce off each other instead.
                if (ba != null && bb != null && ba.pkg == bb.pkg) {
                    if (ba.mode == HELD) merge(ba, bb) else if (bb.mode == HELD) merge(bb, ba)
                }
            }
        }
        host.addGlassListener(l)
        onDispose { host.removeGlassListener(l) }
    }

    // ---- collisions: bubbles of different apps are glass balls that knock into each other
    fun collides(b: Bubble) = b.id >= 0 && (b.mode == RISE || b.mode == REST || b.mode == HELD || b.mode == FREE || b.mode == HOP)
    /** 1/mass, mass ~ radius² (a held bubble is moved by the finger: infinite mass). */
    fun invMass(b: Bubble): Float = if (b.mode == HELD) 0f else { val k = (if (b.rad > 0f) b.rad else r) / r; 1f / (k * k) }
    /** A resting bubble that is hit leaves the dock top and flies (it lands again where it may). */
    fun wake(b: Bubble) {
        if (b.mode != REST) return
        b.mode = FREE; b.calm = 0f; b.age = 0f
        b.wet = host.isUnder(b.x, b.y + b.rad)
        host.styleGlass(b.id, FLYING.copy(group = b.group))
    }

    /**
     * One pass over every pair: circle against circle. Same app: they fuse (when one is moving into
     * the other). Different apps: the overlap is pushed apart by inverse mass (they never sink into
     * each other), and an impulse along the contact normal bounces them (restitution 0.75 on the
     * first pass), with a little friction along the contact that sets the icons spinning. The first
     * pass also squashes both along the hit, knocks (a Clink as hard as the hit) and, under water,
     * ripples where they met. Several passes per step settle chains (A hits B hits C).
     */
    fun collide(all: Array<Bubble>, pass: Int) {
        for (i in all.indices) for (j in i + 1 until all.size) {
            val a = all[i]; val b = all[j]
            if (!collides(a) || !collides(b)) continue
            if (a.mode == REST && b.mode == REST) continue
            if (a.mode == HELD && b.mode == HELD) continue
            val dx = b.x - a.x; val dy = b.y - a.y
            val d = hypot(dx, dy)
            val rr = a.rad + b.rad
            if (d >= rr) continue
            if (a.pkg.isNotEmpty() && a.pkg == b.pkg) {
                // One app: the moving one flows into the other.
                if (d < rr * 0.9f) {
                    val aMoves = a.mode == HELD || (a.mode != REST && b.mode == REST) ||
                        (b.mode != HELD && a.mode != REST && hypot(a.vx, a.vy) >= hypot(b.vx, b.vy))
                    if (aMoves) merge(a, b) else merge(b, a)
                }
                continue
            }
            val nx: Float; val ny: Float
            if (d > 1e-3f) { nx = dx / d; ny = dy / d } else { nx = 0f; ny = -1f }
            if (a.mode == REST) wake(a)
            if (b.mode == REST) wake(b)
            val wa = invMass(a); val wb = invMass(b)
            val w = wa + wb
            if (w <= 0f) continue
            // Push apart (a hair of slop keeps resting contact from jittering).
            val corr = max(rr - d - 0.4f, 0f) / w * 0.9f
            a.x -= nx * corr * wa; a.y -= ny * corr * wa
            b.x += nx * corr * wb; b.y += ny * corr * wb
            val rvx = b.vx - a.vx; val rvy = b.vy - a.vy
            val vn = rvx * nx + rvy * ny
            if (vn >= 0f) continue
            val e = if (pass == 0 && -vn > 60f * dp) 0.75f else 0f
            val jn = -(1f + e) * vn / w
            a.vx -= jn * wa * nx; a.vy -= jn * wa * ny
            b.vx += jn * wb * nx; b.vy += jn * wb * ny
            // Friction along the contact, and the spin it gives each icon (a solid ball: I = 0.4 m r²).
            val tx = -ny; val ty = nx
            val vt = rvx * tx + rvy * ty
            val jt = (-vt / w).coerceIn(-0.12f * jn, 0.12f * jn)
            a.vx -= jt * wa * tx; a.vy -= jt * wa * ty
            b.vx += jt * wb * tx; b.vy += jt * wb * ty
            a.vspin += -jt * wa / (0.4f * a.rad)
            b.vspin += -jt * wb / (0.4f * b.rad)
            if (pass == 0) {
                val hit = -vn
                if (hit > 90f * dp) {
                    val vertical = abs(ny) > abs(nx)
                    impact(a, hit * 2f * wa / w, vertical, dp)
                    impact(b, hit * 2f * wb / w, vertical, dp)
                    host.clink(((hit / dp - 90f) / 2400f).coerceIn(0.12f, 1f))
                    val cx = a.x + nx * a.rad; val cy = a.y + ny * a.rad
                    if (host.isUnder(cx, cy)) host.ripple(cx, cy, (hit / (2600f * dp)).coerceIn(0.2f, 0.9f))
                }
            }
        }
    }

    // ---- the physics: our own fixed 120 Hz steps while anything rises, flies, hops or is held; then nothing runs
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        var last = 0L
        var acc = 0f
        val step = 1f / 120f
        while (true) {
            val busy = bubbles.any {
                it.id >= 0 && (it.mode == RISE || it.mode == FREE || it.mode == HOP || it.mode == HELD ||
                    it.pulse > 0.002f || it.squash > 0.002f || abs(it.vspin) > 0.02f || (it.mode == REST && abs(wrapPi(it.spin)) > 0.01f))
            }
            if (!busy) { last = 0L; acc = 0f; kick.receive(); continue }
            if (app.quiet.launcher.feature.underwater.engine.WaterDebug.logFeel) bubbleDiag(bubbles)
            withFrameNanos { t ->
                val dt = if (last == 0L) 1f / 120f else ((t - last) / 1e9f).coerceIn(0.001f, 0.05f)
                last = t
                acc += dt
                var homes = homes()
                var dirty = false
                var n = 0
                val all = bubbles.toTypedArray()
                while (acc >= step && n < 8) {
                    acc -= step; n++
                    if (dirty) { homes = homes(); dirty = false }
                    // 1. Move each bubble by its own rules.
                    for (b in all) {
                        if (b.id < 0) continue
                        if (b.rad <= 0f) b.rad = radOf(b)
                        val hx = homes[b] ?: slotX(0)
                        when (b.mode) {
                            RISE -> stepRise(host, b, step, hx, b.rad, onSurface = { b.mode = HOP; b.group = 0; host.styleGlass(b.id, FLYING) })
                            FREE -> stepFree(host, dock, b, step, b.rad, reducedNow,
                                onRest = { onDock ->
                                    if (onDock) { b.homeX = b.x.coerceIn(minX(), maxX()); b.mode = REST; dirty = true; host.styleGlass(b.id, RESTING.copy(group = b.group)) }
                                    else { b.mode = HOP; host.feel(Feel.Drip) }
                                },
                                onOut = {
                                    // Off the bottom: a splash, and the notifications go.
                                    b.mode = GONE
                                    host.ripple(b.x.coerceIn(0f, host.w), host.h - 26f * dp, 1.5f)
                                    host.ring(b.x.coerceIn(0f, host.w), host.h - 8f * dp, 0.6f)
                                    host.feel(Feel.Plunk)
                                    val ns = NotificationBubbles.items.value.filter { it.key in b.keys }
                                    val id = b.id; b.id = -1
                                    host.glassAlpha(id, 0f); scope.launch { delay(300); host.freeGlass(id) }
                                    bubbles.remove(b); publishShelf()
                                    ns.forEach { NotificationBubbles.dismiss(it) }
                                })
                            HOP -> stepHop(b, step, hx, restY(b), b.rad) { b.mode = REST; dirty = true; host.styleGlass(b.id, RESTING.copy(group = b.group)) }
                            REST -> { if (!dirty) b.x = hx; b.y = restY(b); b.vx = 0f; b.vy = 0f }
                            else -> {}
                        }
                    }
                    // 2. Knock them off each other: a few passes, so chains settle within the step.
                    for (pass in 0 until 4) collide(all, pass)
                    // 3. Spin, decay, and hand every target to its glass.
                    for (b in all) {
                        if (b.id < 0 || b.mode == GONE || b.mode == CARD) continue
                        b.squash *= exp(-step * 15f)
                        b.pulse *= exp(-step * 9f)
                        if (b.mode == REST) {
                            // At rest the icon rights itself (it floats upright in its bubble).
                            val a = ((b.spin + PI_F) % (2f * PI_F) + 2f * PI_F) % (2f * PI_F) - PI_F
                            b.vspin += (-a * 9f - 3.6f * b.vspin) * step
                        } else b.vspin *= exp(-step * (if (b.wet) 2.4f else 0.7f))
                        b.spin += b.vspin * step
                        // Keep the roll in -pi..pi: an icon that rolled a full turn rests upright at 2pi,
                        // which the idle check must read as upright (it once kept this loop at 120 Hz forever).
                        if (abs(b.spin) > PI_F) b.spin = wrapPi(b.spin)
                        if (b.mode != RISE && b.mode != HELD) aim(b, b.x, b.y)
                    }
                }
                // The icons' roll is drawn on the water's frame clock.
                if (all.any { abs(it.vspin) > 0.02f }) host.wake()
            }
        }
    }

    // ---- grabbing, dragging, throwing
    fun grab(b: Bubble, finger: Offset): Boolean {
        if (b.id < 0 || card != null || b.mode == GONE || b.mode == CARD) return false
        host.feel(Feel.Clink)
        val cx = host.glassCx(b.id); val cy = host.glassCy(b.id)
        b.grab = Offset(cx, cy) - finger
        b.x = cx; b.y = cy; b.vx = 0f; b.vy = 0f
        b.dragT = 0L
        b.mode = HELD
        b.swell = 1f
        host.styleGlass(b.id, FLYING.copy(group = b.group))
        host.holdGlass(b.id, true)
        aim(b, b.x, b.y)
        return true
    }

    fun drag(b: Bubble, finger: Offset) {
        if (b.mode != HELD) return
        val p = finger + b.grab
        val nx = p.x.coerceIn(r * 0.6f, host.w - r * 0.6f); val ny = p.y.coerceIn(dock.bounds.top + r * 0.6f, host.h + r)
        // The finger's velocity: what a held bubble hits others with.
        val now = System.nanoTime()
        if (b.dragT != 0L) {
            val dtd = ((now - b.dragT) / 1e9f).coerceIn(0.004f, 0.1f)
            b.vx = b.vx * 0.4f + (nx - b.x) / dtd * 0.6f; b.vy = b.vy * 0.4f + (ny - b.y) / dtd * 0.6f
        }
        b.dragT = now
        b.x = nx; b.y = ny
        aim(b, b.x, b.y)
        kick.trySend(Unit)
        // Near another bubble the two join the merge group, so they neck and fuse as they touch.
        // Only the nearest one, so two resting neighbours never neck into each other.
        var best: Bubble? = null
        var bestD = 2f * r + 22f * dp
        for (o in bubbles) {
            if (o === b || o.id < 0 || o.mode == GONE || o.mode == CARD || o.pkg != b.pkg) continue
            val d = hypot(host.glassCx(o.id) - b.x, host.glassCy(o.id) - b.y)
            if (d < bestD) { bestD = d; best = o }
        }
        for (o in bubbles) if (o !== b) setGroup(o, if (o === best) MERGE_GROUP else 0)
        setGroup(b, if (best != null) MERGE_GROUP else 0)
    }

    fun letGo(b: Bubble, v: Offset, cancelled: Boolean) {
        if (b.mode != HELD) return
        b.swell = 0f
        if (b.id >= 0) host.holdGlass(b.id, false)
        val maxV = 7000f * dp
        b.vx = v.x.coerceIn(-maxV, maxV); b.vy = v.y.coerceIn(-maxV, maxV)
        if (cancelled) { b.vx = 0f; b.vy = 0f }
        b.mode = FREE; b.calm = 0f; b.age = 0f
        b.wet = host.isUnder(b.x, b.y + r)
        bubbles.forEach { o -> if (o !== b) setGroup(o, 0) }
        scope.launch { delay(200); if (b.mode != HELD) setGroup(b, 0) }
        kick.trySend(Unit)
    }

    fun openCard(b: Bubble) {
        if (card != null || b.id < 0) return
        val c = BubbleCardState(b)
        b.swell = 0f
        host.holdGlass(b.id, false)
        host.feel(Feel.Pop)
        host.ring(host.glassCx(b.id), host.glassCy(b.id), 0.6f)
        val count = b.keys.size
        val h = if (count <= 1) 210f * dp else (64f + count.coerceAtMost(4) * 74f + 8f) * dp
        val bottom = dock.slabTop - 10f * dp
        val top = (bottom - h).coerceAtLeast(dock.bounds.top + 8f * dp)
        c.rect = Rect(dock.dockLeft, top, dock.dockRight, top + h)
        b.mode = CARD
        setGroup(b, 0)
        host.styleGlass(b.id, CARD_GLASS)
        host.moveGlass(b.id, c.rect.center.x, c.rect.center.y, c.rect.width / 2f, c.rect.height / 2f)
        bubbles.forEach { o -> if (o !== b && o.id >= 0) host.glassAlpha(o.id, 0f) }
        card = c
        dock.cardOpen = true
        scope.launch { c.fade.animateTo(1f, tween(240, 110)) }
    }

    /** How the card goes: 0 back into its bubble, 1 opened (it shrinks away), 2 dismissed (it drips into the water). */
    fun closeCard(how: Int) {
        val c = card ?: return
        if (c.closing) return
        c.closing = true
        dock.cardOpen = false
        val b = c.bubble
        scope.launch {
            launch { c.fade.animateTo(0f, tween(110)) }
            val cx = c.rect.center.x; val cy = c.rect.center.y
            val stillThere = how == 0 && b.keys.any { k -> NotificationBubbles.items.value.any { it.key == k } }
            if (stillThere && b.id >= 0) {
                host.styleGlass(b.id, RESTING)
                b.mode = REST
                settleHomes()
                host.feel(Feel.Clink)
            } else if (b.id >= 0) {
                val id = b.id; b.id = -1
                b.mode = GONE
                host.moveGlass(id, cx, cy, 10f * dp, 10f * dp)
                if (how == 2) host.drop(cx, cy, { _, _ -> }, radius = 7.dp, fallMs = 240) else host.feel(Feel.Clink)
                delay(150); host.glassAlpha(id, 0f); delay(400); host.freeGlass(id)
            }
            delay(60)
            bubbles.forEach { o -> if (o !== b && o.id >= 0) host.glassAlpha(o.id, 1f) }
            if (card === c) card = null
            if (b.mode == GONE) { bubbles.remove(b); publishShelf() }
        }
    }

    BackHandler(enabled = card != null && card?.closing == false) { closeCard(0) }

    // In the world: the icons riding the bubbles (the glass lens bends them), and their touch.
    Box(modifier) {
        for (b in bubbles) key(b.uid) {
            if (b.mode != CARD && b.mode != GONE && !hidden) {
                val note = notes.firstOrNull { it.key == b.keys.firstOrNull() }
                BubbleFace(host, dock, b, r, note, count = b.keys.size,
                    onGrab = { f -> grab(b, f) }, onDrag = { f -> drag(b, f) },
                    onRelease = { v, cancelled, tap ->
                        if (tap && b.mode == HELD) {
                            b.mode = REST; b.swell = 0f; host.holdGlass(b.id, false); setGroup(b, 0)
                            openCard(b)
                        } else letGo(b, v, cancelled)
                    })
            }
        }
        // How many each holds: small plain text centred under its bubble, upright (it never rolls),
        // drawn after every bubble so a neighbour can never cover it.
        for (b in bubbles) key("n" + b.uid) {
            if (b.mode != CARD && b.mode != GONE && !hidden && b.keys.size > 1) BubbleCount(host, dock, b, r, b.keys.size)
        }
    }
    // Above the world: the card's tint and words (crisp over its frosted glass).
    val c = card
    if (c != null) OnWaterTop("bubble-card", z = 2) {
        val live = notes.filter { it.key in c.bubble.keys }
        LaunchedEffect(live.isEmpty()) { if (live.isEmpty()) closeCard(2) }
        Box(Modifier.fillMaxSize()) {
            if (!c.closing) Box(Modifier.fillMaxSize().pointerInput(c) { detectTapGestures { closeCard(0) } })
            BubbleCard(host, c, live,
                onResize = { hPx ->
                    val bottom = c.rect.bottom
                    val top = (bottom - hPx).coerceAtLeast(dock.bounds.top + 8f * dp)
                    if (abs(c.rect.height - hPx) > 1f && !c.closing) {
                        c.rect = Rect(c.rect.left, top, c.rect.right, top + hPx)
                        host.moveGlass(c.bubble.id, c.rect.center.x, c.rect.center.y, c.rect.width / 2f, c.rect.height / 2f)
                    }
                },
                onOpen = { n -> NotificationBubbles.open(ctx, n); closeCard(1) },
                onDismiss = { n -> if (live.size <= 1) closeCard(2); NotificationBubbles.dismiss(n) },
                onDismissAll = { closeCard(2); live.forEach { NotificationBubbles.dismiss(it) } },
            )
        }
    }
}

// ------------------------------------------------------------------------------------ physics

/** One substep of a rising bubble: buoyancy against drag, sway, drift under its home; breaking the surface hands it on. */
private fun stepRise(host: WaterHost, b: Bubble, dt: Float, homeX: Float, r: Float, onSurface: () -> Unit) {
    val dp = host.dp
    b.age += dt
    // Buoyancy against drag: terminal speed ~430 dp/s, reached in about half a second.
    b.vy += (-1150f * dp - 2.7f * b.vy) * dt
    // A soft pull toward its home, so it arrives under where it will rest.
    b.vx += ((homeX - b.x) * 2.4f - 1.9f * b.vx) * dt
    b.x += b.vx * dt; b.y += b.vy * dt
    val speed = (-b.vy / (380f * dp)).coerceIn(0f, 1f)
    b.phase += dt * (7.5f + 4f * speed)
    // Sway: bubbles zig-zag as they rise (wake shedding); the glass squash comes from the engine.
    val sway = sin(b.phase) * 6f * dp * speed
    val px = b.x + sway
    // Breathing in size as it sways: a touch wider on the turns.
    val breathe = 1f + 0.06f * sin(b.phase * 2f)
    host.moveGlass(b.id, px, b.y, r * breathe, r / breathe)
    val surf = host.waterY(px)
    if (b.y - r * 0.15f <= surf || b.age > 4f) {
        // It breaks the surface: rings in the water and on it, a drip under the finger tips.
        host.ripple(px, surf + 3f * dp, 1.1f)
        host.ring(px, surf, 0.42f)
        host.feel(Feel.Drip)
        b.x = px
        onSurface()
    }
}

/** Springs home along its own path (from the surface or out of the water), carrying its speed. */
private fun stepHop(b: Bubble, dt: Float, hx: Float, hy: Float, r: Float, done: () -> Unit) {
    val k = 150f; val c = 2f * sqrt(k) * 0.6f
    b.vx += (k * (hx - b.x) - c * b.vx) * dt
    b.vy += (k * (hy - b.y) - c * b.vy) * dt
    b.x += b.vx * dt; b.y += b.vy * dt
    if (abs(hx - b.x) < r * 0.08f && abs(hy - b.y) < r * 0.08f && hypot(b.vx, b.vy) < r * 1.2f) done()
}

/**
 * One substep of a thrown bubble: gravity in the air, buoyancy by how deep it sits, drag (much more
 * in water), bounces off the screen edges and the dock glass (with a squash and a tick), a splash where
 * it hits the water. It rests on the dock top where it lands; in the water it bobs back up and hops home.
 */
private fun stepFree(
    host: WaterHost, dock: DockState, b: Bubble, dt: Float, r: Float, reduced: Boolean,
    onRest: (onDock: Boolean) -> Unit, onOut: () -> Unit,
) {
    val dp = host.dp
    b.age += dt
    val g = 2600f * dp
    val buoy = g + 1300f * dp
    val wy = host.waterY(b.x)
    val sub = ((b.y + r - wy) / (2f * r)).coerceIn(0f, 1f)
    val drag = 0.35f + 3.2f * sub
    b.vx += (-drag * b.vx) * dt
    b.vy += (g - buoy * sub - drag * b.vy) * dt
    b.x += b.vx * dt; b.y += b.vy * dt

    // Into the water: a splash as big as the hit.
    val wetNow = sub > 0.08f
    if (wetNow && !b.wet) {
        val s = b.vy / dp
        if (s > 140f) {
            host.ripple(b.x, wy + 4f * dp, (s / 2200f).coerceIn(0.35f, 1.5f))
            if (s > 900f) host.ring(b.x, wy, (s / 4000f).coerceIn(0.2f, 0.6f))
            host.feel(if (s > 1100f) Feel.Plunk else Feel.Drip)
            impact(b, s * dp, vertical = true, dp)
        }
    } else if (!wetNow && b.wet && b.vy < -300f * dp) {
        host.ripple(b.x, wy + 2f * dp, 0.5f)
    }
    b.wet = wetNow

    var onDockTop = false
    // The screen edges.
    val top = dock.bounds.top + r
    if (b.x < r) { b.x = r; if (b.vx < 0f) bounce(host, b, -b.vx, false, 0.58f) { b.vx = -b.vx * it } }
    if (b.x > host.w - r) { b.x = host.w - r; if (b.vx > 0f) bounce(host, b, b.vx, false, 0.58f) { b.vx = -b.vx * it } }
    if (b.y < top) { b.y = top; if (b.vy < 0f) bounce(host, b, -b.vy, true, 0.5f) { b.vy = -b.vy * it } }
    if (b.y - r > host.h) { onOut(); return }

    // The dock's glass slab: a rounded box. Push out along its normal, bounce, slide with friction.
    val L = dock.dockLeft; val R = dock.dockRight; val T = dock.slabTop; val B = dock.slabBottom
    if (R > L && B > T) {
        val cx = (L + R) / 2f; val cy = (T + B) / 2f
        val hw = (R - L) / 2f; val hh = (B - T) / 2f
        val cr = min(26f * dp, min(hw, hh))
        val qx = abs(b.x - cx) - (hw - cr); val qy = abs(b.y - cy) - (hh - cr)
        val ox = max(qx, 0f); val oy = max(qy, 0f)
        val dist = hypot(ox, oy) + min(max(qx, qy), 0f) - cr
        if (dist < r) {
            var nx: Float; var ny: Float
            if (qx > 0f && qy > 0f) { val l = hypot(ox, oy).coerceAtLeast(1e-3f); nx = ox / l; ny = oy / l }
            else if (qx > qy) { nx = 1f; ny = 0f } else { nx = 0f; ny = 1f }
            nx *= sign(b.x - cx).let { if (it == 0f) 1f else it }
            ny *= sign(b.y - cy).let { if (it == 0f) 1f else it }
            val pen = r - dist
            b.x += nx * pen; b.y += ny * pen
            val vn = b.vx * nx + b.vy * ny
            if (vn < 0f) {
                val e = if (-vn > 260f * dp) 0.42f else 0f
                if (-vn > 120f * dp) {
                    impact(b, -vn, vertical = abs(ny) > abs(nx), dp)
                    if (-vn > 260f * dp) host.feel(if (-vn > 900f * dp) Feel.Clink else Feel.Tick)
                }
                b.vx -= (1f + e) * vn * nx; b.vy -= (1f + e) * vn * ny
            }
            // Friction along the glass.
            val tx = -ny; val ty = nx
            val vt = b.vx * tx + b.vy * ty
            val f = exp(-dt * 7f)
            b.vx += (vt * f - vt) * tx; b.vy += (vt * f - vt) * ty
            if (ny < -0.7f) onDockTop = true
        }
    }

    // At rest? On the dock top it stays where it landed; in the water (or pressed under the dock) it goes home.
    val speed = hypot(b.vx, b.vy) / dp
    val inWater = sub > 0.15f
    b.calm = if ((onDockTop && speed < 45f) || (inWater && speed < 80f)) b.calm + dt else 0f
    if (onDockTop && b.calm > 0.18f) onRest(true)
    else if (!onDockTop && b.calm > 0.55f) onRest(false)
    else if (b.age > 7f || reduced) onRest(false)
}

private inline fun bounce(host: WaterHost, b: Bubble, speed: Float, vertical: Boolean, e: Float, apply: (Float) -> Unit) {
    val dp = host.dp
    if (speed > 120f * dp) impact(b, speed, vertical, dp)
    if (speed > 220f * dp) host.feel(Feel.Tick)
    apply(if (speed > 60f * dp) e else 0f)
}

/** A hit flattens the glass along the hit and lets the size spring wobble it back. */
private fun impact(b: Bubble, speed: Float, vertical: Boolean, dp: Float) {
    val q = (speed / (3600f * dp)).coerceIn(0f, 0.26f)
    if (q > b.squash) { b.squash = q; b.squashY = vertical }
}

// ------------------------------------------------------------------------------------ faces

private val iconCache = LruCache<String, ImageBitmap>(24)

/** The notifying app's icon (the shell test hook shows Settings'), cached; null: no icon to show. */
private fun loadIcon(ctx: Context, pkg: String, size: Int): ImageBitmap? {
    val p = if (pkg == "com.android.shell" || pkg == "android") "com.android.settings" else pkg
    iconCache.get(p)?.let { return it }
    val pm = ctx.packageManager
    val d = runCatching { pm.getApplicationIcon(p) }.getOrNull()
        ?: runCatching { pm.getApplicationIcon("com.android.settings") }.getOrNull()
        ?: return null
    val bmp = runCatching { d.toBitmap(size, size).asImageBitmap() }.getOrNull() ?: return null
    iconCache.put(p, bmp)
    return bmp
}

@Composable
private fun BubbleFace(
    host: WaterHost, dock: DockState, b: Bubble, r: Float, note: BubbleNote?, count: Int,
    onGrab: (Offset) -> Boolean, onDrag: (Offset) -> Unit, onRelease: (velocity: Offset, cancelled: Boolean, tap: Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val c = Q.colors
    val faceSize = with(density) { (2f * r).toDp() }
    val iconPx = (r * 1.34f).roundToInt()
    val pkg = note?.packageName.orEmpty()
    val icon by produceState<ImageBitmap?>(iconCache.get(if (pkg == "com.android.shell") "com.android.settings" else pkg), pkg) {
        if (pkg.isNotEmpty()) value = withContext(Dispatchers.IO) { loadIcon(ctx, pkg, iconPx) }
    }
    val letter = (note?.appLabel?.firstOrNull { it.isLetterOrDigit() } ?: note?.title?.firstOrNull() ?: '•').uppercaseChar().toString()
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val glassless = b.id < 0
    Box(
        Modifier
            .offset {
                host.tick
                val id = b.id
                val x = if (id >= 0) host.glassCx(id) else b.x
                val y = if (id >= 0) host.glassCy(id) else b.y
                IntOffset((x - dock.origin.x - r).roundToInt(), (y - dock.origin.y - r).roundToInt())
            }
            .size(faceSize)
            .onGloballyPositioned { coords[0] = it }
            .graphicsLayer {
                host.tick
                val id = b.id
                if (id >= 0) {
                    scaleX = host.glassHalfW(id) / r; scaleY = host.glassHalfH(id) / r
                    rotationZ = Math.toDegrees((host.glassRotRad(id) + b.spin).toDouble()).toFloat()
                }
                alpha = if (id < 0) 1f else host.glassAlphaNow(id)
                transformOrigin = TransformOrigin.Center
            }
            .pointerInput(b) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun world(p: Offset): Offset = coords[0]?.takeIf { it.isAttached }?.let { host.toWorld(it, p) } ?: p
                    val start = world(down.position)
                    if (!onGrab(start)) return@awaitEachGesture
                    down.consume()
                    app.quiet.launcher.feature.gesture.GestureClaim.owner = down.id
                    val vt = VelocityTracker()
                    vt.addPosition(down.uptimeMillis, start)
                    var moved = false
                    var ended = false
                    try {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id }
                            if (ch == null) { ended = true; onRelease(Offset.Zero, true, false); break }
                            val p = world(ch.position)
                            if (!ch.pressed) {
                                ch.consume(); ended = true
                                val v = vt.calculateVelocity()
                                onRelease(if (moved) Offset(v.x, v.y) else Offset.Zero, false, !moved)
                                break
                            }
                            vt.addPosition(ch.uptimeMillis, p)
                            if (!moved && (p - start).getDistance() > viewConfiguration.touchSlop) moved = true
                            if (moved) onDrag(p)
                            ch.consume()
                        }
                    } finally {
                        if (app.quiet.launcher.feature.gesture.GestureClaim.owner == down.id) app.quiet.launcher.feature.gesture.GestureClaim.owner = null
                        if (!ended) onRelease(Offset.Zero, true, false)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val rr = size.minDimension / 2f
            val ctr = Offset(size.width / 2f, size.height / 2f)
            // No glass to spare (all bodies in use): a faint hairline keeps it a bubble.
            if (glassless) drawCircle(c.ink.copy(alpha = 0.28f), rr * 0.96f, ctr, style = Stroke(0.8.dp.toPx()))
            val ic = icon
            if (ic != null) {
                // The app's own icon, flat, its colours a little quieted for the dark room. It rolls
                // with the bubble (this layer rotates); the water around it does the rest.
                val sz = iconPx.toFloat()
                val tl = Offset(ctr.x - sz / 2f, ctr.y - sz / 2f)
                val clip = Path().apply { addOval(Rect(ctr, rr * 0.8f)) }
                clipPath(clip) {
                    drawImage(ic, srcOffset = IntOffset.Zero, srcSize = IntSize(ic.width, ic.height),
                        dstOffset = IntOffset(tl.x.roundToInt(), tl.y.roundToInt()), dstSize = IntSize(sz.roundToInt(), sz.roundToInt()),
                        alpha = 0.96f, colorFilter = QuietIcon)
                }
            }
        }
        if (icon == null) BasicText(letter, style = QType.labelTitle(18.sp).copy(color = c.ink), maxLines = 1)

    }
}

// ------------------------------------------------------------------------------------ card

@Composable
private fun BubbleCard(
    host: WaterHost, c: BubbleCardState, notes: List<BubbleNote>,
    onResize: (Float) -> Unit, onOpen: (BubbleNote) -> Unit, onDismiss: (BubbleNote) -> Unit, onDismissAll: () -> Unit,
) {
    val q = Q.colors
    val density = LocalDensity.current
    val r = c.rect
    val id = c.bubble.id
    val tint = Color.Black.copy(alpha = CARD_TINT)
    val shape = RoundedCornerShape(16.dp)
    // The tint hugs the glass exactly as it morphs from bubble to card (inset a hair: the rim stays bright).
    Canvas(Modifier.fillMaxSize()) {
        host.tick
        val grown = grownOf(host, id)
        drawGlassShape(host, id, tint.copy(alpha = CARD_TINT * (if (c.closing) c.fade.value else smooth(grown))), grow = -1.2f * host.dp)
    }
    Box(
        Modifier
            .offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
            .width(with(density) { r.width.toDp() })
            .graphicsLayer {
                host.tick
                translationX = host.glassDx(id); translationY = host.glassDy(id)
                val sx = host.glassScaleX(id); val sy = host.glassScaleY(id)
                scaleX = sx; scaleY = sy
                transformOrigin = TransformOrigin(0.5f, 0.5f)
                alpha = c.fade.value * (if (c.closing) 1f else grownOf(host, id))
            }
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(
            Modifier.fillMaxWidth().onSizeChanged { onResize(it.height.toFloat()) }.padding(horizontal = 22.dp).padding(top = 18.dp, bottom = 14.dp),
        ) {
            if (notes.size == 1) {
                val n = notes[0]
                BasicText("${n.appLabel} · ${ago(n.postedAt)}", style = QType.secondary(13.sp).copy(color = q.ink.copy(alpha = 0.72f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                if (n.title.isNotEmpty()) BasicText(n.title, style = QType.labelTitle(22.sp).copy(color = q.ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (n.text.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    BasicText(n.text, style = QType.body(17.sp).copy(color = q.ink.copy(alpha = 0.92f)), maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.weight(1f).height(46.dp).clipShape(shape).border(1.dp, q.ink.copy(alpha = 0.34f), shape).pressable { onDismiss(n) },
                        contentAlignment = Alignment.Center,
                    ) { BasicText("Dismiss", style = QType.body(16.sp).copy(color = q.ink.copy(alpha = 0.9f))) }
                    Spacer(Modifier.width(12.dp))
                    Box(
                        Modifier.weight(1f).height(46.dp).clipShape(shape).background(q.ink.copy(alpha = 0.16f), shape)
                            .border(1.dp, q.ink.copy(alpha = 0.5f), shape).pressable { onOpen(n) },
                        contentAlignment = Alignment.Center,
                    ) { BasicText("Open", style = QType.labelTitle(19.sp).copy(color = q.ink)) }
                }
            } else if (notes.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText("${notes.size} new", style = QType.labelTitle(21.sp).copy(color = q.ink), modifier = Modifier.weight(1f))
                    Box(
                        Modifier.height(34.dp).clipShape(shape).border(1.dp, q.ink.copy(alpha = 0.34f), shape).pressable(onClick = onDismissAll).padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) { BasicText("Dismiss all", style = QType.secondary(14.sp).copy(color = q.ink.copy(alpha = 0.9f))) }
                }
                Spacer(Modifier.height(6.dp))
                notes.take(4).forEach { n ->
                    key(n.key) {
                        Row(Modifier.fillMaxWidth().height(74.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).pressable(pressedScale = 0.99f) { onOpen(n) }) {
                                BasicText("${n.appLabel} · ${ago(n.postedAt)}", style = QType.secondary(12.sp).copy(color = q.ink.copy(alpha = 0.72f)), maxLines = 1)
                                BasicText(n.title.ifEmpty { n.text }, style = QType.row(17.sp).copy(color = q.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (n.title.isNotEmpty() && n.text.isNotEmpty())
                                    BasicText(n.text, style = QType.secondary(14.sp).copy(color = q.ink.copy(alpha = 0.82f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Box(Modifier.size(44.dp).pressable { onDismiss(n) }, contentAlignment = Alignment.Center) {
                                BasicText("×", style = QType.labelTitle(24.sp).copy(color = q.ink.copy(alpha = 0.85f)))
                            }
                        }
                    }
                }
                if (notes.size > 4) BasicText("and ${notes.size - 4} more", style = QType.secondary(13.sp).copy(color = q.ink.copy(alpha = 0.72f)), modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

private fun Modifier.clipShape(s: androidx.compose.ui.graphics.Shape) = this.then(Modifier.graphicsLayer { clip = true; shape = s })

/** 0 while the glass is still a bubble, 1 once it has grown to the card. */
internal fun grownOf(host: WaterHost, id: Int): Float {
    if (id < 0) return 0f
    val sx = host.glassScaleX(id); val sy = host.glassScaleY(id)
    return ((minOf(sx, sy) - 0.7f) / 0.28f).coerceIn(0f, 1f)
}

private fun ago(t: Long): String {
    val m = ((System.currentTimeMillis() - t) / 60_000L).coerceAtLeast(0)
    return when {
        m < 1 -> "now"
        m < 60 -> "$m min"
        m < 60 * 24 -> "${m / 60} h"
        else -> "${m / (60 * 24)} d"
    }
}

/** 0 outside the dock's slab .. 1 once the bubble is wholly behind it (world px). */
private fun inDock(host: WaterHost, dock: DockState, x: Float, y: Float, r: Float): Float {
    if (dock.dockRight <= dock.dockLeft || dock.slabBottom <= dock.slabTop) return 0f
    val inset = minOf(minOf(x - dock.dockLeft, dock.dockRight - x), minOf(y - dock.slabTop, dock.slabBottom - y))
    return ((inset - r * 0.4f) / (r * 0.9f)).coerceIn(0f, 1f)
}

private var diagN = 0
private var diagT = 0L
/** Test knob (logfeel): which bubbles keep the physics loop running, once every 5 s. */
private fun bubbleDiag(bubbles: List<Bubble>) {
    diagN++
    val now = android.os.SystemClock.uptimeMillis()
    if (now - diagT < 5000L) return
    android.util.Log.i("QuietWater", "bubbles busy $diagN frames/5s: " + bubbles.joinToString { "${it.pkg}:${it.mode} p=${"%.3f".format(it.pulse)} sq=${"%.3f".format(it.squash)} vs=${"%.3f".format(it.vspin)} s=${"%.3f".format(it.spin)} vy=${"%.1f".format(it.vy)}" })
    diagN = 0; diagT = now
}

/** An angle folded into -pi..pi. */
private fun wrapPi(a: Float): Float = ((a + PI_F) % (2f * PI_F) + 2f * PI_F) % (2f * PI_F) - PI_F

/** Icons a little desaturated, so they sit calmly in the dark gallery. */
private val QuietIcon = androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(0.78f) })

@Composable
private fun BubbleCount(host: WaterHost, dock: DockState, b: Bubble, r: Float, count: Int) {
    var w by remember { mutableIntStateOf(0) }
    BasicText(
        if (count > 9) "9+" else "$count",
        style = QType.secondary(12.sp).copy(color = Q.colors.ink.copy(alpha = 0.88f), shadow = WaterTextShadow),
        maxLines = 1,
        modifier = Modifier
            .wrapContentSize(align = Alignment.TopStart, unbounded = true)
            .onSizeChanged { w = it.width }
            .graphicsLayer {
                host.tick
                val id = b.id
                val x = if (id >= 0) host.glassCx(id) else b.x
                val y = if (id >= 0) host.glassCy(id) + host.glassHalfH(id) else b.y + r
                translationX = x - dock.origin.x - w / 2f
                translationY = y - dock.origin.y + 2f * host.dp
                alpha = if (id < 0) 1f else host.glassAlphaNow(id)
            },
    )
}
