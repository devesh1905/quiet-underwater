package app.quiet.launcher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quiet.launcher.core.model.AppEntry
import app.quiet.launcher.core.model.ThemeMode
import app.quiet.launcher.designsystem.Q
import app.quiet.launcher.designsystem.QuietTheme
import app.quiet.launcher.feature.apps.AppActionsSheet
import app.quiet.launcher.feature.apps.AppsScreen
import app.quiet.launcher.feature.home.HomeScreen
import app.quiet.launcher.feature.lock.LockActivity
import app.quiet.launcher.feature.media.NowPlayingRepositoryImpl
import app.quiet.launcher.feature.settings.SettingsScreen
import app.quiet.launcher.feature.usage.UsageScreen
import app.quiet.launcher.feature.underwater.engine.waterWorld
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

private enum class Overlay { None, Settings, Usage, Notifications, Monochrome, Hidden, Focus, Schedules, FocusSession, BlockApp }

class MainActivity : ComponentActivity() {
    /** Bumped when Home is pressed while we are already in front: return to the Home page. */
    private var homeSignal by mutableIntStateOf(0)
    /** Bumped when the held-notifications summary is tapped. */
    private var openNotifications by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        importFavourites(intent)
        handleDeepLink(intent)
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            // Quiet Underwater: the water look is always dark (classic: unchanged).
            val underwater = BuildConfig.UNDERWATER && app.quiet.launcher.feature.underwater.engine.Underwater.active()
            val dark = if (underwater) true else when (settings.theme) {
                ThemeMode.BLACK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            QuietTheme(dark = dark, reducedMotion = settings.reducedMotion) {
                LauncherRoot(homeSignal, openNotifications)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) homeSignal++
        handleDeepLink(intent)
        importFavourites(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        if (intent != null && app.quiet.launcher.feature.notifications.NotificationFilter.wantsNotifications(intent)) {
            openNotifications++
            app.quiet.launcher.feature.notifications.NotificationFilter.consume(intent)
        }
    }

    /**
     * Debug builds only: `adb shell am start -n app.quiet.launcher/.MainActivity --esa import_favourites "A,B"`
     * matches labels (case-insensitive) and adds them to Home in that order.
     */
    private fun importFavourites(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        val labels = intent?.getStringArrayExtra("import_favourites") ?: return
        intent.removeExtra("import_favourites")
        val repo = container.apps
        container.appScope.launch {
            repo.refresh()
            val apps = repo.apps.value
            labels.map { it.trim() }.filter { it.isNotEmpty() }.forEach { label ->
                val match = apps.firstOrNull { it.label.equals(label, ignoreCase = true) }
                    ?: apps.firstOrNull { it.systemLabel.equals(label, ignoreCase = true) }
                if (match != null) repo.setFavourite(match.key, true)
                else android.util.Log.w("Quiet", "Import: no app called $label")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (container.nowPlaying as? NowPlayingRepositoryImpl)?.recheck()
        // Permission changes (usage access) and fresh numbers show the moment Home is back.
        container.appScope.launch { container.usage.refresh() }
    }
}

@Composable
private fun LauncherRoot(homeSignal: Int, openNotifications: Int) {
    val scope = rememberCoroutineScope()
    // Pages: 0 Focus, 1 Home, 2 All apps. Home is in the middle.
    val pager = rememberPagerState(initialPage = 1) { 3 }
    var overlay by remember { mutableStateOf(Overlay.None) }
    var focusSearch by remember { mutableStateOf(false) }
    var playerOpen by remember { mutableStateOf(false) }
    var sheetApp by remember { mutableStateOf<AppEntry?>(null) }
    var blockPkg by remember { mutableStateOf<String?>(null) }
    var overlayBack by remember { mutableStateOf(Overlay.None) }
    val ctx = LocalContext.current

    LaunchedEffect(homeSignal) {
        if (homeSignal > 0) {
            overlay = Overlay.None; playerOpen = false; sheetApp = null
            pager.animateScrollToPage(1, animationSpec = spring(dampingRatio = 1f, stiffness = 500f))
        }
    }
    LaunchedEffect(openNotifications) { if (openNotifications > 0) overlay = Overlay.Notifications }
    val settings by ctx.container.settings.settings.collectAsStateWithLifecycle()
    LaunchedEffect(settings.lockEnabled) { app.quiet.launcher.service.LockService.startIfEnabled(ctx) }
    BackHandler(enabled = pager.currentPage != 1 && overlay == Overlay.None) {
        scope.launch { pager.animateScrollToPage(1) }
    }
    fun openFrom(o: Overlay, back: Overlay = Overlay.None) { overlayBack = back; overlay = o }

    val waterOn = BuildConfig.UNDERWATER && app.quiet.launcher.feature.underwater.engine.Underwater.active()
    // Underwater: the pages fade back under a frosted overlay pane (fully) or the app sheet (partly),
    // so no list or Home text ghosts through the frost and collides with the words on the glass.
    val pagesFade by androidx.compose.animation.core.animateFloatAsState(
        if (!waterOn) 1f else if (overlay != Overlay.None) 0f else if (sheetApp != null) 0.4f else 1f,
        tween(240), label = "pagesFade",
    )
    val pages: @Composable () -> Unit = {
        HorizontalPager(state = pager, beyondViewportPageCount = 1, modifier = Modifier.fillMaxSize(), userScrollEnabled = !playerOpen) { page ->
            // Pages slide with a gentle depth and fade so Home and the list feel layered.
            Box(Modifier.fillMaxSize().graphicsLayer {
                // Read here (draw phase), not in composition: the offset changes on every swipe frame.
                val offset = (pager.currentPage - page) + pager.currentPageOffsetFraction
                val o = offset.absoluteValue.coerceIn(0f, 1f)
                // Underwater: a page fully out of view draws nothing (alpha 0 is never a layer), so a
                // neighbour that redraws every water frame (the apps slab) costs no offscreen pass.
                alpha = (if (waterOn && o >= 0.999f) 0f else lerp(1f, 0.2f, o)) * pagesFade
                // Underwater: the fade is applied to each draw (pages are words over the painting, little
                // overlaps), not through a full-screen offscreen buffer per page on every swipe frame.
                if (waterOn) compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                val s = lerp(1f, 0.96f, o)
                scaleX = s; scaleY = s
            }) {
                if (page == 0) {
                    app.quiet.launcher.feature.quick.FocusPage(
                        onOpenFocusSetup = { openFrom(Overlay.FocusSession) },
                        onOpenUsage = { openFrom(Overlay.Usage) },
                        onOpenSchedules = { openFrom(Overlay.Schedules) },
                    )
                } else if (page == 1) {
                    HomeScreen(
                        onOpenSearch = { focusSearch = true; scope.launch { pager.animateScrollToPage(2) } },
                        onOpenUsage = { overlay = Overlay.Usage },
                        onOpenFocusSetup = { openFrom(Overlay.FocusSession) },
                        onAppLongPress = { sheetApp = it },
                        playerOpen = playerOpen,
                        onPlayerOpenChange = { playerOpen = it },
                        isActive = pager.targetPage == 1,
                    )
                } else {
                    AppsScreen(
                        isActive = pager.settledPage == 2,
                        focusSearch = focusSearch && pager.currentPage == 2,
                        onFocusConsumed = { focusSearch = false },
                        onOpenSettings = { overlay = Overlay.Settings },
                        onBack = { scope.launch { pager.animateScrollToPage(1) } },
                        onAppLongPress = { sheetApp = it },
                    )
                }
            }
        }
    }

    // Quiet Underwater: the pages are the world the water and glass bend (null = classic look).
    val water = if (waterOn) app.quiet.launcher.feature.underwater.engine.rememberWaterHost() else null
    // Underwater: the backdrop is opaque, so neither the window's black nor the root colour is drawn
    // under it (two full-screen fills a frame less).
    val activity = ctx as? android.app.Activity
    DisposableEffect(water != null) {
        if (water != null) activity?.window?.setBackgroundDrawable(null)
        onDispose { if (water != null) activity?.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK)) }
    }
    // While the player is fully up (its own backdrop covers the screen), the world's painting under it
    // is not drawn. Uncovered the moment it starts to close.
    val playerCovers by produceState(false, playerOpen) {
        value = false
        if (playerOpen) { kotlinx.coroutines.delay(480); value = true }
    }
    CompositionLocalProvider(app.quiet.launcher.feature.underwater.engine.LocalWater provides water) {
    Box(if (water != null) Modifier.fillMaxSize() else Modifier.fillMaxSize().background(Q.colors.bg)) {
        if (water != null) {
            val battery by ctx.container.device.battery.collectAsStateWithLifecycle()
            val art by ctx.container.art.today.collectAsStateWithLifecycle()
            val swatches = app.quiet.launcher.feature.lock.charge.rememberPaintingSwatches(art?.imagePath)
            app.quiet.launcher.feature.underwater.engine.WaterEngine(
                // Home's water sits at a fixed level so the dock never moves; the lock screen follows the battery.
                water, 0.55f, battery.charging, swatches, app.quiet.launcher.designsystem.Q.reducedMotion,
                visible = true, // overlays are frosted glass and the player floats on the water: it runs on under them
            )
            // Home hangs the painting framed on a gallery wall; away from Home it opens out to full bleed.
            val gallery = remember { app.quiet.launcher.feature.underwater.GalleryHang() }
            gallery.front = { (1f - kotlin.math.abs(pager.currentPage + pager.currentPageOffsetFraction - 1f)).coerceIn(0f, 1f) }
            // Home in front and idle: the water keeps a slow, low-rate life (swell, caustics).
            LaunchedEffect(water) {
                snapshotFlow { overlay == Overlay.None && !playerOpen && sheetApp == null && pager.settledPage == 1 && pager.currentPageOffsetFraction == 0f }
                    .collect { water.ambient = it }
            }
            // Home is where the painting comes from in this edition (classic gets it from its lock screen).
            LaunchedEffect(water) { runCatching { ctx.container.art.refreshIfStale() } }
            LaunchedEffect(water) { snapshotFlow { pager.currentPage + pager.currentPageOffsetFraction }.collect { water.wake() } }
            // Above the world (not bent or frosted by it): crisp cards over frosted glass, lifted labels.
            val top = app.quiet.launcher.feature.underwater.rememberWaterTop()
            CompositionLocalProvider(app.quiet.launcher.feature.underwater.LocalWaterTop provides top, app.quiet.launcher.feature.underwater.LocalGallery provides gallery) {
                Box(Modifier.fillMaxSize().waterWorld(water)) {
                    app.quiet.launcher.feature.underwater.WaterBackdrop(art?.imagePath, swatches, covered = { playerCovers }, gallery = gallery)
                    if (app.quiet.launcher.feature.underwater.engine.WaterDebug.hide and 8 == 0) pages()
                }
                app.quiet.launcher.feature.underwater.WaterTopLayer(top)
            }
        } else pages()

        AnimatedContent(
            targetState = overlay,
            transitionSpec = {
                val sp = spring(dampingRatio = 1f, stiffness = 420f, visibilityThreshold = IntOffset.VisibilityThreshold)
                if (targetState != Overlay.None) (slideInHorizontally(sp) { it } + fadeIn(tween(200))) togetherWith fadeOut(tween(200))
                else fadeIn(tween(1)) togetherWith (slideOutHorizontally(sp) { it } + fadeOut(tween(260)))
            },
            label = "overlay",
        ) { o ->
            when (o) {
                Overlay.None -> Box(Modifier)
                Overlay.Settings -> app.quiet.launcher.feature.underwater.GlassPane(leaving = o != overlay && overlay != Overlay.None) {
                    SettingsScreen(
                        onBack = { overlay = Overlay.None },
                        onOpenLockPicker = { LockActivity.openPicker(ctx) },
                        onOpenUsage = { overlay = Overlay.Usage },
                        onOpenNotifications = { overlay = Overlay.Notifications },
                        onOpenMonochrome = { overlay = Overlay.Monochrome },
                        onOpenHidden = { overlay = Overlay.Hidden },
                        onOpenFocus = { overlay = Overlay.Focus },
                        onOpenSchedules = { openFrom(Overlay.Schedules, Overlay.Settings) },
                        onOpenFocusSession = { openFrom(Overlay.FocusSession, Overlay.Settings) },
                    )
                }
                Overlay.Focus -> app.quiet.launcher.feature.underwater.GlassPane(leaving = o != overlay && overlay != Overlay.None) {
                    app.quiet.launcher.feature.focus.settings.FocusSettingsScreen(
                        onBack = { overlay = Overlay.Settings },
                        onOpenSchedules = { openFrom(Overlay.Schedules, Overlay.Settings) },
                        onOpenBlockApp = { p -> blockPkg = p; overlayBack = Overlay.Focus; overlay = Overlay.BlockApp },
                        onOpenFocusSession = { openFrom(Overlay.FocusSession, Overlay.Settings) },
                    )
                }
                Overlay.Schedules -> app.quiet.launcher.feature.underwater.GlassPane(leaving = o != overlay && overlay != Overlay.None) {
                    app.quiet.launcher.feature.focus.settings.SchedulesScreen(onBack = { overlay = if (overlayBack == Overlay.None) Overlay.None else Overlay.Settings; overlayBack = Overlay.Settings })
                }
                Overlay.FocusSession -> app.quiet.launcher.feature.underwater.GlassPane(leaving = o != overlay && overlay != Overlay.None) {
                    app.quiet.launcher.feature.focus.settings.FocusSessionStart(onBack = { overlay = if (overlayBack == Overlay.None) Overlay.None else Overlay.Settings; overlayBack = Overlay.Settings }, onStarted = { overlay = Overlay.None; overlayBack = Overlay.Settings })
                }
                Overlay.BlockApp -> app.quiet.launcher.feature.underwater.GlassPane(leaving = o != overlay && overlay != Overlay.None) {
                    blockPkg?.let { p -> app.quiet.launcher.feature.focus.settings.BlockAppScreen(p, onBack = { overlay = overlayBack }) }
                }
                Overlay.Notifications -> app.quiet.launcher.feature.underwater.GlassPane(leaving = o != overlay && overlay != Overlay.None) {
                    app.quiet.launcher.feature.notifications.NotificationFilterScreen(onBack = { overlay = Overlay.Settings })
                }
                Overlay.Monochrome -> app.quiet.launcher.feature.underwater.GlassPane(leaving = o != overlay && overlay != Overlay.None) {
                    app.quiet.launcher.feature.monochrome.MonochromeScreen(onBack = { overlay = Overlay.Settings })
                }
                Overlay.Hidden -> app.quiet.launcher.feature.underwater.GlassPane(leaving = o != overlay && overlay != Overlay.None) {
                    app.quiet.launcher.feature.hidden.HiddenAppsScreen(onBack = { overlay = Overlay.Settings })
                }
                Overlay.Usage -> app.quiet.launcher.feature.underwater.GlassPane(leaving = o != overlay && overlay != Overlay.None) {
                    UsageScreen(onBack = { overlay = Overlay.None })
                }
            }
        }

        AppActionsSheet(app = sheetApp, onDismiss = { sheetApp = null }, onBlock = { p -> blockPkg = p; overlayBack = Overlay.None; overlay = Overlay.BlockApp })
        // Keeps the system lock-screen wallpaper in step with the chosen lock style.
        // Quiet Underwater sits beside classic Quiet: it only owns the lock wallpaper while it is the home app.
        if (!BuildConfig.UNDERWATER || remember { app.quiet.launcher.core.platform.Permissions.isDefaultLauncher(ctx) })
            app.quiet.launcher.feature.lock.LockWallpaperSync()
    }
    }
}
