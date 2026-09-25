package com.mslynch.awesomesource

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mslynch.awesomesource.ui.LibraryScreen
import com.mslynch.awesomesource.ui.MainViewModel
import com.mslynch.awesomesource.ui.SetupScreen
import com.mslynch.awesomesource.ui.SettingsScreen

private const val ROUTE_SETUP = "setup"
private const val ROUTE_LIBRARY = "library"
private const val ROUTE_SETTINGS = "settings"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestHighRefreshRate()
        setContent {
            AwesomeSourceApp()
        }
    }

    /** Many OEMs (Samsung in particular) don't opt an app into >60Hz just because
     * the device supports it - the framework picks whichever display mode the
     * window explicitly asks for. Scrolling a long library list (see
     * `LibraryScreen`'s track list/scrollbar) is exactly the kind of content that
     * benefits from this, so this asks for the highest refresh rate available at
     * the display's current resolution, same physical mode group only (never a
     * different resolution) so nothing else about rendering changes. */
    private fun requestHighRefreshRate() {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager?.defaultDisplay
        val currentMode = display?.mode ?: return
        val bestMode = display.supportedModes
            .filter { it.physicalWidth == currentMode.physicalWidth && it.physicalHeight == currentMode.physicalHeight }
            .maxByOrNull { it.refreshRate }
            ?: return
        if (bestMode.refreshRate <= currentMode.refreshRate) return
        window.attributes = window.attributes.apply { preferredDisplayModeId = bestMode.modeId }
    }
}

/**
 * Root nav graph: [MainViewModel.trackCount] decides whether the first-run
 * [SetupScreen] or the returning-user [LibraryScreen] is shown, the same
 * has-anything-been-organized-yet check `legacy-expo-attempt/app/index.tsx` did via
 * `getTrackCount()` - `null` means "still checking", not "zero tracks".
 */
@Composable
fun AwesomeSourceApp() {
    MaterialTheme {
        val navController = rememberNavController()
        val viewModel: MainViewModel = viewModel()
        val trackCount = viewModel.trackCount

        when (trackCount) {
            null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> AppNavHost(navController, viewModel, startDestination = if (trackCount > 0) ROUTE_LIBRARY else ROUTE_SETUP)
        }
    }
}

@Composable
private fun AppNavHost(navController: NavHostController, viewModel: MainViewModel, startDestination: String) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable(ROUTE_SETUP) {
            // Mirrors legacy-expo-attempt/app/index.tsx's `router.replace('/library')`
            // after a successful organize - trackCount only rises above zero here
            // once MainViewModel.organize() finishes and refreshes it.
            val trackCount = viewModel.trackCount
            LaunchedEffect(trackCount) {
                if ((trackCount ?: 0) > 0) {
                    navController.navigate(ROUTE_LIBRARY) { popUpTo(ROUTE_SETUP) { inclusive = true } }
                }
            }
            SetupScreen(viewModel, onOpenSettings = { navController.navigate(ROUTE_SETTINGS) })
        }
        composable(ROUTE_LIBRARY) {
            LibraryScreen(viewModel, onOpenSettings = { navController.navigate(ROUTE_SETTINGS) })
        }
        composable(ROUTE_SETTINGS) {
            SettingsScreen(viewModel, onBack = { navController.popBackStack() })
        }
    }
}

@Preview(showBackground = true)
@Composable
fun AwesomeSourceAppPreview() {
    MaterialTheme {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}
