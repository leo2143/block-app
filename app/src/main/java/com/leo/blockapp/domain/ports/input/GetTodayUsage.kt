package com.leo.blockapp.domain.ports.input

import com.leo.blockapp.domain.model.TodayUsageResult

/** Input port: what an adapter (the UI) can ask the domain for. */
interface GetTodayUsage {
    suspend operator fun invoke(): TodayUsageResult
}
