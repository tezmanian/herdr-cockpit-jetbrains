// chooseBackend(): what the "Herdr version" setting picks for each combination of reachable backends.
package io.github.tezmanian.herdrcockpit

import org.junit.Assert.assertEquals
import org.junit.Test

class BackendChoiceTest {
    private fun choose(setting: String?, herdr1: Boolean, core: Boolean): Choice {
        val probed = mutableListOf<String>()
        return chooseBackend(setting, { probed += "herdr1"; herdr1 }, { probed += "core"; core })
    }

    @Test
    fun automaticPrefersHerdr1() {
        assertEquals(Choice(BackendKind.HERDR2), choose("auto", herdr1 = false, core = true))
        assertEquals(Choice(BackendKind.HERDR1, notice = true), choose("auto", herdr1 = true, core = true))
        assertEquals(Choice(BackendKind.HERDR1), choose("auto", herdr1 = true, core = false))
        assertEquals(Choice(null), choose("auto", herdr1 = false, core = false))
    }

    @Test
    fun anExplicitVersionNeverFallsBack() {
        assertEquals(Choice(null), choose("herdr1", herdr1 = false, core = true))
        assertEquals(Choice(null), choose("herdr2", herdr1 = true, core = false))
        assertEquals(Choice(BackendKind.HERDR1), choose("herdr1", herdr1 = true, core = true))
        assertEquals(Choice(BackendKind.HERDR2), choose("herdr2", herdr1 = true, core = true))
    }

    @Test
    fun anUnknownSettingIsAutomatic() {
        assertEquals(Choice(BackendKind.HERDR2), choose("herdr3", herdr1 = false, core = true))
        assertEquals(Choice(BackendKind.HERDR1, notice = true), choose(null, herdr1 = true, core = true))
    }

    @Test
    fun anExplicitVersionProbesOnlyItself() {
        val probed = mutableListOf<String>()
        chooseBackend("herdr2", { probed += "herdr1"; true }, { probed += "core"; true })
        assertEquals(listOf("core"), probed)
    }
}
