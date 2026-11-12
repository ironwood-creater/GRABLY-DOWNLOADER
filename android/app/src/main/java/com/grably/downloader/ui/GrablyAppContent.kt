package com.grably.downloader.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.grably.downloader.AppContainer
import com.grably.downloader.ui.downloads.DownloadsScreen
import com.grably.downloader.ui.downloads.DownloadsViewModel
import com.grably.downloader.ui.home.HomeScreen
import com.grably.downloader.ui.home.HomeViewModel
import com.grably.downloader.ui.onboarding.OnboardingScreen

private object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val DOWNLOADS = "downloads"
}

/**
 * The whole app: welcome screen (first run only), Home and Downloads.
 *
 * [sharedText] is a link shared to Grabbly from another app; [openDownloads] is set when
 * a download notification was tapped. Both are cleared through their callbacks once handled.
 */
@Composable
fun GrablyAppContent(
    container: AppContainer,
    sharedText: String?,
    onSharedTextHandled: () -> Unit,
    openDownloads: Boolean,
    onOpenDownloadsHandled: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    val homeViewModel: HomeViewModel = viewModel { HomeViewModel(container) }
    // Decided once: changing the start screen later would rebuild the navigation graph
    val start = remember { if (container.settings.onboardingDone) Routes.HOME else Routes.ONBOARDING }

    LaunchedEffect(sharedText) {
        if (sharedText != null) {
            container.settings.onboardingDone = true
            navController.navigate(Routes.HOME) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
            homeViewModel.onShared(sharedText)
            onSharedTextHandled()
        }
    }

    LaunchedEffect(openDownloads) {
        if (openDownloads) {
            container.settings.onboardingDone = true
            navController.navigate(Routes.HOME) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
            navController.navigate(Routes.DOWNLOADS) { launchSingleTop = true }
            onOpenDownloadsHandled()
        }
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
            )
        }
        composable(Routes.DOWNLOADS) {
            val downloadsViewModel: DownloadsViewModel = viewModel { DownloadsViewModel(container.downloads) }
            DownloadsScreen(
                viewModel = downloadsViewModel,
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Routes.HOME) {
                            popUpTo(navController.graph.id) { inclusive = true }
                        }
                    }
                },
                onFindVideo = { navController.popBackStack(Routes.HOME, inclusive = false) },
            )
        }
    }
}
