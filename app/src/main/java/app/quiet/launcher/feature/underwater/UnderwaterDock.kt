package app.quiet.launcher.feature.underwater

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quiet.launcher.core.model.AppEntry
import app.quiet.launcher.designsystem.Feel
import app.quiet.launcher.designsystem.Q
import app.quiet.launcher.designsystem.QType
import app.quiet.launcher.feature.underwater.engine.GlassBodies
import app.quiet.launcher.feature.underwater.engine.GlassStyle
import app.quiet.launcher.feature.underwater.engine.WaterHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Press, tap, or hold-and-drag for glass rows. A tap calls [onTap]; holding still for the long-press
 * time calls [onLift] (world px of the finger) and, when it returns true, every move goes to
 * [onMove] until the finger lifts ([onEnd] false) or the gesture is lost ([onEnd] true). Moving past
 * the touch slop before the long press lets the pager and the Home swipes have the gesture.
 */
internal fun Modifier.liquidPress(
    key: Any?,
    host: WaterHost,
    coords: () -> LayoutCoordinates?,
    onPress: (Boolean) -> Unit,
    onTap: () -> Unit,
    onLift: (Offset) -> Boolean,
    onMove: (Offset) -> Unit,
    onEnd: (cancelled: Boolean) -> Unit,
    onDown: (Offset) -> Unit = {},
): Modifier = pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        onDown(coords()?.takeIf { it.isAttached }?.let { host.toWorld(it, down.position) } ?: down.position)
        onPress(true)
        val slop = viewConfiguration.touchSlop
        val r = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var res = 0
            while (res == 0) {
                val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                res = when {
                    c == null -> 2
                    !c.pressed -> { c.consume(); 1 }
                    (c.position - down.position).getDistance() > slop -> 2
                    else -> 0
                }
            }
            res
        }
        if (r != null) { onPress(false); if (r == 1) onTap(); return@awaitEachGesture }
        fun world(p: Offset): Offset = coords()?.takeIf { it.isAttached }?.let { host.toWorld(it, p) } ?: p
        if (!onLift(world(down.position))) { onPress(false); waitForUpOrCancellation()?.consume(); return@awaitEachGesture }
        var done = false
        try {
            while (true) {
                val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                if (c == null) { done = true; onEnd(true); break }
                c.consume()
                if (!c.pressed) { done = true; onEnd(false); break }
                onMove(world(c.position))
            }
        } finally {
            if (!done) onEnd(true)
            onPress(false)
        }
    }
}

/** A favourite lifted off the dock (or out of an open folder) as a glass drop. World px. */
internal class DockDrag(
    val app: AppEntry, val key: String, val fromFolder: Long?, val id: Int,
    val halfW: Float, val halfH: Float, val home: Offset, val start: Offset, val lift: Float,
) {
    var x = home.x; var y = home.y
    var finger = start
    var moved by mutableStateOf(false)
    var targetKey: String? = null
    var targetId = -1
    var targetCenter = Offset.Zero
    var targetHalf = Offset.Zero
    var merged = false
    var released = false
    /** Back in place: the row shows its own label again while the drop fades. */
    var settled by mutableStateOf(false)
}

/** An open folder: one glass body grown out of the folder's row into a frosted card. World px. */
internal class FolderOpen(val folderId: Long, val key: String, val id: Int, val pillCenter: Offset, val pillHalf: Offset) {
    var rect by mutableStateOf(Rect.Zero)
    var closing by mutableStateOf(false)
    val fade = Animatable(0f)
}

/**
 * The underwater dock's interaction state: which rows are where (world), the drop being dragged and
 * the open folder. Glass bodies: a drag uses two (the lifted drop and the target's drop, group 1, so
 * they fuse with a plunk and split with a pop); an open folder one (two while a drop is pulled out).
 */
