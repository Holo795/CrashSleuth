package dev.holo795.crashsleuth.runner

import java.time.Duration

/** How a game client launch is watched: which line means ready, which lines mean it cannot go on. */
object ClientLaunch {
    /** The title screen is up once the block atlas is built and the sound engine started (checked on 1.21 vanilla and Fabric). */
    val READY = Regex("""Sound engine started|Created: \d+x\d+x\d+ minecraft:textures/atlas/blocks\.png-atlas""")

    /** A loader that shows its error screen waits for the player: the launch is over. */
    val FATAL = Regex("""Incompatible mods found!|Mod loading has failed|This crash report has been saved to|Crash report saved to|#@!@# Game crashed!""")

    fun launcher(installer: ClientInstaller, profile: ClientProfile, java: String, timeout: Duration, settle: Duration) =
        ServerLauncher(listOf(java), timeout, settle, ready = READY, stopCommand = null, fatal = FATAL)
}
