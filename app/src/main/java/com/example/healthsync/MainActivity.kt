package com.example.healthsync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.healthsync.ui.heartrate.HeartRateScreen
import com.example.healthsync.ui.theme.HealthSyncTheme
import dagger.hilt.android.AndroidEntryPoint

/** 单 Activity + 100% Compose；当前宿主为心率闭环最小 UI（Milestone 1/6）。 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HealthSyncTheme {
                HeartRateScreen()
            }
        }
    }
}
