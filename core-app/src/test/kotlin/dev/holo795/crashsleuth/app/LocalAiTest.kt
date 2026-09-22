package dev.holo795.crashsleuth.app

import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LocalAiTest {
    @Test
    fun `only a model on this computer is ever asked`() {
        assertFailsWith<IllegalArgumentException> { LocalAi("http://example.org:11434") }
        assertFailsWith<IllegalArgumentException> { LocalAi("http://192.168.1.10:11434") }
        LocalAi("http://localhost:11434")
        LocalAi("http://127.0.0.1:1234/v1")
    }

    @Test
    fun `nothing runs on an unused port, so no model and no error`() {
        assertTrue(LocalAi("http://127.0.0.1:9").models().isEmpty())
    }
}

class InventedSettingsTest {
    private val analysis = Analysis(
        Target("/srv", TargetKind.SERVER, "1.21.1"),
        dev.holo795.crashsleuth.model.Report(
            environment = dev.holo795.crashsleuth.model.Environment(dev.holo795.crashsleuth.model.Platform.VELOCITY, "1.21.1"),
            findings = listOf(
                dev.holo795.crashsleuth.model.Finding(
                    dev.holo795.crashsleuth.model.Situation.PROXY_FORWARDING,
                    dev.holo795.crashsleuth.model.Confidence.CERTAIN,
                    evidence = listOf("Unable to verify player details"),
                ),
            ),
        ),
    )

    @Test
    fun `a setting nobody knows is caught, real ones are left alone`() {
        val ai = LocalAi("http://127.0.0.1:9")
        val good = "Mettez le même secret dans forwarding.secret et dans proxies.velocity.secret, puis relancez."
        kotlin.test.assertEquals(emptyList(), ai.invented(good, analysis))
        val bad = "Réglez player-info-forwarding-mode sur BASIC et activez proxy.turbo-mode dans velocity.toml."
        kotlin.test.assertEquals(listOf("proxy.turbo-mode"), ai.invented(bad, analysis))
    }
}
