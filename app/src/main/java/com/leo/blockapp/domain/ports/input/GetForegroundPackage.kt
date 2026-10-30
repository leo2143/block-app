package com.leo.blockapp.domain.ports.input

import com.leo.blockapp.domain.model.ForegroundApp

/** Input port: the app currently in the foreground and since when, or null when none is known. */
interface GetForegroundPackage {
    suspend operator fun invoke(): ForegroundApp?
}
