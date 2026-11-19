package app.quiet.launcher.core.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.exp

/**
 * How fast the phone is charging, from what the battery actually takes in (current x battery
 * voltage), not from the plug type: a 90 W charger on a hot phone or near full can be trickling.
 *
 * - [amps] reads `BATTERY_PROPERTY_CURRENT_NOW` (µA on most phones, mA on some).
 * - [plugWatts] reads the charger's advertised max (`max_charging_current` x `max_charging_voltage`,
 *   hidden extras; often a 5 V / 1.2 A placeholder, so only a hint before the first measurement).
 * - [tierOf] maps watts to a [Tier] with no memory; [Meter] smooths samples and adds hysteresis and
 *   a dwell so the label never flickers between tiers. [rememberChargeTier] runs a Meter in Compose.
 *
 * Thresholds: under 7.5 W slow, under 18 W normal, under 30 W fast, 30 W and up rapid.
 */
object ChargeRate {
    enum class Tier(private val base: String) {
        /** Not plugged in. */
        NONE(""),
        SLOW("Slow charging"),
        NORMAL("Charging"),
        FAST("Fast charging"),
        RAPID("Charging rapidly"),
        /** Plugged in and full. */
        FULL("Charged");

        val charging: Boolean get() = this == SLOW || this == NORMAL || this == FAST || this == RAPID

        /** The words to show: HyperOS's own charger type when the phone reports one, else the measured tier. */
        val label: String get() = HyperCharge.label(this) ?: base
    }

    /** Lower watt bound of SLOW, NORMAL, FAST, RAPID. */
    private val BOUNDS = floatArrayOf(0f, 5f, 15f, 30f)
    private val TIERS = arrayOf(Tier.SLOW, Tier.NORMAL, Tier.FAST, Tier.RAPID)

    /** Typical Li-ion cell voltage when the battery doesn't report one. */
    const val DEFAULT_VOLTS = 3.9f

    /** Instantaneous current magnitude in amps from CURRENT_NOW's raw value; NaN if unknown. */
    fun amps(raw: Int): Float {
        if (raw == Int.MIN_VALUE || raw == 0) return Float.NaN
        val a = abs(raw)
        return if (a < 20_000) a / 1000f else a / 1_000_000f
    }

    /** Reads CURRENT_NOW and returns amps (NaN if the phone doesn't report it). */
    fun readAmps(ctx: Context): Float {
        val bm = ctx.getSystemService(BatteryManager::class.java) ?: return Float.NaN
        return amps(runCatching { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }.getOrDefault(0))
    }

    /** The charger's advertised max watts from a BATTERY_CHANGED intent; 0 if it doesn't say. */
    fun plugWatts(i: Intent): Float {
        val ua = i.getIntExtra("max_charging_current", 0)
        val uv = i.getIntExtra("max_charging_voltage", 0)
        return if (ua > 0) ua / 1e6f * (if (uv > 0) uv / 1e6f else 5f) else 0f
    }

    /** Battery voltage (V) from a BATTERY_CHANGED intent, or [DEFAULT_VOLTS]. */
    fun batteryVolts(i: Intent): Float {
        val mv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        return if (mv in 2500..20000) mv / 1000f else DEFAULT_VOLTS
    }

    /** Watts flowing into the battery. */
    fun watts(amps: Float, volts: Float = DEFAULT_VOLTS): Float = if (amps.isNaN()) Float.NaN else amps * volts

    /** The tier for [watts] with no smoothing or hysteresis. */
    fun tierOf(watts: Float): Tier {
        if (watts.isNaN()) return Tier.NORMAL
        for (i in TIERS.indices.reversed()) if (watts >= BOUNDS[i]) return TIERS[i]
        return Tier.SLOW
    }

