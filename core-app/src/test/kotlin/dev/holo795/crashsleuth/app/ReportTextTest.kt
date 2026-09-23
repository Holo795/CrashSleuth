package dev.holo795.crashsleuth.app

import dev.holo795.crashsleuth.model.Environment
import dev.holo795.crashsleuth.model.Messages
import dev.holo795.crashsleuth.model.Platform
import dev.holo795.crashsleuth.model.Report
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportTextTest {
    private val text = ReportText(Messages.forLanguage("en"))

    private fun report(platform: Platform, minecraft: String?) =
        Report(environment = Environment(platform = platform, minecraftVersion = minecraft), findings = emptyList())

    @Test
    fun `a recent Paper that found nothing is told how to see a slow tick`() {
        val hints = text.noFindingHints(report(Platform.PAPER, "1.21.11"))
        assertEquals(1, hints.size, "$hints")
        assertTrue(hints.single().contains("spark profiler"), hints.single())
        assertTrue(text.noFindingHints(report(Platform.PURPUR, "26.2")).isNotEmpty())
    }

    @Test
    fun `older servers and other platforms are left alone`() {
        // 1.21.1 still writes "Can't keep up!", and a game client has no tick to profile this way.
        assertTrue(text.noFindingHints(report(Platform.PAPER, "1.21.1")).isEmpty())
        assertTrue(text.noFindingHints(report(Platform.FABRIC, "1.21.11")).isEmpty())
        assertTrue(text.noFindingHints(report(Platform.PAPER, null)).isEmpty())
        // Spigot runs the vanilla tick loop, which still writes "Can't keep up!".
        assertTrue(text.noFindingHints(report(Platform.SPIGOT, "26.2")).isEmpty())
    }

    @Test
    fun `no English text shows a doubled apostrophe`() {
        // Messages replaces {0} by hand, so the MessageFormat habit of writing '' shows up as it is.
        val bundle = java.util.ResourceBundle.getBundle("crashsleuth/messages", java.util.Locale.ROOT)
        val doubled = bundle.keySet().filter { bundle.getString(it).contains("''") }
        assertTrue(doubled.isEmpty(), "$doubled")
    }
}
