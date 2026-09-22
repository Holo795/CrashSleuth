package dev.holo795.crashsleuth.inventory

import dev.holo795.crashsleuth.model.Platform
import dev.holo795.crashsleuth.model.Side
import dev.holo795.crashsleuth.model.Situation
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModCompareTest {
    private fun mod(id: String, version: String = "1.0", content: Boolean = true, environment: Side = Side.UNKNOWN, optional: Boolean = false) =
        JarEntry("$id-$version.jar", "mods", mods = listOf(ModMetadata(MetadataFormat.FABRIC, id, version = version, environment = environment, addsContent = content, optionalOnClient = optional)))

    private fun side(vararg jars: JarEntry, platform: Platform = Platform.FABRIC, minecraft: String = "1.21.1") =
        Inventory(platform, minecraft, jars = jars.toList())

    @Test
    fun `content mod only on the server`() {
        val found = ModCompare.compare(side(mod("fabric-api")), side(mod("fabric-api"), mod("farmersdelight")))
        assertEquals(listOf("farmersdelight"), found.map { it.culprits.single().id })
        assertEquals("player", found.single().details["missing"])
    }

    @Test
    fun `content mod only on the player, and different versions`() {
        val found = ModCompare.compare(side(mod("create", "6.0.1"), mod("waystones")), side(mod("create", "6.0.4")))
        assertEquals(setOf("waystones" to "server", "create" to null), found.map { it.culprits.single().id to it.details["missing"] }.toSet())
    }

    @Test
    fun `mods that may live on one side are left alone`() {
        val player = side(mod("sodium", environment = Side.CLIENT), mod("modmenu", content = false))
        val server = side(mod("lithium", content = false), mod("servercore", environment = Side.SERVER), mod("chunky", optional = true))
        assertEquals(emptyList(), ModCompare.compare(player, server))
    }

    @Test
    fun `other loader or game version`() {
        val found = ModCompare.compare(side(platform = Platform.NEOFORGE, minecraft = "1.21.4"), side())
        assertTrue(found.any { it.situation == Situation.MOD_MISMATCH && it.details["adviceKey"] == "compare.loader" })
        assertTrue(found.any { it.situation == Situation.WRONG_MC })
    }
}
