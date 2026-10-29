package com.leo.blockapp.domain.model

/** Lightweight pointer to a block, used to say which block owns an app. */
data class BlockReference(
    val id: String,
    val name: String,
)
