package com.leo.blockapp.adapters.output.ids

import com.leo.blockapp.domain.ports.output.IdGenerator
import java.util.UUID
import javax.inject.Inject

class UuidIdGenerator @Inject constructor() : IdGenerator {

    override fun newId(): String = UUID.randomUUID().toString()
}
