package dev.holo795.crashsleuth.bisect

import dev.holo795.crashsleuth.engine.Diagnoser
import dev.holo795.crashsleuth.inventory.Dependency
import dev.holo795.crashsleuth.inventory.Inventory
import dev.holo795.crashsleuth.inventory.JarEntry
import dev.holo795.crashsleuth.inventory.MetadataFormat
import dev.holo795.crashsleuth.inventory.ModMetadata
import dev.holo795.crashsleuth.runner.Outcome
import dev.holo795.crashsleuth.runner.RunResult
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The search logic against a simulated server; real launches are covered by the lab. */
class CulpritSearchTest {
    private fun mod(id: String, vararg requires: String) = JarEntry(
        "$id.jar", "mods",
        mods = listOf(ModMetadata(MetadataFormat.FABRIC, id, dependencies = requires.map { Dependency(it) })),
    )

    /** 20 mods; "lib" is needed by a, b and c. */
    private val inventory = Inventory(
        jars = listOf(mod("lib"), mod("a", "lib"), mod("b", "lib"), mod("c", "lib", "b")) + (1..16).map { mod("m$it") },
    )

    private val crash = listOf(Diagnoser.Log("console", "Exception in thread \"main\" java.lang.IllegalStateException: boom\n\tat x.y.Z.run(Z.java:1)\n"))
    private val other = listOf(Diagnoser.Log("console", "Exception in thread \"main\" java.lang.NullPointerException: other\n\tat x.y.Z.run(Z.java:1)\n"))

    private class Simulator(val crashesWhen: (Set<String>) -> List<Diagnoser.Log>?) {
        val launches = mutableListOf<Set<String>>()

        fun launch(jars: List<JarEntry>): RunResult {
            val ids = jars.map { it.mods.single().id }.toSet()
            // A test must never lack a dependency.
            jars.forEach { jar -> jar.mods.single().dependencies.forEach { check(it.id in ids) { "${jar.file} launched without ${it.id}" } } }
            launches += ids
            val logs = crashesWhen(ids)
            return RunResult(if (logs == null) Outcome.READY else Outcome.CRASHED, 1.0, null, logs ?: emptyList())
        }
    }

    @Test
    fun `single culprit found by bisection, with its dependencies`() {
        val simulator = Simulator { ids -> if ("c" in ids) crash else null }
        val result = CulpritSearch(inventory, simulator::launch).search()
        assertTrue(result.reproduced && result.complete)
        assertEquals(listOf("mods/c.jar"), result.culprits)
        assertTrue(simulator.launches.size <= 12, "${simulator.launches.size} launches")
    }

    @Test
    fun `conflict between two mods`() {
        val simulator = Simulator { ids -> if ("m3" in ids && "m14" in ids) crash else null }
        val result = CulpritSearch(inventory, simulator::launch).search()
        assertEquals(setOf("mods/m3.jar", "mods/m14.jar"), result.culprits.toSet())
        assertTrue(result.complete)
    }

    @Test
    fun `suspect from the log is confirmed in two launches`() {
        val simulator = Simulator { ids -> if ("m7" in ids) crash else null }
        val result = CulpritSearch(inventory, simulator::launch).search(suspects = listOf("m7"))
        assertEquals(listOf("mods/m7.jar"), result.culprits)
        assertEquals(3, simulator.launches.size, "reference, without the suspect, the suspect alone")
    }

    @Test
    fun `wrong suspect falls back to the search`() {
        val simulator = Simulator { ids -> if ("m9" in ids) crash else null }
        val result = CulpritSearch(inventory, simulator::launch).search(suspects = listOf("a"))
        assertEquals(listOf("mods/m9.jar"), result.culprits)
    }

    @Test
    fun `another crash is not mistaken for the one searched`() {
        // m2 crashes too, but differently: it must not be reported.
        val simulator = Simulator { ids -> if ("m5" in ids) crash else if ("m2" in ids) other else null }
        val result = CulpritSearch(inventory, simulator::launch).search()
        assertEquals(listOf("mods/m5.jar"), result.culprits)
    }

    @Test
    fun `a harmless logged error is not part of the crash identity`() {
        // Real case (Paper): m4 logs a remapping error but works; m11 stops the JVM without a word.
        // Recognised with certainty by a signature, and still not what stopped the server.
        val harmless = Diagnoser.Log(
            "logs/latest.log",
            "[ERROR]: java.lang.RuntimeException: Failed to remap plugin jar 'plugins/m4.jar'\n\tat a.b.C.run(C.java:1)\n" +
                "Caused by: java.lang.IllegalArgumentException: Unsupported class file major version 69\n\tat a.b.C.run(C.java:1)\n",
        )
        val simulator = Simulator { ids -> if ("m11" in ids) listOfNotNull(harmless.takeIf { "m4" in ids }) else null }
        val result = CulpritSearch(inventory, simulator::launch).search(suspects = listOf("m4"))
        assertEquals(listOf("mods/m11.jar"), result.culprits)
    }

    @Test
    fun `crash that happens one time out of three`() {
        var launches = 0
        // Deterministic "random": m6 crashes the server on every third launch it is part of.
        val simulator = Simulator { ids -> if ("m6" in ids && launches++ % 3 == 0) crash else null }
        val result = CulpritSearch(inventory, simulator::launch, maxRuns = 200, repeat = 4).search()
        assertEquals(listOf("mods/m6.jar"), result.culprits)
    }

    @Test
    fun `parallel launches give the same answer`() {
        val simulator = Simulator { ids -> if ("m3" in ids && "m14" in ids) crash else null }
        val synchronizedLaunch = { jars: List<JarEntry> -> synchronized(simulator) { simulator.launch(jars) } }
        val result = CulpritSearch(inventory, synchronizedLaunch, parallel = 3).search()
        assertEquals(setOf("mods/m3.jar", "mods/m14.jar"), result.culprits.toSet())
        assertTrue(result.complete)
    }

    @Test
    fun `nothing to search when the server starts`() {
        val result = CulpritSearch(inventory, Simulator { null }::launch).search()
        assertFalse(result.reproduced)
        assertTrue(result.culprits.isEmpty())
    }

    @Test
    fun `budget is respected`() {
        val result = CulpritSearch(inventory, Simulator { ids -> if ("m3" in ids && "m14" in ids) crash else null }::launch, maxRuns = 4).search()
        assertFalse(result.complete)
        assertTrue(result.runs.size <= 4)
    }
}
