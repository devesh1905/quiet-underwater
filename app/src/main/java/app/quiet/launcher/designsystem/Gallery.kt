package app.quiet.launcher.designsystem

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A work hung on the wall: square art on a mat (art is never rounded, DESIGN-NOTES §3).
 * The mat is ~4.7 % of the frame so the small card and the big frame morph without the mat
 * changing proportion.
 */
@Composable
fun GalleryFrame(
    art: Bitmap?,
    fallbackTitle: String,
    matColor: Color,
    modifier: Modifier = Modifier,
    matFraction: Float = 0.047f,
    shadow: Dp = 0.dp,
    contentKey: Any? = art,
    crossfadeMs: Int = 600,
) {
    BoxWithConstraints(
        modifier
            .then(if (shadow > 0.dp) Modifier.shadow(shadow, RectangleShape, clip = false, ambientColor = Color.Black, spotColor = Color.Black) else Modifier)
            .background(matColor),
    ) {
        val pad = maxWidth * matFraction
        Box(Modifier.fillMaxSize().padding(pad).clipToBounds()) {
            Crossfade(targetState = contentKey to art, animationSpec = tween(crossfadeMs), label = "art") { (_, bmp) ->
                if (bmp != null) {
                    val img = remember(bmp) { bmp.asImageBitmap() }
                    Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    TypographicCover(fallbackTitle, Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** When a player gives no art: the title set in Fraunces on a quiet tone. */
@Composable
fun TypographicCover(title: String, modifier: Modifier = Modifier) {
    Box(modifier.background(Color(0xFF1A1916)).padding(12.dp), contentAlignment = Alignment.BottomStart) {
        BasicText(
            title.ifBlank { "Untitled" },
            style = QType.labelTitle(18.sp).copy(color = Color(0xFFEDEBE6)),
            maxLines = 4, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Museum label: italic title, then artist, then album/year/museum. */
@Composable
fun MuseumLabel(
    title: String,
    line2: String,
    line3: String?,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = QType.labelTitle(24.sp),
    ink: Color = Q.colors.ink,
    muted: Color = Q.colors.muted,
    line2Style: TextStyle = QType.body(16.sp),
    line3Style: TextStyle = QType.secondary(14.sp),
    gap: Dp = 6.dp,
    titleLines: Int = 2,
) {
    Column(modifier) {
        BasicText(title, style = titleStyle.copy(color = ink), maxLines = titleLines, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(gap))
        BasicText(line2, style = line2Style.copy(color = ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!line3.isNullOrBlank()) {
            BasicText(line3, style = line3Style.copy(color = muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Day strip (DESIGN-NOTES §3): 18 columns 06:00–24:00, 3 dp bars, full height = 30 min or more.
 * Past empty hours: 7 dp hairline stub; future: faint dot; current hour in ink.
 */
@Composable
fun DayStrip(hourlyMinutes: IntArray, currentIndex: Int, modifier: Modifier = Modifier, height: Dp = 44.dp) {
    val ink = Q.colors.ink
    val muted = Q.colors.muted
    val faint = Q.colors.faint
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val n = 18
            val step = size.width / n
            val bw = 3.dp.toPx()
            for (i in 0 until n) {
                val x = step * i + step / 2f
                val m = hourlyMinutes.getOrElse(i) { 0 }
                when {
                    i > currentIndex && currentIndex >= 0 || currentIndex < 0 ->
                        drawCircle(faint, 1.5.dp.toPx(), Offset(x, size.height - 1.5.dp.toPx()))
                    m == 0 -> drawLine(faint, Offset(x - 3.5.dp.toPx(), size.height), Offset(x + 3.5.dp.toPx(), size.height), 1.dp.toPx())
                    else -> {
                        val h = (m / 30f).coerceIn(0.12f, 1f) * size.height
                        drawRoundRectBar(if (i == currentIndex) ink else muted, x, h, bw)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(16.dp)) {
            val labels = listOf(0 to "6", 6 to "12", 12 to "18")
            labels.filter { (i, _) -> currentIndex !in 0..17 || kotlin.math.abs(i - currentIndex) > 2 }.forEach { (i, s) ->
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val step = maxWidth / 18
                    BasicText(
                        s, style = QType.small(12.sp).copy(color = muted),
                        modifier = Modifier.offset(x = step * i + step / 2 - 4.dp),
                    )
                }
            }
            if (currentIndex in 0..17) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val step = maxWidth / 18
                    BasicText(
                        "now", style = QType.small(12.sp).copy(color = ink),
                        modifier = Modifier.offset(x = step * currentIndex + step / 2 - 11.dp),
                    )
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRoundRectBar(c: Color, x: Float, h: Float, w: Float) {
    drawLine(c, Offset(x, size.height - h + w / 2), Offset(x, size.height - w / 2), w, androidx.compose.ui.graphics.StrokeCap.Round)
}

/** Hairline progress with a small knob (player). */
@Composable
fun HairlineProgress(fraction: Float, modifier: Modifier = Modifier, track: Color = Q.colors.edge, ink: Color = Q.colors.ink) {
    Canvas(modifier.fillMaxWidth().height(12.dp)) {
        val y = size.height / 2
        val f = fraction.coerceIn(0f, 1f)
        drawLine(track, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
        drawLine(ink, Offset(0f, y), Offset(size.width * f, y), 1.5.dp.toPx())
        drawCircle(ink, 3.5.dp.toPx(), Offset(size.width * f, y))
    }
}

/** A soft picture light from above, used behind framed art on lock screens. */
fun Modifier.pictureLight(color: Color = Color(0xFFFFF1DC), strength: Float = 0.10f): Modifier = drawBehind {
    drawRect(
        Brush.radialGradient(
            colors = listOf(color.copy(alpha = strength), Color.Transparent),
            center = Offset(size.width / 2, size.height * 0.18f),
            radius = size.width * 0.75f,
        )
    )
}

@Suppress("unused") private val Italic = FontStyle.Italic
