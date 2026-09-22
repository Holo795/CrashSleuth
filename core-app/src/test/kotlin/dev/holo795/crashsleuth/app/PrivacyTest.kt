package dev.holo795.crashsleuth.app

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class PrivacyTest {
    @Test
    fun `paths, addresses and identifiers of a person are removed`() {
        assertEquals("/Users/<user>/Library/x.jar", Privacy.clean("/Users/alice/Library/x.jar"))
        assertEquals("C:\\Users\\<user>\\AppData\\Roaming\\.minecraft", Privacy.clean("C:\\Users\\Bob\\AppData\\Roaming\\.minecraft"))
        assertEquals("/home/<user>/server/logs", Privacy.clean("/home/carol/server/logs"))
        assertEquals("joined from <ip>:52011 as <uuid>, mail <email>", Privacy.clean("joined from 82.12.4.9:52011 as f261d4f8-3d63-3d50-bfb9-627e804e9cc5, mail a.b@example.org"))
        assertEquals("Minecraft 1.21.1 on 127.0.0.1:25565", Privacy.clean("Minecraft 1.21.1 on 127.0.0.1:25565"))
    }
}