@Stable
internal class DockState(val host: WaterHost, val scope: CoroutineScope, val folders: DockFolders) {
    val rows = HashMap<String, LayoutCoordinates>()
    val labels = HashMap<String, LayoutCoordinates>()
    var items: List<DockItem> = emptyList()
    var origin = Offset.Zero
    var bounds = Rect.Zero
    var dockLeft by mutableFloatStateOf(0f)
    var dockRight by mutableFloatStateOf(0f)
    /** The dock slab's top and bottom (world px; -1 until laid out). */
    var slabTop by mutableFloatStateOf(-1f)
    var slabBottom = 0f
    /** Height (px) the resting notification bubbles need above the slab (their row and clearance); 0 with none. */
    var shelf by mutableFloatStateOf(0f)
    /** Width (px) the resting bubbles need along the slab top, from its left edge. */
    var shelfWidth by mutableFloatStateOf(0f)
    /** World x the resting bubbles stay left of (the battery label shares their row); NaN: the whole dock. */
    var shelfRight by mutableFloatStateOf(Float.NaN)
    /** A notification card is open (Home dims under it, like under an open folder). */
    var cardOpen by mutableStateOf(false)
    /** 0..1: Home's text and the dock fade, and a scrim darkens the world, while a card is up. */
    val dim = Animatable(0f)
    val focused: Boolean get() = cardOpen || open?.closing == false
    var onLongPress: (AppEntry) -> Unit = {}
    var drag by mutableStateOf<DockDrag?>(null)
        private set
    var open by mutableStateOf<FolderOpen?>(null)
        private set

    // ---------------------------------------------------------------- press: the name surfaces

    /** The favourite whose name has risen out of the water under a finger (drawn crisp on the top layer). */
    var pressedKey by mutableStateOf<String?>(null)
        private set
    /** 0 under water .. 1 risen (a spring; it overshoots a touch). */
    val surfacing = Animatable(0f)
    /** When the current press came down (uptime ms): the launch holds until the effect has been seen. */
    var pressAt = 0L
        private set

    /**
     * A finger lands on a name: one clean ring spreads through the water from the touch (the grid
     * ripple: it bends the frame's submerged part, the label and the names as it passes) and the
     * name rises out of the water, sharp and bright, with a thin meniscus at its base.
     */
    fun pressDown(key: String, at: Offset) {
        if (drag != null || open != null) return
        pressAt = android.os.SystemClock.uptimeMillis()
        // On touch-down: a crisp ring through air and water (it bends the frame and the label within
        // ~150 ms) and a ripple in the grid, and the name springs up (~80% in ~70 ms).
        host.ring(at.x, at.y, 0.85f, fade = 1.15f)
        host.ripple(at.x, at.y, 0.9f)
        pressedKey = key
        scope.launch { surfacing.animateTo(1f, spring(0.6f, 1700f)) }
    }

    /** The finger lifts or slides off: the name sinks back (a small splash if it was a tap). */
    fun pressUp(key: String, tapped: Boolean) {
        if (pressedKey != key) return
        scope.launch {
            if (tapped) {
                // A quick tap still shows the name standing out of the water a moment (the launch is
                // held for it), then it drops back with a small splash.
                surfacing.animateTo(1f, spring(0.6f, 1700f))
                delay(110)
                val lc = labels[key]?.takeIf { it.isAttached }
                if (lc != null) { val b = host.toWorld(lc, Offset(lc.size.width / 2f, lc.size.height * 0.8f)); host.ripple(b.x, b.y, 0.6f) }
            }
            surfacing.animateTo(0f, if (tapped) spring(0.75f, 900f) else spring(1f, 300f))
            if (pressedKey == key) pressedKey = null
        }
    }

    /** A hold became a lift (the drop takes the name): no surfacing name. */
    private fun clearPress() {
        if (pressedKey == null) return
        pressedKey = null
        scope.launch { surfacing.snapTo(0f) }
    }

    private val dp get() = host.dp

    private fun pill(lc: LayoutCoordinates): Pair<Offset, Offset> {
        val c = host.toWorld(lc, Offset(lc.size.width / 2f, lc.size.height / 2f))
        return c to Offset(lc.size.width / 2f + 18f * dp, 21f * dp)
    }

    private fun dropStyle(group: Int, lens: Float, frost: Float = 0f) =
        GlassStyle(corner = 999.dp, bevel = 13.dp, group = group, lens = lens, frost = frost, stir = true, stiffness = 380f, damping = 0.6f, clear = true)

