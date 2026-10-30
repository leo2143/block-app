package com.leo.blockapp.domain.model

/** The app in the foreground and the timestamp of the event that opened it ([foregroundSinceMillis]). */
data class ForegroundApp(val packageName: String, val foregroundSinceMillis: Long)
