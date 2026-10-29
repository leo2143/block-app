package com.leo.blockapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.leo.blockapp.adapters.input.ui.theme.BlockAppTheme
import com.leo.blockapp.adapters.input.ui.usage.AppUsageScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BlockAppTheme {
                AppUsageScreen()
            }
        }
    }
}
