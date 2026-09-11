package com.screenpulse.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.screenpulse.navigation.Screen
import com.screenpulse.repository.ThemeMode
import com.screenpulse.ui.home.HomeScreen
import com.screenpulse.ui.log.LogScreen
import com.screenpulse.ui.preview.VideoPreviewScreen
import com.screenpulse.ui.settings.SettingsScreen
import com.screenpulse.ui.theme.ScreenPulseTheme
import com.screenpulse.ui.trim.VideoTrimScreen
import com.screenpulse.ui.videolist.VideoListScreen
import com.screenpulse.util.LogManager
import com.screenpulse.util.ProjectionRequestBus
import com.screenpulse.viewmodel.RecordingViewModel
import com.screenpulse.viewmodel.SettingsViewModel

class MainActivity : AppCompatActivity() {

    private val projectionRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            LogManager.log(LogManager.TAG_UI, "Received projection request broadcast")
            ProjectionRequestBus.request()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val settingsViewModel: SettingsViewModel = viewModel()
            val themeMode by settingsViewModel.themeMode.collectAsState()
            val isDark = when (themeMode) {
                ThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            ScreenPulseTheme(darkTheme = isDark) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val recordingViewModel: RecordingViewModel = viewModel()

                    NavHost(
                        navController = navController,
                        startDestination = Screen.Home.route
                    ) {
                        composable(Screen.Home.route) {
                            HomeScreen(
                                recordingViewModel = recordingViewModel,
                                settingsViewModel = settingsViewModel,
                                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                                onNavigateToVideoList = { navController.navigate(Screen.VideoList.route) },
                                onNavigateToLogs = { navController.navigate(Screen.Logs.route) }
                            )
                        }
                        composable(Screen.Logs.route) {
                            LogScreen(
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable(Screen.Settings.route) {
                            SettingsScreen(
                                settingsViewModel = settingsViewModel,
                                onBack = { navController.popBackStack() },
                                onNavigateToRegionSelect = {
                                    val intent = android.content.Intent(
                                        this@MainActivity,
                                        com.screenpulse.ui.regionselect.RegionSelectActivity::class.java
                                    )
                                    startActivity(intent)
                                }
                            )
                        }
                        composable(Screen.VideoList.route) {
                            VideoListScreen(
                                onBack = { navController.popBackStack() },
                                onVideoClick = { filePath ->
                                    navController.navigate(Screen.VideoPreview.createRoute(filePath))
                                },
                                onVideoTrim = { filePath ->
                                    navController.navigate(Screen.VideoTrim.createRoute(filePath))
                                }
                            )
                        }
                        composable(
                            route = Screen.VideoPreview.route,
                            arguments = listOf(navArgument("filePath") { type = NavType.StringType })
                        ) { backStackEntry ->
                            val filePath = backStackEntry.arguments?.getString("filePath") ?: ""
                            VideoPreviewScreen(
                                filePath = filePath,
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable(
                            route = Screen.VideoTrim.route,
                            arguments = listOf(navArgument("filePath") { type = NavType.StringType })
                        ) { backStackEntry ->
                            val filePath = backStackEntry.arguments?.getString("filePath") ?: ""
                            VideoTrimScreen(
                                filePath = filePath,
                                onBack = { navController.popBackStack() },
                                onTrimComplete = {
                                    navController.popBackStack()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun isSystemInDarkTheme(): Boolean {
        return resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ProjectionRequestBus.REQUEST_PROJECTION_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(projectionRequestReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(projectionRequestReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(projectionRequestReceiver) }
    }
}
