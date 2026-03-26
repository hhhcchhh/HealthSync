package com.example.healthsync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.healthsync.ui.heartrate.HeartRateScreen
import com.example.healthsync.ui.history.HistoryScreen
import com.example.healthsync.ui.overview.OverviewScreen
import com.example.healthsync.ui.theme.HealthSyncTheme
import dagger.hilt.android.AndroidEntryPoint

private enum class Screen(val route: String, val label: String, val icon: ImageVector) {
    Overview("overview", "概览", Icons.Default.Home),
    HeartRate("heartRate", "心率", Icons.Default.Favorite),
    History("history", "历史", Icons.Default.DateRange)
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HealthSyncTheme {
                MainNavigation()
            }
        }
    }
}

@Composable
private fun MainNavigation() {
    val navController = rememberNavController()
    val screens = Screen.entries

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                screens.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Overview.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Overview.route) { OverviewScreen() }
            composable(Screen.HeartRate.route) { HeartRateScreen() }
            composable(Screen.History.route) { HistoryScreen() }
        }
    }
}
