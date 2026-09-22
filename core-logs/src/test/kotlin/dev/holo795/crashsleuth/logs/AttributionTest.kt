package dev.holo795.crashsleuth.logs

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttributionTest {
    @Test
    fun `the game jar installed by a launcher is never a culprit`() {
        listOf("1.21.1.jar", "versions/1.21.1/1.21.1.jar", "24w14a.jar", "1.20.1-forge-47.3.0.jar", "velocity-3.4.0-566.jar", "velocity.jar").forEach { assertTrue(Attribution.isPlatformJar(it), it) }
        listOf("sodium-fabric-0.6.13+mc1.21.1.jar", "create-1.21.1-6.0.4.jar").forEach { assertFalse(Attribution.isPlatformJar(it), it) }
    }
}

class NeoForgeVersionTest {
    @Test
    fun `the NeoForge version is not taken from another jar's name`() {
        val crash = "\t\tsodium-neoforge-0.8.13+mc1.21.1.jar |Sodium |sodium |0.8.13+mc1.21.1 |ERROR\n" +
            "\t\tneoforge-21.1.251-universal.jar |NeoForge |neoforge |21.1.251 |ERROR\n"
        kotlin.test.assertEquals("21.1.251", EnvironmentDetector.detect(LogDocument(crash)).loaderVersion)
    }
}
