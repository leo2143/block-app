package com.leo.blockapp.domain.ports.input

/** Input port: whether the usage access permission is granted. Cheap, re-ask on resume. */
fun interface IsUsageAccessGranted {
    operator fun invoke(): Boolean
}
