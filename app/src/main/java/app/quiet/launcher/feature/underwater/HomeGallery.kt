package app.quiet.launcher.feature.underwater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import app.quiet.launcher.feature.underwater.engine.GlassStyle
import app.quiet.launcher.feature.underwater.engine.WaterHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Home hangs today's painting on a dark gallery wall: a framed canvas the water floods up to.
 * Home lays it out ([art], world px, the painting inside the frame) and the backdrop draws it there,
 * under the world's water. [front] says how far Home is in front (0..1, the pager): away from Home
 * the painting opens out to the old full-bleed backdrop (the apps list and the glass keyboard read
 * against that), and on the way back it steps into its frame on the wall.
 */
@Stable
class GalleryHang {
    /** The painting inside the frame (world px, before the tilt lean); empty while Home is not laid out. */
    var art by mutableStateOf(Rect.Zero)
    /** The current painting's aspect (w / h), 0 until it is decoded. Home sizes the frame by it. */
    var aspect by mutableFloatStateOf(0f)
    /** 0: the painting fills the screen (other pages) .. 1: it hangs on the wall (Home). Read while drawing. */
    var front: () -> Float = { 0f }
}

val LocalGallery = staticCompositionLocalOf<GalleryHang?> { null }

/** The hung painting leans with the tilt less than the full-bleed one (it is a smaller, nearer thing). */
internal const val HANG_LEAN = 0.6f
/** The frame's moulding, each side. */
internal val FRAME_W = 11.dp

/**
 * Draws the gallery: the wall, a picture light, the frame (moulding, a thin gilt slip, the shadow it
 * casts on the wall) and the painting, which opens out to full bleed as [e] goes to 0. Shaders are
 * made once per bitmap / size; a frame only sets local matrices and alphas (no allocation).
 */
