package dev.holo795.crashsleuth.logs

import dev.holo795.crashsleuth.model.Confidence
import dev.holo795.crashsleuth.model.Platform
import dev.holo795.crashsleuth.model.Report
import dev.holo795.crashsleuth.model.Side
import dev.holo795.crashsleuth.model.Situation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LogAnalyzerTest {
    private val analyzer = LogAnalyzer()

    private fun sample(name: String): Report {
        val text = requireNotNull(javaClass.getResource("/samples/$name")) { "missing sample $name" }.readText()
        return analyzer.analyze(text)
    }

    private fun Report.find(situation: Situation) =
        assertNotNull(findings.firstOrNull { it.situation == situation }, "no $situation in ${findings.map { it.situation }}")

    @Test
    fun `neoforge missing and outdated dependencies`() {
        val report = sample("neoforge-missing-dependency.log")
        assertEquals(Platform.NEOFORGE, report.environment.platform)
        assertEquals("21.1.209", report.environment.loaderVersion)
        val missing = report.find(Situation.DEP_MISSING)
        assertEquals("geckolib", missing.details["dependency"])
        assertEquals("alexsmobs", missing.details["requester"])
        val outdated = report.find(Situation.DEP_VERSION)
        assertEquals("create", outdated.details["dependency"])
        assertEquals("6.0.1", outdated.details["actual"])
    }

    @Test
    fun `fabric missing and wrong version dependencies`() {
        val report = sample("fabric-dependencies.log")
        assertEquals(Platform.FABRIC, report.environment.platform)
        assertEquals("1.21.1", report.environment.minecraftVersion)
        assertEquals("fabric-api", report.find(Situation.DEP_MISSING).details["dependency"])
        val wrong = report.find(Situation.DEP_VERSION)
        assertEquals("sodium", wrong.details["dependency"])
        assertEquals("0.5.8", wrong.details["actual"])
    }

    @Test
    fun `paper plugin with a missing dependency and a plugin for a newer api`() {
        val report = sample("paper-unknown-dependency.log")
        assertEquals(Platform.PAPER, report.environment.platform)
        assertEquals(Side.SERVER, report.environment.side)
        val missing = report.find(Situation.DEP_MISSING)
        assertEquals("Essentials", missing.details["dependency"])
        assertEquals("EssentialsChat", missing.details["requester"])
        val api = report.find(Situation.PLUGIN_API)
        assertEquals("OldShop", api.culprits.first().id)
    }

    @Test
    fun `paper plugin failing on enable`() {
        val report = sample("paper-enable-error.log")
        val primary = assertNotNull(report.primary)
        assertEquals(Situation.UNCAUGHT_EXCEPTION, primary.situation)
        assertEquals("WorldGuardExtra", primary.culprits.first().id)
        assertEquals(Confidence.CERTAIN, primary.confidence)
    }

    @Test
    fun `java too old for a mod`() {
        val finding = sample("java-version.log").find(Situation.JAVA_VERSION)
        assertEquals("21", finding.details["required"])
        assertEquals("17", finding.details["current"])
        assertTrue(finding.culprits.any { it.id == "net.example.fancymod" })
    }

    @Test
    fun `neoforge crash on a ticking block entity`() {
        val report = sample("neoforge-block-entity-crash.txt")
        assertEquals(Platform.NEOFORGE, report.environment.platform)
        assertEquals("1.21.1", report.environment.minecraftVersion)
        assertEquals("21.0.4", report.environment.javaVersion)
        assertEquals("21.1.209", report.environment.loaderVersion)
        val primary = assertNotNull(report.primary)
        assertEquals(Situation.TICK_BLOCK_ENTITY, primary.situation)
        assertEquals("create", primary.culprits.first().id)
        assertEquals("Create", primary.culprits.first().name)
        assertEquals("6.0.4", primary.culprits.first().version)
        assertEquals("120, 64, -35", primary.details["location"])
    }

    @Test
    fun `fabric mixin failure`() {
        val finding = sample("fabric-mixin-failure.log").find(Situation.MIXIN_CONFLICT)
        assertEquals("betterclouds", finding.culprits.first().id)
    }

    @Test
    fun `server out of memory`() {
        val report = sample("out-of-memory-crash.txt")
        assertEquals(Situation.OUT_OF_MEMORY, report.primary?.situation)
        assertEquals("1.20.1", report.environment.minecraftVersion)
    }

    @Test
    fun `native crash in the amd graphics driver`() {
        val finding = sample("hs_err_pid4242.log").find(Situation.RENDER)
        assertEquals("atio6axx.dll", finding.details["library"])
    }

    @Test
    fun `vanilla crash without a third party culprit`() {
        val report = sample("vanilla-generic.txt")
        assertEquals(Platform.VANILLA, report.environment.platform)
        val primary = assertNotNull(report.primary)
        assertEquals(Situation.UNCAUGHT_EXCEPTION, primary.situation)
        assertTrue(primary.culprits.isEmpty())
        assertEquals(Confidence.LOW, primary.confidence)
    }

    @Test
    fun `jar names are turned into identifiers`() {
        assertEquals("create", Attribution.idFromJar("create-1.21.1-6.0.4.jar"))
        assertEquals("Essentials", Attribution.idFromJar("Essentials-2.21.0.jar"))
        assertEquals("sodium", Attribution.idFromJar("sodium-fabric-0.6.0+mc1.21.1.jar"))
        assertTrue(Attribution.isPlatformJar("server-1.21.1-20240808.144430-srg.jar"))
        assertTrue(Attribution.isPlatformJar("paper-api-1.21.1-R0.1-SNAPSHOT.jar"))
    }
}
