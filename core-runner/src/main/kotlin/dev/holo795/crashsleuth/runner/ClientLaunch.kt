package dev.holo795.crashsleuth.runner

import java.time.Duration

/** How a game client launch is watched: which line means ready, which lines mean it cannot go on. */
object ClientLaunch {
    /** The title screen is up once the block atlas is built and the sound engine started (checked on 1.21 vanilla and Fabric). */
    val READY = Regex("""Sound engine started|Created: \d+x\d+x\d+ minecraft:textures/atlas/blocks\.png-atlas""")

    /** A loader that shows its error screen waits for the player: the launch is over. */
    val FATAL = Regex("""Incompatible mods found!|Mod loading has failed|This crash report has been saved to|Crash report saved to|#@!@# Game crashed!""")

    /** In a world: the player's own join message comes back in the chat. */
    val JOINED = Regex("""(?:\[CHAT] |\[Server thread/INFO]: )\S+ joined the game""")

    /** The game went back to a disconnection screen. */
    val DISCONNECTED = Regex("""Client disconnected with reason|Disconnected from server|Connection lost|Kicked from server|Failed to connect to the server|Couldn't connect to server""")

    fun launcher(installer: ClientInstaller, profile: ClientProfile, java: String, timeout: Duration, settle: Duration, joining: Boolean = false, windows: WindowKeeper = WindowKeeper.forThisSystem()) =
        if (joining) {
            ServerLauncher(listOf(java), timeout, settle, ready = JOINED, stopCommand = null, fatal = Regex(FATAL.pattern + "|" + DISCONNECTED.pattern), windows = windows)
        } else {
            ServerLauncher(listOf(java), timeout, settle, ready = READY, stopCommand = null, fatal = FATAL, windows = windows)
        }
}
