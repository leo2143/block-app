package com.leo.blockapp.adapters.input.service

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import com.leo.blockapp.R
import com.leo.blockapp.adapters.input.service.enforcer.BlockScreenLauncher

/**
 * Starts the block screen through an explicit-package intent. The action and the extra name live in resources so
 * this adapter never references the activity class (input adapters do not call each other).
 *
 * [performHomeAction] is the service GLOBAL_ACTION_HOME. Android glue: it cannot run on the JVM, so it stays thin
 * and the policy lives in the enforcer.
 */
class ActivityBlockScreenLauncher(
    private val context: Context,
    private val performHomeAction: () -> Boolean,
) : BlockScreenLauncher {

    override fun showBlockScreen(packageName: String) {
        val intent = Intent(context.getString(R.string.action_show_block_screen))
            .setPackage(context.packageName)
            .putExtra(context.getString(R.string.extra_blocked_package_name), packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (failure: ActivityNotFoundException) {
            Log.w(TAG, "No screen handles the block screen action", failure)
        } catch (failure: SecurityException) {
            Log.w(TAG, "The block screen could not be started", failure)
        }
    }

    override fun goHome() {
        performHomeAction()
    }

    private companion object {
        const val TAG = "BlockScreenLauncher"
    }
}
