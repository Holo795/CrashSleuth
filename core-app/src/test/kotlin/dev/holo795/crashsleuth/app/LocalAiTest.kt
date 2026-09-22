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
