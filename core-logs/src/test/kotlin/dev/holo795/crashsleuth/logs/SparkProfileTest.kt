package dev.holo795.crashsleuth.logs

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SparkProfileTest {
    /** Saved by the spark built into Paper 1.21.1 in the lab: a plugin spends 70 ms of every tick. */
    private val bytes = javaClass.classLoader.getResource("spark/lag.sparkprofile")!!.readBytes()

    @Test
    fun `the plugin that takes the main thread is named with its share`() {
        val profile = SparkProfile.read(bytes)!!
        println(profile)
        val top = profile.shares.first()
        assertEquals("CrashSleuthFixture", top.source)
        assertTrue(top.percent > 40, "$top")
        assertEquals("FixturePlugin.recalculatePrices", top.topMethod)
    }
}
