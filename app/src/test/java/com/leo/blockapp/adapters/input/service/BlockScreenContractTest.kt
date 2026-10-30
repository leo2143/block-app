package com.leo.blockapp.adapters.input.service

import com.leo.blockapp.adapters.input.ui.blocking.BlockingActivity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The accessibility service recognizes the block screen by the class name declared in resources, because the
 * service and the activity are input adapters that must not reference each other. This pins that string to the real
 * class so renaming or moving the activity fails the build instead of silently breaking the detection.
 */
class BlockScreenContractTest {

    private val contractFile = File("src/main/res/values/block_screen_contract.xml")

    private fun stringResource(name: String): String? {
        val xml = contractFile.readText()
        return Regex("""<string\s+name="$name"[^>]*>([^<]*)</string>""").find(xml)?.groupValues?.get(1)?.trim()
    }

    @Test
    fun `class_block_screen matches the real block screen class name`() {
        val declared = stringResource("class_block_screen")

        assertNotNull("class_block_screen not found in ${contractFile.absolutePath}", declared)
        assertEquals(BlockingActivity::class.java.name, declared)
    }
}