    /**
     * Smoothed tier from a stream of samples. Exponential smoothing (time constant [tauS]), then
     * hysteresis (to go up a tier the smoothed watts must clear the bound by [band], to go down they
     * must fall [band] below it) and a dwell (the new tier must hold for [dwellMs]). Near full the
     * charger tapers on purpose, so from [taperLevel] up it never reports SLOW.
     */
    class Meter(
        private val tauS: Float = 6f,
        private val band: Float = 0.12f,
        private val dwellMs: Long = 5000L,
        private val taperLevel: Float = 0.6f,
    ) {
        var smoothed = Float.NaN
            private set
        var tier = Tier.NORMAL
            private set
        private var lastAt = 0L
        private var candidate = Tier.NORMAL
        private var candidateSince = 0L
        private var started = false
        private var peak = -1

        fun reset() { peak = -1; smoothed = Float.NaN; lastAt = 0L; started = false; tier = Tier.NORMAL; candidate = Tier.NORMAL }

        /** Feeds one sample (watts into the battery; NaN = unknown) at [nowMs]; returns the tier. */
        fun update(watts: Float, level: Float, nowMs: Long = SystemClock.uptimeMillis()): Tier {
            if (!watts.isNaN() && watts >= 0f) {
                smoothed = if (smoothed.isNaN()) watts else {
                    val dt = ((nowMs - lastAt).coerceIn(0L, 60_000L)) / 1000f
                    val k = 1f - exp(-dt / tauS)
                    smoothed + (watts - smoothed) * k
                }
                lastAt = nowMs
            }
            val w = smoothed
            var want = if (w.isNaN()) tier else withBand(w)
            // A fast charger stays "fast" for the whole plug-in while the phone tapers or warms up.
            if (!w.isNaN() && TIERS.indexOf(want) > peak) peak = TIERS.indexOf(want)
            if (peak >= TIERS.indexOf(Tier.FAST) && TIERS.indexOf(want) < TIERS.indexOf(Tier.FAST)) want = Tier.FAST
            if (want == Tier.SLOW && level >= taperLevel) want = Tier.NORMAL
            if (!started) {
                // The first real reading sets the tier at once (no flicker from a default).
                if (!w.isNaN()) { tier = want; started = true }
                candidate = tier; candidateSince = nowMs
                return tier
            }
            if (want == tier) { candidate = tier; candidateSince = nowMs; return tier }
            if (want != candidate) { candidate = want; candidateSince = nowMs }
            if (nowMs - candidateSince >= dwellMs) tier = candidate
            return tier
        }

        private fun withBand(w: Float): Tier {
            val cur = TIERS.indexOf(tier).let { if (it < 0) TIERS.indexOf(tierOf(w)) else it }
            var i = cur
            // Up while the next bound is cleared with margin; down while below this bound by margin.
            while (i < TIERS.size - 1 && w >= BOUNDS[i + 1] * (1f + band)) i++
            while (i > 0 && w < BOUNDS[i] * (1f - band)) i--
            return TIERS[i]
        }
    }
}

/**
 * The charge tier, live: [ChargeRate.Tier.NONE] while [plugged] is false; while plugged, CURRENT_NOW
 * is sampled (every second for the first few, then every 2 s) through a [ChargeRate.Meter], with the
 * battery voltage, status (FULL -> Charged) and the charger's advertised watts (a first guess) from
 * BATTERY_CHANGED. Nothing runs while unplugged or out of the composition.
 */
/**
 * Xiaomi HyperOS / MIUI announce the charger themselves (sticky system broadcasts): the quick-charge type
 * (0 none, 1 quick, 2 fast, 3 super, 4 hyper) with the charger's max watts, and the battery's
 * hundredths while it charges. When present they name the charge exactly as the system does
 * ("Super fast charging · 90W", "73.39%"); other phones fall back to the measured tier.
 */
object HyperCharge {
    private const val ACTION_TYPE = "miui.intent.action.ACTION_QUICK_CHARGE_TYPE"
    private const val ACTION_DECIMAL = "miui.intent.action.ACTION_SOC_DECIMAL"
    val type = androidx.compose.runtime.mutableIntStateOf(-1)
    val powerMax = androidx.compose.runtime.mutableIntStateOf(0)
    val decimal = androidx.compose.runtime.mutableIntStateOf(-1)
    @Volatile private var registered = false

