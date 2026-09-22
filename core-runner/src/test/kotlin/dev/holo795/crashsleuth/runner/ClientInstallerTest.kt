package dev.holo795.crashsleuth.runner

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Installs a client from fake Mojang manifests; the real downloads are covered by the client lab. */
class ClientInstallerTest {
    @TempDir
    lateinit var cache: Path

    private fun sha1(bytes: ByteArray) = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private val jar = "client".toByteArray()
    private val lib = "lib".toByteArray()
    private val index = """{"objects": {}}""".toByteArray()

    private val version = """
        {"id": "1.21.1", "mainClass": "net.minecraft.client.main.Main", "javaVersion": {"majorVersion": 21},
         "assetIndex": {"id": "17", "url": "https://x/index.json", "sha1": "${sha1(index)}"},
         "downloads": {"client": {"url": "https://x/client.jar", "sha1": "${sha1(jar)}"}},
         "libraries": [
           {"name": "com.example:always:1.0", "downloads": {"artifact": {"path": "com/example/always/1.0/always-1.0.jar", "url": "https://x/always.jar", "sha1": "${sha1(lib)}"}}},
           {"name": "com.example:elsewhere:1.0", "rules": [{"action": "allow", "os": {"name": "some-other-os"}}],
            "downloads": {"artifact": {"path": "com/example/elsewhere/1.0/elsewhere-1.0.jar", "url": "https://x/elsewhere.jar", "sha1": "${sha1(lib)}"}}}
         ],
         "arguments": {
           "jvm": ["-Djava.library.path=${'$'}{natives_directory}", "-cp", "${'$'}{classpath}"],
           "game": ["--username", "${'$'}{auth_player_name}", "--gameDir", "${'$'}{game_directory}",
                    {"rules": [{"action": "allow", "features": {"is_demo_user": true}}], "value": "--demo"},
                    {"rules": [{"action": "allow", "features": {"has_custom_resolution": true}}], "value": ["--width", "${'$'}{resolution_width}"]},
                    "--quickPlayPath", "${'$'}{quickPlayPath}"]
         }}
    """.trimIndent().toByteArray()

    private val manifest = """{"versions": [{"id": "1.21.1", "url": "https://x/1.21.1.json", "sha1": "${sha1(version)}"}]}""".toByteArray()

    private val files = mapOf(
        "version_manifest_v2.json" to manifest, "1.21.1.json" to version, "client.jar" to jar,
        "always.jar" to lib, "elsewhere.jar" to lib, "index.json" to index,
    )

    @Test
    fun `vanilla client command`() {
        val fetched = mutableListOf<String>()
        val installer = ClientInstaller(cache) { uri: URI -> uri.path.substringAfterLast('/').also { fetched += it }.let(files::getValue) }
        val profile = installer.install("1.21.1")
        // Libraries for another operating system are never downloaded.
        assertFalse("elsewhere.jar" in fetched)
        assertTrue(profile.classpath.all { it.exists() })
        assertEquals("1.21.1.jar", profile.classpath.last().fileName.toString(), "the game jar itself, not the parts of its path")

        val gameDirectory = Path.of("/game")
        val command = installer.command(profile, gameDirectory, java = "java")
        val classpath = command[command.indexOf("-cp") + 1]
        assertEquals(2, classpath.split(java.io.File.pathSeparator).size)
        assertEquals("CrashSleuth", command[command.indexOf("--username") + 1])
        assertEquals(gameDirectory.toString(), command[command.indexOf("--gameDir") + 1])
        assertEquals("427", command[command.indexOf("--width") + 1], "small window")
        assertFalse("--demo" in command)
        assertFalse("--quickPlayPath" in command, "a flag whose value is unknown is dropped")
        assertTrue(command.none { it.contains("\${") })

        // Second install: nothing downloaded again.
        fetched.clear()
        installer.install("1.21.1")
        assertTrue(fetched.isEmpty(), "$fetched")
    }
}
