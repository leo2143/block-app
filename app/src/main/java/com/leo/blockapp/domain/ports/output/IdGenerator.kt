package com.leo.blockapp.domain.ports.output

/** Output port: new unique ids, so the domain stays deterministic in tests. */
interface IdGenerator {
    fun newId(): String
}