    // ---------------------------------------------------------------- drag

    fun lift(app: AppEntry, key: String, fromFolder: Long?, lc: LayoutCoordinates?, finger: Offset): Boolean {
        if (drag != null || lc == null || !lc.isAttached) return false
        clearPress()
        if (fromFolder == null && open != null) return false
        val (c, half) = pill(lc)
        // In a folder the drop starts inside the card (same group): pulling it out stretches a neck that breaks.
        // A little frost: what the lifted drop passes over blurs under it, so its label reads alone.
        val id = host.obtainGlass(dropStyle(if (fromFolder != null) 2 else 1, 0.15f))
        val lift = 7f * dp
        val d = DockDrag(app, key, fromFolder, id, half.x, half.y, c, finger, lift)
        // It swells out of the row: small first, then springs to full size a little above the label.
        host.moveGlass(id, c.x, c.y, half.x * 0.55f, half.y * 0.55f)
        host.holdGlass(id, true)
        drag = d
        if (fromFolder == null) host.feel(Feel.Clink)
        scope.launch {
            delay(34)
            if (drag === d && !d.released) { d.y = if (d.moved) d.y else c.y - lift; host.moveGlass(id, d.x, d.y, half.x, half.y) }
        }
        return true
    }

    fun move(finger: Offset) {
        val d = drag ?: return
        if (d.released) return
        d.finger = finger
        if (!d.moved && (finger - d.start).getDistance() > 10f * dp) d.moved = true
        var p = finger + (d.home - d.start) - Offset(0f, d.lift)
        val tk = if (d.fromFolder == null && d.moved) hitRow(finger, d.key) else null
        if (tk != d.targetKey) { releaseTarget(d); if (tk != null) takeTarget(d, tk) }
        // Near a target the drop leans into it (a soft magnet), so the two fuse before the finger arrives.
        if (d.targetKey != null) p += (d.targetCenter - p) * 0.38f
        d.x = p.x; d.y = p.y
        host.moveGlass(d.id, p.x, p.y, d.halfW, d.halfH)
    }

    private fun hitRow(f: Offset, exclude: String): String? {
        if (f.x < dockLeft || f.x > dockRight) return null
        for (it in items) {
            if (it.key == exclude) continue
            val rc = rows[it.key]?.takeIf { c -> c.isAttached } ?: continue
            val top = host.toWorld(rc).y
            val h = rc.size.height
            if (abs(f.y - (top + h / 2f)) < h * 0.44f) return it.key
        }
        return null
    }

    private fun takeTarget(d: DockDrag, key: String) {
        val lc = labels[key]?.takeIf { it.isAttached } ?: return
        val (c, half) = pill(lc)
        val id = host.obtainGlass(dropStyle(1, 0.2f).copy(stir = false, stiffness = 320f, damping = 0.5f))
        d.targetKey = key; d.targetId = id; d.targetCenter = c; d.targetHalf = half; d.merged = false
        host.moveGlass(id, c.x, c.y, half.x * 0.6f, half.y * 0.6f)
        scope.launch { delay(34); if (d.targetId == id) host.moveGlass(id, c.x, c.y, half.x, half.y) }
    }

    private fun releaseTarget(d: DockDrag) {
        val id = d.targetId
        d.targetKey = null; d.targetId = -1; d.merged = false
        if (id < 0) return
        host.moveGlass(id, d.targetCenter.x, d.targetCenter.y, d.targetHalf.x * 0.5f, d.targetHalf.y * 0.5f)
        host.glassAlpha(id, 0f)
        scope.launch { delay(420); host.freeGlass(id) }
    }

    fun end(cancelled: Boolean) {
        val d = drag ?: return
        if (d.released) return
        d.released = true
        host.holdGlass(d.id, false)
        val inCard = open?.rect?.inflate(6f * dp)?.contains(d.finger) == true
        when {
            cancelled -> springBack(d)
            !d.moved -> { springBack(d); onLongPress(d.app) }
            d.fromFolder != null && !inCard -> takeOut(d)
            d.fromFolder == null && d.targetKey != null -> mergeInto(d)
            else -> springBack(d)
        }
    }

