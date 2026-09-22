package dev.holo795.crashsleuth.fixture;

import java.nio.file.Files;
import java.nio.file.Path;
import net.neoforged.fml.common.Mod;

/**
 * Test mod of the CrashSleuth lab for NeoForge. Misbehaves on purpose, as set in fixture-mode.txt of the game
 * folder: "halt" stops the game at once, with no exception and nothing in the logs to point at it.
 */
@Mod("crashsleuth_fixture")
public final class NeoForgeFixture {
    public NeoForgeFixture() {
        String mode;
        try {
            mode = Files.readString(Path.of("fixture-mode.txt")).trim();
        } catch (Exception e) {
            mode = "none";
        }
        if (mode.equals("halt")) {
            Runtime.getRuntime().halt(1);
        }
    }
}