    fun ensure(ctx: Context) {
        if (registered) return
        registered = true
        val app = ctx.applicationContext
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    ACTION_TYPE -> {
                        type.intValue = i.getIntExtra("miui.intent.extra.quick_charge_type", -1)
                        powerMax.intValue = i.getIntExtra("miui.intent.extra.POWER_MAX", 0)
                    }
                    ACTION_DECIMAL -> decimal.intValue = i.getIntExtra("miui.intent.extra.soc_decimal", -1)
                }
            }
        }
        for (a in arrayOf(ACTION_TYPE, ACTION_DECIMAL)) runCatching {
            ContextCompat.registerReceiver(app, rx, IntentFilter(a), ContextCompat.RECEIVER_EXPORTED)?.let { rx.onReceive(app, it) }
        }
    }

    fun label(tier: ChargeRate.Tier): String? {
        if (!tier.charging) return null
        val t = type.intValue
        val w = powerMax.intValue
        return when {
            t >= 3 -> if (w > 0) "Super fast charging · ${w}W" else "Super fast charging"
            t == 2 -> "Fast charging"
            t == 1 -> "Quick charging"
            t == 0 -> "Charging"   // an old charger or power bank: HyperOS says just "Charging"
            else -> null
        }
    }

    /** "73.39%" while HyperOS super-charges and reports hundredths, else "73%". */
    fun percent(levelPct: Int, charging: Boolean): String {
        val d = decimal.intValue
        return if (charging && type.intValue >= 3 && d in 0..99 && levelPct < 100) "$levelPct.${d.toString().padStart(2, '0')}%" else "$levelPct%"
    }
}

@Composable
fun rememberChargeTier(plugged: Boolean, level: Float): State<ChargeRate.Tier> {
    val ctx = LocalContext.current
    remember { HyperCharge.ensure(ctx); 0 }
    val state = remember { mutableStateOf(if (plugged) ChargeRate.Tier.NORMAL else ChargeRate.Tier.NONE) }
    val meter = remember { ChargeRate.Meter() }
    val levelNow = rememberUpdatedState(level)
    val volts = remember { floatArrayOf(ChargeRate.DEFAULT_VOLTS, 0f, 0f) }   // volts, plug watts, full (1/0)
    DisposableEffect(plugged) {
        if (!plugged) {
            meter.reset(); state.value = ChargeRate.Tier.NONE
            return@DisposableEffect onDispose { }
        }
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                volts[0] = ChargeRate.batteryVolts(i)
                volts[1] = ChargeRate.plugWatts(i)
                volts[2] = if (i.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_FULL) 1f else 0f
                if (volts[2] > 0f) state.value = ChargeRate.Tier.FULL
            }
        }
        ContextCompat.registerReceiver(ctx, rx, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            ?.let { rx.onReceive(ctx, it) }
        onDispose { runCatching { ctx.unregisterReceiver(rx) } }
    }
    val ccWin = remember { LongArray(3) }   // window start ms, counter µAh, last amps (float bits)
    LaunchedEffect(plugged) {
        if (!plugged) return@LaunchedEffect
        ccWin.fill(0L)
        var n = 0
        while (true) {
            val level = levelNow.value
            val full = volts[2] > 0f || level >= 0.999f
            if (full) state.value = ChargeRate.Tier.FULL
            else {
                // CURRENT_NOW under-reports on some phones (HyperOS: ~0.6 A while the charge counter
                // shows 1.5 A), so the counter's rise over ~15 s wins when it is available.
                val bm = ctx.getSystemService(android.os.BatteryManager::class.java)
                val cc = runCatching { bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: 0 }.getOrDefault(0)
                val now = android.os.SystemClock.uptimeMillis()
                var counterAmps = Float.NaN
                if (cc > 0) {
                    if (ccWin[0] <= 0L) { ccWin[0] = now; ccWin[1] = cc.toLong() }
                    else if (now - ccWin[0] >= 15_000L) {
                        val dUah = cc - ccWin[1]
                        if (dUah > 0) counterAmps = dUah * 3.6f / ((now - ccWin[0]) / 1000f) / 1000f
                        ccWin[0] = now; ccWin[1] = cc.toLong(); ccWin[2] = java.lang.Float.floatToIntBits(counterAmps).toLong()
                    } else if (ccWin[2] != 0L) counterAmps = java.lang.Float.intBitsToFloat(ccWin[2].toInt())
                }
                val nowAmps = ChargeRate.readAmps(ctx)
                val amps = when { counterAmps.isNaN() -> nowAmps; nowAmps.isNaN() -> counterAmps; else -> maxOf(counterAmps, nowAmps) }
                var w = ChargeRate.watts(amps, volts[0])
                // No measurement yet (or none on this phone): the charger's advertised watts, if any.
                if (w.isNaN() && volts[1] > 0f) w = volts[1]
                state.value = meter.update(w, level)
                if (n % 10 == 0) android.util.Log.d("QuietCharge", "rate w=$w smoothed=${meter.smoothed} tier=${state.value} level=$level")
            }
            delay(if (n++ < 4) 1000L else 2000L)
        }
    }
    return state
}
