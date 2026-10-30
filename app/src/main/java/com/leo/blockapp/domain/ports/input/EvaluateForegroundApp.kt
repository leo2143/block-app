package com.leo.blockapp.domain.ports.input

import com.leo.blockapp.domain.model.ForegroundAppVerdict

/**
 * Input port: decides what to do with the app that is in the foreground. It may start a cooldown, so calling it
 * changes the persisted block state. An IOException from storage propagates so the caller can fail closed.
 *
 * [foregroundSinceMillis] is when [packageName] came to the foreground (see [GetForegroundPackage]); it lets the
 * evaluation count an app that was opened before the usage window started and has had no events since.
 */
interface EvaluateForegroundApp {
    suspend operator fun invoke(packageName: String, foregroundSinceMillis: Long): ForegroundAppVerdict
}
