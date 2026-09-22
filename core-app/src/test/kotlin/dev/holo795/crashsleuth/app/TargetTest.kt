package dev.holo795.crashsleuth.app

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TargetTest {
    @Test
    fun `the culprit search runs on servers and on vanilla, Fabric, NeoForge and Forge games`() {
        assertTrue(Target("/srv", TargetKind.SERVER).searchable)
        listOf("vanilla", "fabric", "neoforge", "forge", null).forEach { assertTrue(Target("/game", TargetKind.CLIENT, "1.21.1", it).searchable, "$it") }
        assertFalse(Target("/game", TargetKind.CLIENT, "1.21.1", "quilt").searchable)
        assertFalse(Target("/crash.txt", TargetKind.LOG).searchable)
    }
}
