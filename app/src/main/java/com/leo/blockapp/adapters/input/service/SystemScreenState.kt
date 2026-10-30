package com.leo.blockapp.adapters.input.service

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import com.leo.blockapp.adapters.input.service.enforcer.ScreenState

/** Android glue: the screen is on and the keyguard is not showing. */
class SystemScreenState(context: Context) : ScreenState {
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val keyguardManager = context.getSystemService(KeyguardManager::class.java)

    override fun isInteractiveAndUnlocked(): Boolean =
        powerManager.isInteractive && !keyguardManager.isKeyguardLocked
}
