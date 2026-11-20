package app.quiet.launcher.service

import app.quiet.launcher.feature.underwater.UnlockWatch
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.quiet.launcher.container
import app.quiet.launcher.feature.underwater.engine.WaterDebug
import app.quiet.launcher.feature.lock.LockDebug
import app.quiet.launcher.feature.focus.FocusRuntime
import app.quiet.launcher.feature.focus.engine.Behaviour
import app.quiet.launcher.feature.focus.engine.Schedule
import app.quiet.launcher.feature.notifications.NotificationBubbles
import app.quiet.launcher.feature.notifications.NotificationFilter
import kotlinx.coroutines.launch

/**
 * Set-up commands from the owner's computer over adb. Protected by android.permission.DUMP, which
 * only the shell and the system hold, so other apps on the phone cannot send these.
 *
 *   adb shell am broadcast -a app.quiet.launcher.SHELL -n app.quiet.launcher/.service.ShellCommandReceiver \
 *       --es cmd notif_setup --es allow "com.whatsapp,com.google.android.gm" --ez enable true
 *   ... --es cmd favourites --es labels "WhatsApp,Gmail"
 */
class ShellCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        app.container.appScope.launch {
            try {
                when (intent.getStringExtra("cmd")) {
                    "notif_setup" -> {
                        val filter = NotificationFilter.get(app)
                        intent.getStringExtra("allow").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                            .forEach { filter.setAllowed(it, true) }
                        intent.getStringExtra("deny").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                            .forEach { filter.setAllowed(it, false) }
                        if (intent.hasExtra("enable")) filter.setEnabled(intent.getBooleanExtra("enable", true))
                        Log.i("Quiet", "notif_setup done")
                    }
                    "favourites" -> {
                        val repo = app.container.apps
                        repo.refresh()
                        intent.getStringExtra("labels").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                            .forEach { label ->
                                repo.apps.value.firstOrNull { it.label.equals(label, true) || it.systemLabel.equals(label, true) }
                                    ?.let { repo.setFavourite(it.key, true) }
                            }
                    }
                    "focus_import" -> focusImport(app, intent)
                    // Quiet Underwater test knobs: --ei shader 0..3  --ez still true|false  --ez logfeel true|false
                    "water" -> android.os.Handler(android.os.Looper.getMainLooper()).post {
                        val d = WaterDebug
                        if (intent.hasExtra("shader")) d.shaderMode = intent.getIntExtra("shader", 0)
                        if (intent.hasExtra("still")) d.forceStill = intent.getBooleanExtra("still", false)
                        if (intent.hasExtra("logfeel")) d.logFeel = intent.getBooleanExtra("logfeel", false)
                        // Underwater Home: behave as if the phone was just unlocked (the first-look ripple).
                        if (intent.getBooleanExtra("unlock", false)) UnlockWatch.fake()
                        Log.i("QuietWater", "debug shader=${d.shaderMode} still=${d.forceStill} logfeel=${d.logFeel}")
                    }
                    // Lock screen test knobs (session only, nothing saved): see feature/lock/LockDebug.kt.
                    "lock" -> LockDebug.handle(app, intent)
                    // Underwater edition test hook: a Home bubble with no real notification behind it.
                    //   ... --es cmd bubble --es pkg com.whatsapp --es title "Asha" --es text "On my way"
                    "bubble" -> NotificationBubbles.inject(
                        app, intent.getStringExtra("pkg") ?: "com.android.shell",
                        intent.getStringExtra("title").orEmpty(), intent.getStringExtra("text").orEmpty(),
                    )
                    else -> Log.w("Quiet", "Unknown shell command")
                }
            } catch (e: Exception) {
                Log.w("Quiet", "Shell command failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Mirror another launcher's focus set-up.
     *  --es reminders "Label,Label"   reminders ON for these, OFF for every other default-watched app
     *  --es behaviour EXIT_AND_BLOCK  --ei blockMinutes 2  --ez floating true --ez launchDelay true
     *  --es schedules "Name|06:30|12:00|Label;Label#Name|22:15|01:00|Label"   (every day)
     *  --ez shortVideo true
     */
    private suspend fun focusImport(app: Context, intent: Intent) {
        val repo = FocusRuntime.get(app).repo
        val apps = app.container.apps
        apps.refresh()
        fun pkgFor(label: String) = apps.apps.value.firstOrNull { it.label.equals(label, true) || it.systemLabel.equals(label, true) }?.key?.packageName
        fun labels(csv: String?, sep: Char) = csv.orEmpty().split(sep).map { it.trim() }.filter { it.isNotEmpty() }

        intent.getStringExtra("reminders")?.let { csv ->
            val on = labels(csv, ',').mapNotNull { l -> pkgFor(l).also { if (it == null) Log.w("Quiet", "import: no app $l") } }.toSet()
            on.forEach { p -> repo.updateRule(p) { it.copy(reminder = true) } }
            val watched = repo.snapshot.value.defaultWatched + repo.snapshot.value.rules.filterValues { it.reminder }.keys
            (watched - on).forEach { p -> repo.updateRule(p) { it.copy(reminder = false) } }
        }
        repo.updateConfig { c ->
            c.copy(
                reminderEnabled = true,
                behaviour = intent.getStringExtra("behaviour")?.let { runCatching { Behaviour.valueOf(it) }.getOrNull() } ?: c.behaviour,
                blockMinutes = intent.getIntExtra("blockMinutes", c.blockMinutes),
                floatingBar = intent.getBooleanExtra("floating", c.floatingBar),
                launchDelay = intent.getBooleanExtra("launchDelay", c.launchDelay),
            )
        }
        intent.getStringExtra("schedules")?.let { spec ->
            val now = System.currentTimeMillis()
            val list = labels(spec, '#').mapIndexedNotNull { i, part ->
                val f = part.split('|')
                if (f.size < 4) return@mapIndexedNotNull null
                fun min(t: String) = t.split(':').let { it[0].trim().toInt() * 60 + it[1].trim().toInt() }
                Schedule(
                    id = "imported-$i-" + f[0].lowercase().replace(' ', '-'),
                    name = f[0], startMinute = min(f[1]), endMinute = min(f[2]),
                    days = java.time.DayOfWeek.entries.toSet(),
                    apps = labels(f[3], ';').mapNotNull(::pkgFor).toSet(),
                    enabled = true, createdMs = now,
                )
            }
            repo.saveSchedules(list)
        }
        if (intent.hasExtra("shortVideo")) repo.setShortVideoEnabled(intent.getBooleanExtra("shortVideo", false))
        Log.i("Quiet", "focus_import done")
    }
}
