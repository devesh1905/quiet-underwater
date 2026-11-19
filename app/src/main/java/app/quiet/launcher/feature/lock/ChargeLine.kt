package app.quiet.launcher.feature.lock

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.quiet.launcher.core.platform.ChargeRate
import app.quiet.launcher.designsystem.Q
import app.quiet.launcher.designsystem.QType
import kotlin.math.roundToInt

/**
 * "Fast charging · 64%" under the lock clock's date while plugged in, in the date's own face, a
 * step quieter. The tier label crossfades when the charge rate moves between tiers (the meter in
 * [ChargeRate] already holds a tier for a few seconds, so this never flickers); the percentage
 * just counts. The line opens and closes softly when the charger goes in or out.
 */
@Composable
fun ChargeLine(ctx: LockContext, ink: Color, modifier: Modifier = Modifier, alignment: Alignment.Horizontal = Alignment.CenterHorizontally) {
    if (ctx.wallpaper) return
    val tier = ctx.charge
    val reduced = Q.reducedMotion || !ctx.animate
    // Keep the last tier while the line closes, so it fades out with its words instead of blanking.
    var last by remember { mutableStateOf(tier) }
    if (tier != ChargeRate.Tier.NONE) last = tier
    val ms = { m: Int -> if (reduced) 0 else m }
    AnimatedVisibility(
        visible = tier != ChargeRate.Tier.NONE,
        modifier = modifier,
        enter = fadeIn(tween(ms(520), ms(120))) + expandVertically(tween(ms(420)), expandFrom = Alignment.Top),
        exit = fadeOut(tween(ms(300))) + shrinkVertically(tween(ms(420), ms(160)), shrinkTowards = Alignment.Top),
    ) {
        val style = QType.body(14.sp).copy(color = ink.copy(alpha = 0.74f), letterSpacing = 0.01.em, fontFeatureSettings = "lnum, tnum")
        val pct = (ctx.chargeLevel * 100f).roundToInt().coerceIn(0, 100)
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(
                targetState = last,
                transitionSpec = {
                    (fadeIn(tween(ms(700), ms(180))) togetherWith fadeOut(tween(ms(420)))) using SizeTransform(clip = false) { _, _ -> tween(ms(520)) }
                },
                contentAlignment = when (alignment) { Alignment.Start -> Alignment.CenterStart; else -> Alignment.Center },
                label = "chargeTier",
            ) { t -> BasicText(t.label, style = style, maxLines = 1) }
            BasicText(" · " + app.quiet.launcher.core.platform.HyperCharge.percent(pct, last.charging), style = style.copy(color = ink.copy(alpha = 0.6f)), maxLines = 1)
        }
    }
}