    private fun springBack(d: DockDrag) {
        releaseTarget(d)
        d.x = d.home.x; d.y = d.home.y
        host.moveGlass(d.id, d.home.x, d.home.y, d.halfW, d.halfH)
        scope.launch {
            delay(if (d.moved) 300 else 120)
            d.settled = true
            host.glassAlpha(d.id, 0f)
            delay(380)
            host.freeGlass(d.id)
            if (drag === d) drag = null
        }
    }

    private fun mergeInto(d: DockDrag) {
        val target = items.firstOrNull { it.key == d.targetKey }
        val tId = d.targetId
        val c = d.targetCenter
        if (target == null) { springBack(d); return }
        folders.drop(d.app, target)
        if (!d.merged) host.feel(Feel.Plunk)
        // Both drops sink into one at the target and soak back into the dock.
        d.x = c.x; d.y = c.y
        host.moveGlass(d.id, c.x, c.y, d.targetHalf.x * 0.7f, d.targetHalf.y * 0.8f)
        host.moveGlass(tId, c.x, c.y, d.targetHalf.x * 1.06f, d.targetHalf.y * 1.06f)
        host.ripple(c.x, c.y, 0.7f)
        d.targetId = -1; d.targetKey = null
        scope.launch {
            delay(140)
            d.settled = true
            host.glassAlpha(d.id, 0f); host.glassAlpha(tId, 0f)
            delay(420)
            host.freeGlass(d.id); host.freeGlass(tId)
            if (drag === d) drag = null
        }
    }

    private fun takeOut(d: DockDrag) {
        val fid = d.fromFolder ?: return
        folders.remove(fid, d.app)
        // The drop lets go and falls into the water; the app is back in its own row.
        host.moveGlass(d.id, d.x, d.y, d.halfW * 0.3f, d.halfH * 0.4f)
        host.drop(d.x, d.y, { _, _ -> }, radius = 6.dp, fallMs = 220)
        scope.launch {
            delay(90)
            d.settled = true
            host.glassAlpha(d.id, 0f)
            delay(400)
            host.freeGlass(d.id)
            if (drag === d) drag = null
        }
        val left = folders.folders.value.firstOrNull { it.id == fid }
        if (left == null || left.members.size < 2) closeFolder()
    }

    fun onGlassEvent(type: Int, a: Int, b: Int) {
        val d = drag ?: return
        val pair = (a == d.id && b == d.targetId) || (b == d.id && a == d.targetId)
        if (!pair || d.id < 0) return
        if (type == GlassBodies.EV_MERGE) d.merged = true
        else if (type == GlassBodies.EV_SPLIT) d.merged = false
    }

    // ---------------------------------------------------------------- folders

    fun openFolder(item: DockItem.Folder) {
        if (open != null || drag != null) return
        val lc = labels[item.key]?.takeIf { it.isAttached } ?: return
        val (c, half) = pill(lc)
        // Fully frosted: the card's words are drawn on the top layer, over a blur of the dock below.
        val id = host.obtainGlass(GlassStyle(corner = 28.dp, bevel = 16.dp, group = 2, frost = 1f, stiffness = 230f, damping = 0.6f, clear = true))
        val f = FolderOpen(item.folder.id, item.key, id, c, half)
        val n = item.apps.size.coerceAtMost(MAX_ROWS)
        val h = (62f + n * 52f + 18f) * dp
        val w = dockRight - dockLeft
        val top = (c.y - h / 2f).coerceIn(bounds.top + 8f * dp, maxOf(bounds.top + 8f * dp, bounds.bottom - h - 8f * dp))
        f.rect = Rect(dockLeft, top, dockLeft + w, top + h)
        // The folder's drop splits open: a pop and a ring, and the glass springs out into the card.
        host.moveGlass(id, c.x, c.y, half.x, half.y)
        host.feel(Feel.Pop)
        host.ring(c.x, c.y, 0.55f)
        open = f
        scope.launch {
            delay(34)
            if (open === f && !f.closing) host.moveGlass(id, f.rect.center.x, f.rect.center.y, f.rect.width / 2f, f.rect.height / 2f)
            f.fade.animateTo(1f, tween(260, 90))
        }
    }

