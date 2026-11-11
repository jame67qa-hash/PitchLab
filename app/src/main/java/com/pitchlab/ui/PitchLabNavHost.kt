package com.pitchlab.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.pitchlab.ui.camera.CameraScreen
import com.pitchlab.ui.history.HistoryScreen

/** 目前只有兩頁：相機（主頁）與投球紀錄。Phase 3 會再加「單球回放/剪輯」。 */
@Composable
fun PitchLabNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "camera") {
        composable("camera") {
            CameraScreen(
                onOpenHistory = { navController.navigate("history") }
            )
        }
        composable("history") {
            HistoryScreen(onBack = { navController.popBackStack() })
        }
    }
}
