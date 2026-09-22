package dev.holo795.crashsleuth.engine

import dev.holo795.crashsleuth.inventory.Inventory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Replays every case of lab/corpus. Each one was produced by a real server, broken on purpose by the lab,
 * and comes with its logs, the inventory of its mods and plugins, and the expected answer.
 * A server that started cleanly must give no finding.
 */
class RealWorldCorpusTest {
    private val diagnoser = Diagnoser()
    private val json = Json { ignoreUnknownKeys = true }

    @TestFactory
    fun `real world cases`(): List<DynamicTest> {
        val corpus = Path.of(System.getProperty("crashsleuth.corpus") ?: "lab/corpus")
        if (!Files.isDirectory(corpus)) return emptyList()
        return Files.list(corpus).use { entries -> entries.filter { it.isDirectory() }.sorted().toList() }.map { case ->
            DynamicTest.dynamicTest(case.name) { check(case) }
        }
    }

    private fun check(case: Path) {
        val expected = Json.parseToJsonElement(case.resolve("expected.json").readText()).jsonObject
        val situation = expected["situation"]?.jsonPrimitive?.stringOrNull()
        val culprit = expected["culprit"]?.jsonPrimitive?.stringOrNull()
        val logs = (expected["logs"]?.jsonArray?.map { it.jsonPrimitive.content } ?: listOfNotNull(expected["log"]?.jsonPrimitive?.content))
            .map { Diagnoser.Log(it, case.resolve(it.substringAfterLast('/')).readText()) }
        val inventory = case.resolve("inventory.json").takeIf { it.exists() }?.let { json.decodeFromString(Inventory.serializer(), it.readText()) }
        val profiles = expected["profiles"]?.jsonArray?.flatMap { Diagnoser.profileFile(case.resolve(it.jsonPrimitive.content)) }.orEmpty()
        val report = diagnoser.diagnose(logs, inventory, profiles)
        // Crashes that leave no explanation in the logs: only the culprit search can find them (bisect.json).
        if (situation == null && expected["crashes"]?.jsonPrimitive?.content == "true") return
        if (situation == null) {
            assertTrue(report.findings.isEmpty(), "a clean start must give no finding, got ${report.findings.map { "${it.situation}${it.culprits.map { c -> c.id }}" }}")
            return
        }
        val primary = report.primary ?: fail("expected $situation, got no finding")
        assertEquals(situation, primary.situation.name, "primary finding of ${case.name}")
        if (culprit != null) {
            assertTrue(
                primary.culprits.any { it.id.contains(culprit, ignoreCase = true) || it.name?.contains(culprit, ignoreCase = true) == true },
                "expected culprit $culprit, got ${primary.culprits.map { it.id }}",
            )
        }
    }

    private fun JsonPrimitive.stringOrNull(): String? = if (isString) content else null
}
