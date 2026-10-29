package com.leo.blockapp.adapters.input.ui.root

import com.leo.blockapp.domain.model.DeletedBlock

enum class PendingStatus {
    /** Deleted, waiting for the user to undo or for the snackbar to time out. */
    AWAITING_DECISION,

    /** Undo tapped and the restore is in flight; the runner never offers it. */
    RESTORING,

    /** The restore write failed; the entry stays queued so it can be retried. */
    RESTORE_FAILED,
}

/** A deleted block that can still be undone. The queue of these lives at root scope. */
data class PendingDeletion(
    val deleted: DeletedBlock,
    val status: PendingStatus = PendingStatus.AWAITING_DECISION,
) {
    val id: String get() = deleted.block.id
}
