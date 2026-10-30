package com.leo.blockapp.domain.ports.input

import com.leo.blockapp.domain.model.ForegroundAppVerdict

/**
 * Input port: decides what to do with the app that is in the foreground. It may start a cooldown, so calling it
 * changes the persisted block state. An IOException from storage propagates so the caller can fail closed.
 */
interface EvaluateForegroundApp {
    suspend operator fun invoke(packageName: String): ForegroundAppVerdict
}