internal class HangPainter {
    private val m = Matrix()
    private val shaders = arrayOfNulls<BitmapShader>(2)
    private val shaderOf = arrayOfNulls<Bitmap>(2)
    private val canvasPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true; isDither = true }
    private val wall = Paint()
    private val light = Paint(Paint.DITHER_FLAG)
    private val lightShader = RadialGradient(0f, 0f, 1f, intArrayOf(0x1CFFF0DA, 0x0AFFF0DA, 0x00FFF0DA), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mould = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val mouldShader = LinearGradient(0f, 0f, 0f, 1f, intArrayOf(0xFF3E352C.toInt(), 0xFF221C17.toInt(), 0xFF16120F.toInt()), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val slip = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val inner = Paint(Paint.DITHER_FLAG)
    private val innerShader = LinearGradient(0f, 0f, 0f, 1f, intArrayOf(0x66000000, 0x00000000), null, Shader.TileMode.CLAMP)
    private val dark = Paint()
    private val face = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = android.graphics.Path()
    private val r = RectF()
    private val o = RectF()
    private val d = RectF()

    init {
        light.shader = lightShader; mould.shader = mouldShader; inner.shader = innerShader
    }

    private fun shaderFor(slot: Int, b: Bitmap): BitmapShader {
        val s = shaders[slot]
        if (s != null && shaderOf[slot] === b) return s
        val n = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        if (android.os.Build.VERSION.SDK_INT >= 33) n.filterMode = BitmapShader.FILTER_MODE_LINEAR
        shaders[slot] = n; shaderOf[slot] = b
        return n
    }

    fun wall(c: Canvas, w: Float, h: Float, color: Int, art: Rect, lx: Float, ly: Float, dp: Float, alpha: Float) {
        wall.color = color
        c.drawRect(0f, 0f, w, h, wall)
        if (art.isEmpty || alpha <= 0f) return
        // A picture light over the frame: a warm pool on the wall, strongest just above it.
        val rad = art.width * 1.05f
        m.setScale(rad, rad * 0.9f); m.postTranslate(art.center.x + lx, art.top + ly - 30f * dp)
        lightShader.setLocalMatrix(m)
        light.alpha = (255 * alpha).toInt()
        c.drawRect(0f, 0f, w, h, light)
    }

    /**
     * One hung painting. [slot] 0 = the current one, 1 = the one sinking away. [art] is where it
     * hangs (already moved by the lean and any sink / rise), [e] how hung it is (0 = full bleed with
     * the old crop, overscale and tilt shift: [fullTx], [fullTy], [fullSc]), [a] its alpha, [darken] a
     * black wash (sinking), [dp] px per dp.
     */
    fun painting(
        c: Canvas, slot: Int, b: Bitmap?, w: Float, h: Float, art: Rect, e: Float, a: Float, darken: Float,
        fullTx: Float, fullTy: Float, fullSc: Float, dp: Float, placeholder: Int,
    ) {
        if (a <= 0.004f) return
        r.set(art.left, art.top, art.right, art.bottom)
        val fw = FRAME_W.value * dp
        // ---- the frame (only once it is hung: it fades in as the painting steps back into it)
        val fa = (a * e).coerceIn(0f, 1f)
        if (fa > 0.004f) {
            o.set(r.left - fw, r.top - fw, r.right + fw, r.bottom + fw)
            // The shadow it casts on the wall: a wide soft blur, a little below (the lamp is above).
            shadow.color = 0xFF000000.toInt()
            shadow.alpha = (255 * fa).toInt()
            shadow.setShadowLayer(30f * dp, 0f, 14f * dp, android.graphics.Color.argb((150 * fa).toInt(), 0, 0, 0))
            c.drawRect(o.left + 3f * dp, o.top + 3f * dp, o.right - 3f * dp, o.bottom - 3f * dp, shadow)
            // The moulding as four bevelled faces under the picture lamp: the top face catches the
            // light, the sides less, the bottom face is in its own shadow.
            val ia = (255 * fa).toInt()
            bevel(c, o.left, o.top, o.right, o.top, r.right, r.top, r.left, r.top, 0x6E5D49, ia)
            bevel(c, o.left, o.top, r.left, r.top, r.left, r.bottom, o.left, o.bottom, 0x45392E, ia)
            bevel(c, o.right, o.top, o.right, o.bottom, r.right, r.bottom, r.right, r.top, 0x382E25, ia)
            bevel(c, o.left, o.bottom, r.left, r.bottom, r.right, r.bottom, o.right, o.bottom, 0x17120E, ia)
            // A raised bead along the middle of the moulding: lit above, shaded below.
            val mid = fw * 0.45f
            edge.strokeWidth = 1.2f * dp
            edge.color = android.graphics.Color.argb((70 * fa).toInt(), 255, 236, 205)
            c.drawLine(o.left + mid, o.top + mid, o.right - mid, o.top + mid, edge)
            edge.color = android.graphics.Color.argb((90 * fa).toInt(), 0, 0, 0)
            c.drawLine(o.left + mid, o.bottom - mid, o.right - mid, o.bottom - mid, edge)
            // The outer arris catches the lamp along the top only.
            edge.strokeWidth = 1f * dp
            edge.color = android.graphics.Color.argb((95 * fa).toInt(), 255, 240, 214)
            c.drawLine(o.left + 1f * dp, o.top + 0.5f * dp, o.right - 1f * dp, o.top + 0.5f * dp, edge)
            // A thin gilt slip around the canvas.
            slip.strokeWidth = 2.2f * dp
            slip.color = android.graphics.Color.argb((210 * fa).toInt(), 0x8E, 0x77, 0x4E)
            val s = 1.6f * dp
            c.drawRect(r.left - s, r.top - s, r.right + s, r.bottom + s, slip)
        }
        // ---- the canvas: from full bleed (e = 0) into its frame (e = 1)
        d.set(lerp(0f, r.left, e), lerp(0f, r.top, e), lerp(w, r.right, e), lerp(h, r.bottom, e))
        if (b == null) {
            dark.color = placeholder; dark.alpha = (255 * a).toInt()
            c.drawRect(d, dark)
        } else {
            val bw = b.width.toFloat(); val bh = b.height.toFloat()
            val k = maxOf(w / bw, h / bh)
            val sf = k * fullSc
            val ofx = (kotlin.math.round((w - bw * k) / 2f) - w / 2f) * fullSc + w / 2f + fullTx
            val ofy = (kotlin.math.round((h - bh * k) / 2f) - h / 2f) * fullSc + h / 2f + fullTy
            val sr = maxOf(r.width() / bw, r.height() / bh)
            val orx = r.centerX() - sr * bw / 2f
            val ory = r.centerY() - sr * bh / 2f
            val sc = lerp(sf, sr, e)
            m.setScale(sc, sc); m.postTranslate(lerp(ofx, orx, e), lerp(ofy, ory, e))
            val sh = shaderFor(slot, b)
            sh.setLocalMatrix(m)
            canvasPaint.shader = sh
            canvasPaint.alpha = (255 * a).toInt()
            c.drawRect(d, canvasPaint)
        }
        if (darken > 0.004f) { dark.color = 0xFF000000.toInt(); dark.alpha = (255 * darken * a).toInt(); c.drawRect(d, dark) }
        // The moulding's lip shades the top of the canvas a touch (it sits in the frame, not on it).
        if (fa > 0.004f) {
            m.setScale(1f, 9f * dp); m.postTranslate(0f, r.top)
            innerShader.setLocalMatrix(m)
            inner.alpha = (255 * fa).toInt()
            c.drawRect(r.left, r.top, r.right, r.top + 9f * dp, inner)
        }
    }

    /** One face of the moulding (a quad), in [rgb] at alpha [a]. */
    private fun bevel(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, rgb: Int, a: Int) {
        path.rewind()
        path.moveTo(x0, y0); path.lineTo(x1, y1); path.lineTo(x2, y2); path.lineTo(x3, y3); path.close()
        face.color = (a shl 24) or rgb
        c.drawPath(path, face)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}

// ---------------------------------------------------------------- the first look after an unlock

/**
 * Arms one ripple across Home for the first time Home shows after the screen was off (once per
 * unlock, not on every return to Home). [present] bumps when the keyguard goes away.
 */
object UnlockWatch {
    @Volatile var armed = true
    var present by mutableIntStateOf(0)
        private set
    private var registered = false

    fun ensure(ctx: Context) {
        if (registered) return
        registered = true
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    Intent.ACTION_SCREEN_OFF -> armed = true
                    Intent.ACTION_USER_PRESENT -> present++
                }
            }
        }
        val f = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT) }
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33) ctx.applicationContext.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
            else ctx.applicationContext.registerReceiver(r, f)
        }
    }

    /** Test hook (adb): behave as if the phone was just unlocked. */
    fun fake() { armed = true; present++ }
}
