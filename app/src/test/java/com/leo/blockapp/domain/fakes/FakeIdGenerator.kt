package com.leo.blockapp.domain.fakes

import com.leo.blockapp.domain.ports.output.IdGenerator

/** Deterministic ids: generated-1, generated-2, ... */
class FakeIdGenerator : IdGenerator {

    var generatedCount = 0
        private set

    override fun newId(): String {
        generatedCount += 1
        return "generated-$generatedCount"
    }
}
