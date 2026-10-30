package com.leo.blockapp.domain.model

/**
 * The app in the foreground and the timestamp of the event that opened it ([foregroundSinceMillis]): the foreground
 * timestamp of the latest open activity. A stale open activity reported as foreground would be counted from the
 * start of the usage range.
 */
data class ForegroundApp(val packageName: String, val foregroundSinceMillis: Long)
