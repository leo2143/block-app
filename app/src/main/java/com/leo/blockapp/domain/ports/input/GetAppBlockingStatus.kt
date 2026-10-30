package com.leo.blockapp.domain.ports.input

import com.leo.blockapp.domain.model.BlockScreenState

/**
 * Input port: what the block screen shows for [packageName] right now. Read-only; the status is recomputed on every
 * call, so the screen re-queries on resume and when its countdown reaches zero.
 *
 * Errors propagate (for example an IOException from storage). A failure means "unknown", never
 * [BlockScreenState.Inactive]: callers must keep the app blocked and retry instead of treating it as released.
 */
interface GetAppBlockingStatus {
    suspend operator fun invoke(packageName: String): BlockScreenState
}
