package com.leo.blockapp.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class BlockStateTest {

    @Test
    fun `initial state starts the cycle at the start of today with no cooldown or hold`() {
        val state = BlockState.initial(blockName = "Social", startOfTodayMillis = 1_000L)

        assertEquals(
            BlockState(
                cycleStartMillis = 1_000L,
                cooldownEndMillis = null,
                cyclesToday = 0,
                cyclesDayStartMillis = 1_000L,
                holdUntilMillis = null,
                holdReason = null,
                holdPackageNames = emptySet(),
                blockName = "Social",
            ),
            state,
        )
    }
}
