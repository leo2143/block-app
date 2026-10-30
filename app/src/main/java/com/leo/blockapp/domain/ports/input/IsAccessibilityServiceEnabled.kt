package com.leo.blockapp.domain.ports.input

/** Input port: whether the accessibility service that detects the foreground app is turned on. Cheap, re-ask on resume. */
fun interface IsAccessibilityServiceEnabled {
    operator fun invoke(): Boolean
}
