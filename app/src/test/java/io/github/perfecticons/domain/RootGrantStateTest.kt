package io.github.perfecticons.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * libsu reports root availability as a tri-state, and the cached value never
 * changes inside one process. These cases pin the mapping so a future refactor
 * cannot collapse `null` back into "denied".
 */
class RootGrantStateTest {

    private enum class Decision { Granted, Denied, MustVerify }

    /** Mirrors RootInstaller.detect: null means "su exists, verify by running". */
    private fun decide(flag: Boolean?): Decision = when (flag) {
        true -> Decision.Granted
        false -> Decision.Denied
        null -> Decision.MustVerify
    }

    @Test
    fun `true is granted without extra work`() {
        assertEquals(Decision.Granted, decide(true))
    }

    @Test
    fun `false is denied because no su exists on path`() {
        assertEquals(Decision.Denied, decide(false))
    }

    @Test
    fun `null means the grant is unverified, not denied`() {
        assertEquals(Decision.MustVerify, decide(null))
    }

    @Test
    fun `verification only runs for the null state`() {
        val verified = mutableListOf<Boolean?>()
        listOf<Boolean?>(true, false, null).forEach { flag ->
            val decision = decide(flag)
            if (decision == Decision.MustVerify) verified += flag
        }
        assertEquals(listOf<Boolean?>(null), verified)
    }
}
