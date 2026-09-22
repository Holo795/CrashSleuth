package dev.holo795.crashsleuth.logs

import dev.holo795.crashsleuth.model.Situation
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Each shipped signature must load and recognise the message it was written for. */
class SignatureTest {
    private val analyzer = LogAnalyzer()

    private fun primary(log: String) = analyzer.analyze(log).primary!!.let { it.situation to it.culprits.map { c -> c.id } }

    @Test
    fun `all signatures load`() {
        assertEquals(72, SignatureDetector.load(javaClass.classLoader.getResource("crashsleuth/signatures.json")!!.readText()).size)
    }

    @Test
    fun `known messages`() {
        assertEquals(
            Situation.WRONG_MC to listOf("examplemod"),
            primary("[main/FATAL]: The mod examplemod does not wish to run in Minecraft version Minecraft 1.12.2. You will have to remove it to play."),
        )
        assertEquals(
            Situation.MOD_CONFLICT to listOf("optifabric", "sodium"),
            primary("Incompatible mods found!\n\t - Mod 'OptiFabric' (optifabric) 1.14.3 is incompatible with any version of mod 'Sodium' (sodium), but a matching version is present: 0.5.8!"),
        )
        assertEquals(
            Situation.MOD_CONFLICT to listOf("foo", "bar"),
            primary("Exception in thread \"main\" java.lang.module.ResolutionException: Modules foo and bar export package com.example.shared to module baz"),
        )
        assertEquals(
            Situation.WORLD_DOWNGRADE to emptyList(),
            primary("java.lang.RuntimeException: Server attempted to load chunk saved with newer version of minecraft! 3955 > 3700"),
        )
        assertEquals(
            Situation.MOD_MISMATCH to listOf("create"),
            primary("[Server thread/WARN]: This world was saved with mod create which appears to be missing, things may not work well"),
        )
        assertEquals(
            Situation.DEP_MISSING to listOf("mymod", "kotlinforforge"),
            primary("Mod File mymod-1.0.jar needs language provider kotlinforforge:4.0 to load\nWe have found 0"),
        )
    }
}