    fun closeFolder(quick: Boolean = false) {
        val f = open ?: return
        if (f.closing) return
        f.closing = true
        scope.launch {
            launch { f.fade.animateTo(0f, tween(if (quick) 80 else 130)) }
            // Back into a drop at the folder's row, which soaks into the dock.
            host.moveGlass(f.id, f.pillCenter.x, f.pillCenter.y, f.pillHalf.x * 0.9f, f.pillHalf.y * 0.9f)
            delay(if (quick) 60 else 210)
            if (!quick) { host.feel(Feel.Plunk); host.ripple(f.pillCenter.x, f.pillCenter.y, 0.5f) }
            host.glassAlpha(f.id, 0f)
            if (open === f) open = null
            delay(420)
            host.freeGlass(f.id)
        }
    }

    /** Home is going away (another page, an overlay, the player): put everything back. */
    fun reset() {
        drag?.let { if (!it.released) end(true) }
        closeFolder(quick = true)
    }

    companion object { const val MAX_ROWS = 7 }
}

@Composable
internal fun rememberDockState(host: WaterHost, onLongPress: (AppEntry) -> Unit): DockState {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val s = remember(host) { DockState(host, scope, DockFolders.get(ctx)) }
    s.onLongPress = onLongPress
    DisposableEffect(s) {
        val l: (Int, Int, Int) -> Unit = { t, a, b -> s.onGlassEvent(t, a, b) }
        host.addGlassListener(l)
        onDispose { host.removeGlassListener(l); s.reset() }
    }
    return s
}

/** One dock row: a favourite (tap: drop launch; hold: lift; drag onto another: folder) or a folder (tap: open). */
@Composable
internal fun DockRow(state: DockState, view: View, item: DockItem, rowH: Dp) {
    val host = state.host
    val c = Q.colors
    val ref = remember { arrayOfNulls<LayoutCoordinates>(1) }
    // Lifted and carried away: the row closes up behind the drop on a spring (and opens again when it comes back).
    val d = state.drag
    val away = d != null && d.key == item.key && d.moved && !d.released
    val open by animateFloatAsState(if (away) 0f else 1f, if (away) spring(0.82f, 420f) else spring(0.7f, 520f), label = "gap")
    DisposableEffect(item.key) { onDispose { state.rows.remove(item.key); state.labels.remove(item.key) } }
    Box(
        Modifier
            .fillMaxWidth()
            .layout { m, cons ->
                val full = rowH.roundToPx()
                val p = m.measure(cons.copy(minHeight = full, maxHeight = full))
                val h = (full * open).roundToInt().coerceAtLeast(0)
                layout(p.width, h) { p.place(0, (h - full) / 2) }
            }
            .onGloballyPositioned { ref[0] = it; state.rows[item.key] = it }
            .liquidPress(
                item, host, { ref[0] },
                onDown = { state.pressDown(item.key, it) },
                onPress = { if (!it) state.pressUp(item.key, tapped = false) },
                onTap = {
                    state.pressUp(item.key, tapped = true)
                    when (item) {
                        is DockItem.App -> {
                            val k = state.labels[item.key]
                            val from = if (k != null && k.isAttached) host.toWorld(k, Offset(k.size.width / 2f, k.size.height / 2f)) else Offset(host.w / 2f, host.restLine)
                            // Held ~180 ms after release (and 260 ms after down at least): the splash and
                            // the start of the drop are seen; the haptic still fires at once.
                            val sinceDown = android.os.SystemClock.uptimeMillis() - state.pressAt
                            launchWithDrop(host, view, item.app, from, holdMs = maxOf(180L, 260L - sinceDown))
                        }
                        is DockItem.Folder -> state.openFolder(item)
                    }
                },
                onLift = { f ->
                    when (item) {
                        is DockItem.App -> state.lift(item.app, item.key, null, state.labels[item.key], f)
                        is DockItem.Folder -> { state.openFolder(item); false }
                    }
                },
                onMove = state::move,
                onEnd = state::end,
            )
            .graphicsLayer {
                val dd = state.drag
                // The lifted label rides the drop on the top layer: no ghost stays behind in the row.
                val lifted = dd != null && dd.key == item.key && !dd.settled
                // Surfaced: the crisp copy on the top layer stands in for it.
                val up = if (state.pressedKey == item.key) (state.surfacing.value * 4f).coerceIn(0f, 1f) else 0f
                alpha = (if (lifted) 0f else 1f) * (1f - up) * open * (1f - 0.72f * state.dim.value)
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(Modifier.onGloballyPositioned { state.labels[item.key] = it }, verticalAlignment = Alignment.CenterVertically) {
            when (item) {
                is DockItem.App -> BasicText(item.app.label, style = QType.favourite(24.sp).copy(color = c.ink, shadow = NameShadow), maxLines = 1, overflow = TextOverflow.Ellipsis)
                is DockItem.Folder -> {
                    BasicText(item.folder.name, style = QType.favourite(24.sp).copy(color = c.ink, shadow = NameShadow), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(12.dp))
                    FolderBeads(item.apps.size)
                }
            }
        }
    }
}

/** Names written straight on the water: a soft dark halo, never a hard drop shadow or a box. */
private val NameShadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1.5f), 14f)

