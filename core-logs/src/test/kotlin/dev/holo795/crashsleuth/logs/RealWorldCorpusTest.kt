package dev.holo795.crashsleuth.logs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Replays every real log of lab/corpus. Each one was produced by a real server, broken on purpose
 * by the lab, and comes with the expected answer. A server that started cleanly must give no finding.
 */
class RealWorldCorpusTest {
    private val analyzer = LogAnalyzer()

    @TestFactory
    fun `real world logs`(): List<DynamicTest> {
        val corpus = Path.of(System.getProperty("crashsleuth.corpus") ?: "lab/corpus")
        if (!Files.isDirectory(corpus)) return emptyList()
        return Files.list(corpus).use { entries -> entries.filter { it.isDirectory() }.sorted().toList() }.map { case ->
            DynamicTest.dynamicTest(case.name) { check(case) }
        }
    }

    private fun check(case: Path) {
        val expected = Json.parseToJsonElement(case.resolve("expected.json").readText()).jsonObject
        val situation = expected["situation"]?.jsonPrimitive?.contentOrNullSafe()
        val culprit = expected["culprit"]?.jsonPrimitive?.contentOrNullSafe()
        val log = case.resolve(expected.getValue("log").jsonPrimitive.content)
        val report = analyzer.analyze(log.readText())
        if (situation == null) {
            assertTrue(report.findings.isEmpty(), "a clean start must give no finding, got ${report.findings.map { it.situation }}")
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

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? = if (isString) content else null
}
