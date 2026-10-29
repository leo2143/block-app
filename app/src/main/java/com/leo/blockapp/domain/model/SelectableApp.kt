package com.leo.blockapp.domain.model

/** An app the editor can offer. [ownerBlock] is set when another block already owns it. */
data class SelectableApp(
    val packageName: String,
    val label: String,
    val installed: Boolean,
    val ownerBlock: BlockReference?,
)
