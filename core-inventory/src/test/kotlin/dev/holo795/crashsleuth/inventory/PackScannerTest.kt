package dev.holo795.crashsleuth.inventory

import dev.holo795.crashsleuth.model.Platform
import dev.holo795.crashsleuth.model.Side
import dev.holo795.crashsleuth.model.Situation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.writeBytes
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PackScannerTest {
    @TempDir
    lateinit var dir: Path

    private fun zip(files: Map<String, Any>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            files.forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name))
                out.write(if (content is ByteArray) content else content.toString().toByteArray())
                out.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun sha1(bytes: ByteArray) = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private val statusBars = zip(mapOf("META-INF/neoforge.mods.toml" to "[[mods]]\nmodId=\"statuseffectbars\"\n"))
    private val create = zip(mapOf("META-INF/neoforge.mods.toml" to "[[mods]]\nmodId=\"create\"\n"))
    private val clientOnlyEnv = zip(mapOf("META-INF/neoforge.mods.toml" to "[[mods]]\nmodId=\"skipped\"\n"))

    private fun file(path: String, bytes: ByteArray, url: String, server: String = "required", sha1: String = sha1(bytes)) =
        """{"path": "$path", "hashes": {"sha1": "$sha1"}, "env": {"client": "required", "server": "$server"}, "downloads": ["$url"], "fileSize": ${bytes.size}}"""

    @Test
    fun `Modrinth pack is read without being installed`() {
        val index = """
            {"formatVersion": 1, "game": "minecraft", "versionId": "1", "name": "Test",
             "dependencies": {"minecraft": "1.21.1", "neoforge": "21.1.200"},
             "files": [
               ${file("mods/statuseffectbars.jar", statusBars, "https://cdn.modrinth.com/data/a/statuseffectbars.jar")},
               ${file("mods/create.jar", create, "https://cdn.modrinth.com/data/b/create.jar", sha1 = "0000")},
               ${file("mods/skipped.jar", clientOnlyEnv, "https://cdn.modrinth.com/data/c/skipped.jar", server = "unsupported")},
               ${file("mods/elsewhere.jar", create, "https://example.com/elsewhere.jar")}
             ]}
        """.trimIndent()
        val pack = dir.resolve("test.mrpack").also {
            it.writeBytes(zip(mapOf("modrinth.index.json" to index, "overrides/mods/local.jar" to create, "overrides/config/x.toml" to "")))
        }
        val downloaded = mutableListOf<String>()
        val files = mapOf("statuseffectbars.jar" to statusBars, "create.jar" to create, "skipped.jar" to clientOnlyEnv)
        val scanner = PackScanner(dir.resolve("cache")) { uri: URI ->
            downloaded += uri.path.substringAfterLast('/')
            files.getValue(uri.path.substringAfterLast('/'))
        }
        val inventory = scanner.scan(pack, Side.SERVER)
        assertEquals(Platform.NEOFORGE, inventory.platform)
        assertEquals("1.21.1", inventory.minecraftVersion)
        // Files unsupported on the server are not downloaded, untrusted hosts are never contacted.
        assertEquals(listOf("statuseffectbars.jar", "create.jar"), downloaded)
        assertEquals(listOf("elsewhere.jar"), inventory.skipped)
        assertNotNull(inventory.jars.single { it.file == "create.jar" }.error, "hash mismatch must be reported")
        assertTrue(inventory.jars.any { it.file == "local.jar" && it.mods.single().id == "create" })

        val findings = InventoryAnalyzer.analyze(inventory).map { it.situation to it.culprits.first().id }
        assertTrue(Situation.CLIENT_ONLY_ON_SERVER to "statuseffectbars" in findings, "$findings")
        assertTrue(Situation.CORRUPT_JAR to "create.jar" in findings, "$findings")

        // Second scan: everything comes from the cache.
        downloaded.clear()
        scanner.scan(pack, Side.SERVER)
        assertEquals(listOf("create.jar"), downloaded, "only the file that failed its hash is fetched again")
    }

    @Test
    fun `CurseForge pack lists what cannot be downloaded`() {
        val manifest = """{"minecraft": {"version": "1.20.1", "modLoaders": [{"id": "forge-47.3.0", "primary": true}]},
            "files": [{"projectID": 238222, "fileID": 1, "required": true}], "overrides": "overrides"}"""
        val pack = dir.resolve("cf.zip").also { it.writeBytes(zip(mapOf("manifest.json" to manifest))) }
        val inventory = PackScanner(dir.resolve("cache")) { error("no download expected") }.scan(pack)
        assertEquals(Platform.FORGE, inventory.platform)
        assertEquals("47.3.0", inventory.loaderVersion)
        assertEquals(listOf("curseforge:238222"), inventory.skipped)
    }
}
