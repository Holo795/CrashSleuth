package dev.holo795.crashsleuth.inventory

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class VersionsRangeTest {
    /** Forge 52.1.16 satisfies a mod asking for [52.0.16,): the lab reported it as too old. */
    @Test
    fun `a newer patch satisfies a maven range`() {
        assertTrue(Versions.matches("52.1.16", "[52.0.16,)"), "52.1.16 in [52.0.16,)")
        assertTrue(Versions.matches("52.1.16", "[51.0.5,)"), "52.1.16 in [51.0.5,)")
        assertTrue(Versions.matches("21.1.251", "[21.1.0,)"), "21.1.251 in [21.1.0,)")
        assertTrue(Versions.matches("1.21.1", "[1.21,)"), "1.21.1 in [1.21,)")
    }
}
