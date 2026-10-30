package com.leo.blockapp.domain.ports.input

/** Input port: the package currently in the foreground, or null when none is known. */
interface GetForegroundPackage {
    suspend operator fun invoke(): String?
}
