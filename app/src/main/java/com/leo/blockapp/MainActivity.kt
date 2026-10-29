package com.leo.blockapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.leo.blockapp.adapters.input.ui.root.BlockAppRoot
import com.leo.blockapp.adapters.input.ui.root.RootSnackbarViewModel
import com.leo.blockapp.adapters.input.ui.theme.BlockAppTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Activity-scoped: the undo queue and the message channel outlive every screen and tab switch.
    private val rootSnackbarViewModel: RootSnackbarViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BlockAppTheme {
                BlockAppRoot(rootSnackbarViewModel)
            }
        }
    }
}
