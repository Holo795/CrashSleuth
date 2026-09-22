package dev.holo795.crashsleuth.app

import dev.holo795.crashsleuth.inventory.Inventory
import dev.holo795.crashsleuth.inventory.JarEntry
import dev.holo795.crashsleuth.inventory.MetadataFormat
import dev.holo795.crashsleuth.inventory.ModMetadata
import dev.holo795.crashsleuth.model.Platform
import dev.holo795.crashsleuth.model.Situation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModrinthCheckTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `only hashes are sent, updates become information`() {
        val jar = dir.resolve("lithium.jar").also { it.writeText("jar") }
        val hash = ModrinthCheck.sha1(jar)
        val inventory = Inventory(
            platform = Platform.FABRIC,
            jars = listOf(JarEntry("lithium.jar", "mods", mods = listOf(ModMetadata(MetadataFormat.FABRIC, "lithium", "Lithium", "0.15.3")), path = jar.toString())),
        )
        val sent = mutableListOf<Pair<String, String>>()
        val check = ModrinthCheck { path, body ->
            sent += path to body
            if (path.endsWith("/update")) """{"$hash": {"id": "new", "version_number": "0.15.4"}}"""
            else """{"$hash": {"id": "old", "project_id": "gvQqBUqZ", "version_number": "0.15.3"}}"""
        }
        val findings = check.findings(check.check(inventory, "1.21.1"))
        assertEquals(Situation.OUTDATED, findings.single().situation)
        assertTrue(findings.single().evidence.single().contains("0.15.3 → 0.15.4"))
        // Nothing but hashes, the loader and the game version leaves the computer.
        assertTrue(sent.all { (_, body) -> !body.contains("lithium") && body.contains(hash) })
        assertTrue(sent.last().second.contains("\"fabric\"") && sent.last().second.contains("\"1.21.1\""))
    }
}