/** A tiny cluster of drops after a folder's name: one per app (up to four). */
@Composable
private fun FolderBeads(n: Int) {
    val ink = Q.colors.ink
    Canvas(Modifier.size(width = 30.dp, height = 18.dp)) {
        val r = 3.6.dp.toPx()
        val pts = listOf(Offset(0.22f, 0.62f), Offset(0.48f, 0.34f), Offset(0.66f, 0.72f), Offset(0.88f, 0.42f))
        for (i in 0 until n.coerceAtMost(4)) {
            val p = Offset(size.width * pts[i].x, size.height * pts[i].y)
            val rr = r * (1f - 0.12f * i)
            drawCircle(ink.copy(alpha = 0.16f), rr, p)
            drawCircle(ink.copy(alpha = 0.72f), rr, p, style = Stroke(1.dp.toPx()))
            drawCircle(Color.White.copy(alpha = 0.55f), rr * 0.28f, p + Offset(-rr * 0.35f, -rr * 0.35f))
        }
    }
}

/** Black over a fully frosted folder card (plus the scrim under it): its words read at well over 4.5:1. */
private const val FOLDER_TINT = 0.62f
/** The scrim over the world while a card is up (the dock and Home recede behind it). */
private const val SCRIM = 0.4f

/**
 * Measures where Home is (world px) and puts, on the top layer above the world (crisp, never frosted
 * or bent): the scrim while a card is up, the open folder's card, and the label riding a lifted drop.
 * Place it as the last child of the Home box, the size of that box.
 */
@Composable
internal fun DockOverlay(state: DockState, view: View, modifier: Modifier) {
    val host = state.host
    val f = state.open
    val d = state.drag
    BackHandler(enabled = f != null && !f.closing) { state.closeFolder() }
    // Home dims while a card (folder or notification) is up; a spring, so it breathes in and out.
    LaunchedEffect(Unit) {
        snapshotFlow { state.focused }.collect { on ->
            state.dim.animateTo(if (on) 1f else 0f, if (on) spring(1f, 300f) else spring(1f, 420f))
        }
    }
    Box(modifier.onGloballyPositioned { state.origin = host.toWorld(it); state.bounds = Rect(state.origin, Offset(state.origin.x + it.size.width, state.origin.y + it.size.height)) })
    OnWaterTop("dock-scrim", z = 1) {
        Canvas(Modifier.fillMaxSize()) {
            val a = state.dim.value
            if (a > 0.002f) drawRect(Color.Black.copy(alpha = SCRIM * a))
        }
    }
    if (f != null) OnWaterTop("dock-folder", z = 2) {
        Box(Modifier.fillMaxSize()) {
            // Tap anywhere else to close.
            if (!f.closing) Box(Modifier.fillMaxSize().pointerInput(f) { detectTapGestures { state.closeFolder() } })
            FolderCard(state, f, view)
        }
    }
    if (d != null) OnWaterTop("dock-drag", z = 3) { DragLabel(state, d) }
    val pk = state.pressedKey
    if (pk != null) OnWaterTop("dock-press", z = 3) { SurfacedName(state, pk) }
}

