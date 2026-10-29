package com.leo.blockapp.domain.model

/** An app of the draft that currently belongs to another block and needs a confirmed move. */
data class MoveConflict(
    val packageName: String,
    val appLabel: String,
    val fromBlockId: String,
    val fromBlockName: String,
    val fromBlockWillBeDeleted: Boolean,
)
