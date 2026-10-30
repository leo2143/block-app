package com.leo.blockapp.adapters.input.ui.blocking

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leo.blockapp.R
import com.leo.blockapp.adapters.input.ui.theme.BlockAppTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The screen that covers a blocked app. Started by the accessibility service through an explicit-package intent whose
 * action and extra key come from resources (the two input adapters never reference each other). The intent carries
 * only the package name; everything shown comes from the ViewModel.
 *
 * Declared singleTask in its own task and excluded from recents. A new intent for another blocked app arrives in
 * [onNewIntent] and re-keys the ViewModel. Android glue: not testable on the JVM, the logic lives in the ViewModel.
 */
@AndroidEntryPoint
class BlockingActivity : ComponentActivity() {

    private val viewModel: BlockingViewModel by viewModels()

    // onCreate and onNewIntent already query; the onResume that follows them must not query a second time.
    private var skipNextResumeQuery = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val packageName = blockedPackageName(intent)
        if (packageName == null) {
            finish()
            return
        }
        skipNextResumeQuery = true
        viewModel.show(packageName)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = goHome()
            },
        )

        setContent {
            BlockAppTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                LaunchedEffect(uiState) {
                    if (uiState == BlockingUiState.Finished) finish()
                }
                BlockingScreen(uiState = uiState, onGoHome = ::goHome)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val packageName = blockedPackageName(intent) ?: return
        skipNextResumeQuery = true
        viewModel.show(packageName)
    }

    override fun onResume() {
        super.onResume()
        if (skipNextResumeQuery) skipNextResumeQuery = false else viewModel.onResume()
    }

    private fun blockedPackageName(intent: Intent): String? =
        intent.getStringExtra(getString(R.string.extra_blocked_package_name))?.takeIf { it.isNotBlank() }

    private fun goHome() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(home)
        finish()
    }
}