/**
 * A pressed name out of the water: drawn above the world (so no refraction, no tint), risen a few
 * dp on a spring, a touch brighter, with a thin meniscus line where it leaves the surface.
 */
@Composable
private fun SurfacedName(state: DockState, key: String) {
    val host = state.host
    val item = state.items.firstOrNull { it.key == key } ?: return
    val text = when (item) { is DockItem.App -> item.app.label; is DockItem.Folder -> item.folder.name }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val dp = host.dp
    fun at(): Offset? = state.labels[key]?.takeIf { it.isAttached }?.let { host.toWorld(it) }
    fun shown(): Float = (state.surfacing.value * 4f).coerceIn(0f, 1f)
    // The meniscus: a thin bright line at the name's base with a faint glow under it, fading at the ends.
    Canvas(Modifier.fillMaxSize()) {
        val p = at() ?: return@Canvas
        val k = state.surfacing.value.coerceIn(0f, 1.2f)
        val a = shown() * k.coerceAtMost(1f)
        if (a <= 0.01f || size.width == 0) return@Canvas
        val y = p.y + size.height * 0.84f
        val x0 = p.x - 10f * dp; val x1 = p.x + size.width + 10f * dp
        val line = androidx.compose.ui.graphics.Brush.horizontalGradient(
            0f to Color.Transparent, 0.15f to Color.White.copy(alpha = 0.75f * a), 0.85f to Color.White.copy(alpha = 0.75f * a), 1f to Color.Transparent,
            startX = x0, endX = x1,
        )
        drawLine(line, Offset(x0, y), Offset(x1, y), strokeWidth = 1.5f * dp)
        val glow = androidx.compose.ui.graphics.Brush.horizontalGradient(
            0f to Color.Transparent, 0.25f to Color.White.copy(alpha = 0.12f * a), 0.75f to Color.White.copy(alpha = 0.12f * a), 1f to Color.Transparent,
            startX = x0, endX = x1,
        )
        drawLine(glow, Offset(x0, y + 2.5f * dp), Offset(x1, y + 2.5f * dp), strokeWidth = 4f * dp)
    }
    BasicText(
        text,
        style = QType.favourite(24.sp).copy(color = Color.White, shadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 2f * dp), 12f * dp)),
        maxLines = 1,
        modifier = Modifier
            .wrapContentSize(align = Alignment.TopStart, unbounded = true)
            .onSizeChanged { size = it }
            .graphicsLayer {
                val p = at() ?: Offset(-9999f, -9999f)
                val k = state.surfacing.value
                translationX = p.x; translationY = p.y - 8f * dp * k
                val s = 1f + 0.02f * k
                scaleX = s; scaleY = s
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 1f)
                alpha = shown() * (1f - 0.72f * state.dim.value)
            },
    )
}

@Composable
private fun DragLabel(state: DockState, d: DockDrag) {
    val host = state.host
    var size by remember { mutableStateOf(IntSize.Zero) }
    val dp = host.dp
    // No chip under the words: the clear drop and the label's soft shadow are all there is.
    BasicText(
        d.app.label,
        style = QType.favourite(24.sp).copy(
            color = Q.colors.ink,
            shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 2.5f * dp), 10f * dp),
        ),
        maxLines = 1,
        modifier = Modifier
            .wrapContentSize(align = Alignment.TopStart, unbounded = true)
            .onSizeChanged { size = it }
            .graphicsLayer {
                host.tick
                translationX = host.glassCx(d.id) - size.width / 2f
                translationY = host.glassCy(d.id) - size.height / 2f
                rotationZ = host.glassRotation(d.id)
                val sx = host.glassScaleX(d.id); val sy = host.glassScaleY(d.id)
                // Lifted: a touch bigger than in its row, breathing with the drop's squash.
                val lift = if (d.released) 1f else 1.06f
                scaleX = lift * (1f + (sx - 1f) * 0.5f); scaleY = lift * (1f + (sy - 1f) * 0.5f)
                alpha = labelAlpha(host, d)
            },
    )
}

