package com.leo.blockapp.domain.ports.input

/** Input port: pause or resume a block. Changes only `enabled`; an unknown id is a no-op. */
interface SetBlockEnabled {
    suspend operator fun invoke(id: String, enabled: Boolean)
}
