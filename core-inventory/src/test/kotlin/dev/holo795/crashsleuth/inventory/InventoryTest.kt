package dev.holo795.crashsleuth.inventory

import dev.holo795.crashsleuth.model.Platform
import dev.holo795.crashsleuth.model.Side
import dev.holo795.crashsleuth.model.Situation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InventoryTest {
    @TempDir
    lateinit var root: Path

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

    private fun jar(folder: String, name: String, files: Map<String, Any>) {
        root.resolve(folder).createDirectories().resolve(name).writeBytes(zip(files))
    }

    private fun fabricMod(id: String, version: String, extra: String = "") =
        """{ "schemaVersion": 1, "id": "$id", "version": "$version", "name": "${id.replaceFirstChar { it.uppercase() }}" $extra }"""

    private fun fabricServer() {
        root.resolve("libraries/net/fabricmc/fabric-loader/0.16.10").createDirectories()
        root.resolve("versions/1.21.1").createDirectories()
        root.resolve("server.properties").writeText("")
    }

    private fun situations(inventory: Inventory) = InventoryAnalyzer.analyze(inventory).map { it.situation to it.culprits.first().id }

    @Test
    fun `detects the platform and version of a Fabric server`() {
        fabricServer()
        val inventory = InstanceScanner.scan(root)
        assertEquals(Platform.FABRIC, inventory.platform)
        assertEquals("1.21.1", inventory.minecraftVersion)
        assertEquals("0.16.10", inventory.loaderVersion)
        assertEquals(Side.SERVER, inventory.side)
    }

    @Test
    fun `a clean Fabric server gives nothing, nested jars satisfy dependencies`() {
        fabricServer()
        val nested = zip(mapOf("fabric.mod.json" to fabricMod("fabric-api-base", "0.4.0")))
        jar("mods", "fabric-api.jar", mapOf(
            "fabric.mod.json" to fabricMod("fabric-api", "0.116.0", """, "provides": ["fabric"], "jars": [{"file": "META-INF/jars/base.jar"}]"""),
            "META-INF/jars/base.jar" to nested,
        ))
        jar("mods", "chunky.jar", mapOf("fabric.mod.json" to fabricMod("chunky", "1.4.0", """, "depends": {"fabric": "*", "fabric-api-base": "*", "minecraft": ">=1.21 <1.21.2"}""")))
        assertEquals(emptyList(), situations(InstanceScanner.scan(root)))
    }

    @Test
    fun `duplicates, wrong Minecraft, missing dependency, wrong loader, client mods ignored on a server`() {
        fabricServer()
        jar("mods", "lithium-0.15.3.jar", mapOf("fabric.mod.json" to fabricMod("lithium", "0.15.3")))
        jar("mods", "lithium-0.15.4.jar", mapOf("fabric.mod.json" to fabricMod("lithium", "0.15.4")))
        jar("mods", "old.jar", mapOf("fabric.mod.json" to fabricMod("old", "1.0", """, "depends": {"minecraft": "~1.20.1"}""")))
        jar("mods", "addon.jar", mapOf("fabric.mod.json" to fabricMod("addon", "1.0", """, "depends": {"somelib": ">=2"}""")))
        jar("mods", "jei-neoforge.jar", mapOf("META-INF/neoforge.mods.toml" to "modLoader=\"javafml\"\n[[mods]]\nmodId=\"jei\"\nversion=\"19\"\n"))
        jar("mods", "sodium.jar", mapOf("fabric.mod.json" to fabricMod("sodium", "0.6", """, "environment": "client", "depends": {"iris": "*"}""")))
        val found = situations(InstanceScanner.scan(root)).toSet()
        assertTrue(Situation.DUPLICATE to "lithium" in found, "$found")
        assertTrue(Situation.WRONG_MC to "old" in found, "$found")
        assertTrue(Situation.DEP_MISSING to "addon" in found, "$found")
        assertTrue(Situation.WRONG_LOADER to "jei" in found, "$found")
        assertTrue(found.none { it.second == "sodium" }, "Fabric skips client mods on a server: $found")
    }

    @Test
    fun `NeoForge metadata, jar version placeholder, optional and client dependencies`() {
        root.resolve("libraries/net/neoforged/neoforge/21.1.209").createDirectories()
        root.resolve("libraries/net/minecraft/server/1.21.1-20240808.144430").createDirectories()
        root.resolve("server.properties").writeText("")
        jar("mods", "supplementaries.jar", mapOf(
            "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\nImplementation-Version: 3.1.0\n",
            "META-INF/neoforge.mods.toml" to """
                modLoader="javafml"
                [[mods]]
                modId="supplementaries"
                version="${'$'}{file.jarVersion}"
                [[dependencies.supplementaries]]
                modId="moonlight"
                type="required"
                versionRange="[2.0,)"
                [[dependencies.supplementaries]]
                modId="jei"
                type="optional"
                [[dependencies.supplementaries]]
                modId="clientlib"
                type="required"
                side="CLIENT"
                [[dependencies.supplementaries]]
                modId="minecraft"
                type="required"
                versionRange="[1.21.1,1.22)"
            """.trimIndent(),
        ))
        jar("mods", "kotlinforforge-all.jar", mapOf(
            "META-INF/jarjar/kff.jar" to zip(mapOf("META-INF/neoforge.mods.toml" to "[[mods]]\nmodId=\"kotlinforforge\"\n")),
        ))
        jar("mods", "kotlinmod.jar", mapOf("META-INF/neoforge.mods.toml" to "[[mods]]\nmodId=\"kotlinmod\"\n[[dependencies.kotlinmod]]\nmodId=\"kotlinforforge\"\ntype=\"required\"\n"))
        val inventory = InstanceScanner.scan(root)
        assertEquals(Platform.NEOFORGE, inventory.platform)
        assertEquals("1.21.1", inventory.minecraftVersion)
        assertEquals("3.1.0", inventory.jars.single { it.file == "supplementaries.jar" }.mods.single().version)
        assertEquals(listOf(Situation.DEP_MISSING to "supplementaries"), situations(inventory))
    }

    @Test
    fun `plugins - paper-plugin yml, missing depend, api version, mod in plugins, corrupt jar`() {
        root.resolve("plugins").createDirectories()
        root.resolve("config").createDirectories()
        root.resolve("config/paper-global.yml").writeText("")
        root.resolve("versions/1.20.4").createDirectories()
        root.resolve("versions/1.20.4/paper-1.20.4.jar").writeText("")
        jar("plugins", "ChunkyBorder.jar", mapOf("plugin.yml" to "name: ChunkyBorder\nversion: 1.2\nmain: a.B\ndepend: [Chunky]\napi-version: '1.20'\n"))
        jar("plugins", "Modern.jar", mapOf("paper-plugin.yml" to "name: Modern\nversion: 1\nmain: a.B\napi-version: '1.21'\ndependencies:\n  server:\n    LuckPerms:\n      required: false\n"))
        jar("plugins", "jei.jar", mapOf("META-INF/neoforge.mods.toml" to "[[mods]]\nmodId=\"jei\"\n"))
        root.resolve("plugins/broken.jar").writeText("not a zip")
        // Paper keeps remapped copies in a hidden sub-folder: they must not count as duplicates.
        root.resolve("plugins/.paper-remapped").createDirectories()
        Files.copy(root.resolve("plugins/Modern.jar"), root.resolve("plugins/.paper-remapped/Modern.jar"))
        val inventory = InstanceScanner.scan(root)
        assertEquals(Platform.PAPER, inventory.platform)
        val found = situations(inventory).toSet()
        assertEquals(
            setOf(
                Situation.DEP_MISSING to "ChunkyBorder",
                Situation.PLUGIN_API to "Modern",
                Situation.WRONG_LOADER to "jei",
                Situation.CORRUPT_JAR to "broken.jar",
            ),
            found,
        )
    }

    @Test
    fun `version ranges`() {
        assertTrue(Versions.matches("1.21.1", ">=1.21 <1.21.2"))
        assertFalse(Versions.matches("1.21.2", ">=1.21 <1.21.2"))
        assertTrue(Versions.matches("1.21.1", "~1.21"))
        assertFalse(Versions.matches("1.21.1", "~1.20.1"))
        assertTrue(Versions.matches("1.21.1", "1.21.x"))
        assertTrue(Versions.matches("1.21.1", "1.20.1 || 1.21.1"))
        assertFalse(Versions.matches("1.21.1", "1.20.1"))
        assertTrue(Versions.matches("1.21.1", "[1.21,1.22)"))
        assertTrue(Versions.matches("1.21.1", "[1.21, 1.21.1)"), "NeoForge loads JEI declaring this on 1.21.1")
        assertFalse(Versions.matches("1.21.1", "[1.20.1,1.20.2)"))
        assertTrue(Versions.matches("1.20.1", "[1.20,1.20.1],[1.21,)"))
        assertTrue(Versions.matches("24w14a", ">=1.21"), "snapshots are never judged")
        assertTrue(Versions.matches("26.1", ">=26.1-"), "unparsable ranges never accuse")
        assertTrue((Versions.compare("1.21-rc.1", "1.21") ?: 0) < 0)
    }
}
