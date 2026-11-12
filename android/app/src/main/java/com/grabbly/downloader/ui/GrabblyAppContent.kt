package com.grabbly.downloader.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.grabbly.downloader.AppContainer
import com.grabbly.downloader.ui.batch.BatchScreen
import com.grabbly.downloader.ui.batch.BatchViewModel
import com.grabbly.downloader.ui.downloads.DownloadsScreen
import com.grabbly.downloader.ui.downloads.DownloadsViewModel
import com.grabbly.downloader.ui.home.HomeScreen
import com.grabbly.downloader.ui.home.HomeViewModel
import com.grabbly.downloader.ui.onboarding.OnboardingScreen
import com.grabbly.downloader.ui.player.PlayerScreen
import com.grabbly.downloader.ui.settings.SettingsScreen
import com.grabbly.downloader.util.DeviceLock
import com.grabbly.downloader.util.SystemActions

private object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val DOWNLOADS = "downloads"
    const val PRIVATE = "private"
    const val SETTINGS = "settings"
    const val BATCH = "batch"
    const val PLAYER = "player/{id}?unlocked={unlocked}"

    fun player(id: Long, unlocked: Boolean = false) = "player/$id?unlocked=$unlocked"
}

/**
 * The whole app: welcome screen (first run only), Home, Downloads, the private folder, Settings,
 * "Paste several links" and the player.
 *
 * [sharedText] is a link shared to Grabbly from another app; [openDownloads] is set when a download
 * notification was tapped, [openPlayer] when a "Saved" notification was. Each is cleared through its
 * callback once handled.
 */
@Composable
fun GrabblyAppContent(
    container: AppContainer,
    sharedText: String?,
    onSharedTextHandled: () -> Unit,
    openDownloads: Boolean,
    onOpenDownloadsHandled: () -> Unit,
    openPlayer: Long?,
    onOpenPlayerHandled: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    val homeViewModel: HomeViewModel = viewModel { HomeViewModel(container) }
    // Kept for the whole app, so a list of links keeps going if the user leaves the screen
    val batchViewModel: BatchViewModel = viewModel { BatchViewModel(container) }
    // Decided once: changing the start screen later would rebuild the navigation graph
    val start = remember { if (container.settings.onboardingDone) Routes.HOME else Routes.ONBOARDING }
    val activity = LocalActivity.current
    var needsScreenLock by remember { mutableStateOf(false) }

    fun goHome() {
        container.settings.onboardingDone = true
        navController.navigate(Routes.HOME) {
            popUpTo(navController.graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }

    /** Asks for the fingerprint, face or phone PIN, then opens the private folder. */
    fun openPrivate() {
        val current = activity ?: return
        if (!DeviceLock.isAvailable(current)) {
            needsScreenLock = true
            return
        }
        DeviceLock.unlock(
            current,
            onSuccess = { navController.navigate(Routes.PRIVATE) { launchSingleTop = true } },
            onError = { message -> message?.let { SystemActions.toast(current, it) } },
        )
    }

    LaunchedEffect(sharedText) {
        if (sharedText != null) {
            goHome()
            homeViewModel.onShared(sharedText)
            onSharedTextHandled()
        }
    }

    LaunchedEffect(openDownloads) {
        if (openDownloads) {
            goHome()
            navController.navigate(Routes.DOWNLOADS) { launchSingleTop = true }
            onOpenDownloadsHandled()
        }
    }

    LaunchedEffect(openPlayer) {
        if (openPlayer != null) {
            goHome()
            navController.navigate(Routes.player(openPlayer))
            onOpenPlayerHandled()
        }
    }

    // Private files: lock again when Grabbly goes to the background, and keep them out of
    // screenshots and the recent-apps preview
    val entry by navController.currentBackStackEntryAsState()
    val showingPrivate = entry?.destination?.route == Routes.PRIVATE ||
        (entry?.destination?.route == Routes.PLAYER && entry?.arguments?.getBoolean("unlocked") == true)
    val showingPrivateNow by rememberUpdatedState(showingPrivate)
    DisposableEffect(activity) {
        val lifecycle = (activity as? ComponentActivity)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && showingPrivateNow) {
                navController.popBackStack(Routes.PRIVATE, inclusive = true)
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    DisposableEffect(showingPrivate, activity) {
        val window = activity?.window
        if (showingPrivate) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    NavHost(
        navController = navController,
        startDestination = start,
        enterTransition = { fadeIn() + slideInHorizontally { it / 8 } },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { fadeOut() + slideOutHorizontally { it / 8 } },
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onContinue = {
                    container.settings.onboardingDone = true
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                viewModel = homeViewModel,
                onOpenDownloads = { navController.navigate(Routes.DOWNLOADS) { launchSingleTop = true } },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                onOpenBatch = { navController.navigate(Routes.BATCH) { launchSingleTop = true } },
                onOpenPlayer = { id -> navController.navigate(Routes.player(id)) },
            )
        }
        composable(Routes.DOWNLOADS) {
            val downloadsViewModel: DownloadsViewModel = viewModel { DownloadsViewModel(container.downloads, privateFolder = false) }
            DownloadsScreen(
                viewModel = downloadsViewModel,
                privateFolder = false,
                onBack = {
                    if (!navController.popBackStack()) goHome()
                },
                onFindVideo = { navController.popBackStack(Routes.HOME, inclusive = false) },
                onOpen = { item -> navController.navigate(Routes.player(item.id)) },
                onOpenPrivate = ::openPrivate,
            )
        }
        composable(Routes.PRIVATE) {
            val privateViewModel: DownloadsViewModel = viewModel { DownloadsViewModel(container.downloads, privateFolder = true) }
            DownloadsScreen(
                viewModel = privateViewModel,
                privateFolder = true,
                onBack = { navController.popBackStack() },
                onFindVideo = { navController.popBackStack(Routes.HOME, inclusive = false) },
                onOpen = { item -> navController.navigate(Routes.player(item.id, unlocked = true)) },
                onOpenPrivate = {},
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                settings = container.settings,
                onBack = { navController.popBackStack() },
                onOpenPrivate = ::openPrivate,
            )
        }
        composable(Routes.BATCH) {
            BatchScreen(
                viewModel = batchViewModel,
                onBack = { navController.popBackStack() },
                onOpenDownloads = {
                    navController.navigate(Routes.DOWNLOADS) {
                        popUpTo(Routes.HOME)
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(
            Routes.PLAYER,
            arguments = listOf(
                navArgument("id") { type = NavType.LongType },
                navArgument("unlocked") {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) { backStackEntry ->
            PlayerScreen(
                downloadId = backStackEntry.arguments?.getLong("id") ?: -1L,
                unlocked = backStackEntry.arguments?.getBoolean("unlocked") ?: false,
                repository = container.downloads,
                onBack = { navController.popBackStack() },
            )
        }
    }

    if (needsScreenLock) {
        AlertDialog(
            onDismissRequest = { needsScreenLock = false },
            title = { Text("Set a screen lock first") },
            text = {
                Text(
                    "The private folder opens with your fingerprint, face or phone PIN, so your phone needs a " +
                        "screen lock. You can add one in your phone's Settings.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        needsScreenLock = false
                        activity?.let { SystemActions.openLockSettings(it) }
                    },
                ) { Text("Open Settings") }
            },
            dismissButton = { TextButton(onClick = { needsScreenLock = false }) { Text("Not now") } },
        )
    }
}
