package dev.holo795.crashsleuth.engine

import dev.holo795.crashsleuth.model.Situation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A log pasted without its files is all many people have: the loader's own mod list is enough. */
class KnownPairsFromLogTest {
    private val log = """
        [01:36:00] [main/INFO]: Loading 93 mods:
        	- fabric-api 0.116.17+1.21.1
        	- jade 15.10.5
        	- notenoughcrashes 4.4.9+1.21.1
        	- sodium 0.8.13+mc1.21.1
        [01:36:02] [main/INFO]: Done!
    """.trimIndent()

    @Test
    fun `two mods known not to work together are named from the mod list alone`() {
        val report = Diagnoser().diagnose(listOf(Diagnoser.Log("latest.log", log)), null)
        val conflict = assertNotNull(
            report.findings.firstOrNull { it.situation == Situation.MOD_CONFLICT },
            "got ${report.findings.map { it.situation }}",
        )
        assertEquals(listOf("jade", "notenoughcrashes"), conflict.culprits.map { it.id })
        assertTrue(conflict.evidence.any { it.startsWith("https://") }, "the answer says where it comes from")
    }

    @Test
    fun `a mod list without that pair says nothing`() {
        val alone = log.lines().filterNot { it.contains("notenoughcrashes") }.joinToString("\n")
        assertTrue(Diagnoser().diagnose(listOf(Diagnoser.Log("latest.log", alone)), null).findings.isEmpty())
    }
}