private fun labelAlpha(host: WaterHost, d: DockDrag): Float =
    if (d.settled) 0f else if (d.id < 0) 1f else host.glassAlphaNow(d.id).coerceIn(0f, 1f).let { if (d.released) it else 1f }

@Composable
private fun FolderCard(state: DockState, f: FolderOpen, view: View) {
    val host = state.host
    val c = Q.colors
    val density = LocalDensity.current
    val item = state.items.firstOrNull { it is DockItem.Folder && it.folder.id == f.folderId } as? DockItem.Folder
    LaunchedEffect(item == null) { if (item == null) state.closeFolder(quick = true) }
    item ?: return
    val r = f.rect
    // The tint follows the glass exactly as it springs out of the folder's drop into the card.
    Canvas(Modifier.fillMaxSize()) {
        host.tick
        val a = if (f.closing) f.fade.value else smooth(grownOf(host, f.id))
        drawGlassShape(host, f.id, Color.Black.copy(alpha = FOLDER_TINT * a), grow = -1.2f * host.dp)
    }
    Box(
        Modifier
            .offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
            .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() })
            .graphicsLayer {
                host.tick
                translationX = host.glassDx(f.id); translationY = host.glassDy(f.id)
                val sx = host.glassScaleX(f.id); val sy = host.glassScaleY(f.id)
                scaleX = sx; scaleY = sy
                // The words come in as the glass reaches its size, and go first when it closes.
                alpha = f.fade.value * (if (f.closing) 1f else grownOf(host, f.id))
            }
            // Taps on the card itself do not close it.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 22.dp).padding(top = 16.dp, bottom = 10.dp)) {
            Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(item.folder.name, style = QType.labelTitle(21.sp).copy(color = c.ink), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                BasicText("${item.apps.size}", style = QType.secondary(14.sp).copy(color = c.ink.copy(alpha = 0.72f)))
            }
            Spacer(Modifier.height(8.dp))
            item.apps.take(DockState.MAX_ROWS).forEach { app -> key(app.key.id) { FolderRow(state, f, app, view) } }
        }
    }
}

@Composable
private fun FolderRow(state: DockState, f: FolderOpen, app: AppEntry, view: View) {
    val host = state.host
    val key = "f${f.folderId}:${app.key.id}"
    val ref = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val label = remember { arrayOfNulls<LayoutCoordinates>(1) }
    var pressed by remember { mutableStateOf(false) }
    val press by animateFloatAsState(if (pressed) 1f else 0f, if (pressed) spring(1f, 1400f) else spring(0.7f, 500f), label = "press")
    val d = state.drag
    val away = d != null && d.key == key && d.moved && !d.released
    val open by animateFloatAsState(if (away) 0f else 1f, if (away) spring(0.82f, 420f) else spring(0.7f, 520f), label = "gap")
    Box(
        Modifier
            .fillMaxWidth()
            .layout { m, cons ->
                val full = 52.dp.roundToPx()
                val p = m.measure(cons.copy(minHeight = full, maxHeight = full))
                val h = (full * open).roundToInt().coerceAtLeast(0)
                layout(p.width, h) { p.place(0, (h - full) / 2) }
            }
            .onGloballyPositioned { ref[0] = it }
            .liquidPress(
                key, host, { ref[0] },
                onPress = { pressed = it },
                onTap = {
                    if (f.closing) return@liquidPress
                    val k = label[0]
                    val from = if (k != null && k.isAttached) host.toWorld(k, Offset(k.size.width / 2f, k.size.height / 2f)) else f.rect.center
                    state.closeFolder(quick = true)
                    launchWithDrop(host, view, app, from)
                },
                onLift = { p -> !f.closing && state.lift(app, key, f.folderId, label[0], p) },
                onMove = state::move,
                onEnd = state::end,
            )
            .graphicsLayer {
                val p = press
                scaleX = 1f - 0.015f * p; scaleY = scaleX
                val dd = state.drag
                alpha = (1f - 0.4f * p) * (if (dd != null && dd.key == key && !dd.settled) 0f else 1f) * open
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(
            app.label, style = QType.favourite(22.sp).copy(color = Q.colors.ink), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.onGloballyPositioned { label[0] = it },
        )
    }
}
