package com.leo.blockapp.adapters.input.ui.navigation

/**
 * String routes on purpose: type-safe routes need a Bundle to rebuild arguments, which breaks plain JVM ViewModel
 * tests. The editor reads "blockId" from its SavedStateHandle.
 */
object BlockAppRoutes {

    const val USAGE = "usage"
    const val BLOCKS = "blocks"
    const val EDITOR_BLOCK_ID_ARG = "blockId"
    const val EDITOR_PATTERN = "blocks/editor?$EDITOR_BLOCK_ID_ARG={$EDITOR_BLOCK_ID_ARG}"

    private const val EDITOR_BASE = "blocks/editor"

    /** Block ids are UUIDs, so no URL encoding is needed. Null opens the editor for a new block. */
    fun editor(blockId: String?): String =
        if (blockId == null) EDITOR_BASE else "$EDITOR_BASE?$EDITOR_BLOCK_ID_ARG=$blockId"

    fun showsBottomBar(route: String?): Boolean = route == USAGE || route == BLOCKS
}
