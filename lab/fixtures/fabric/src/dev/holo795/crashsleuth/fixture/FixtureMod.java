package dev.holo795.crashsleuth.fixture;

import java.nio.file.Files;
import java.util.Random;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Misbehaves on purpose so the lab can record what real game clients do. The mode is read from
 * fixture-mode.txt in the game folder. Needs nothing but Fabric Loader.
 */
public final class FixtureMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        String mode = mode();
        System.out.println("[CrashSleuthFixture] mode: " + mode);
        if (mode.equals("throw")) {
            throw new IllegalStateException("Texture cache is not ready");
        }
        // Stops the JVM at once: no exception, no crash report.
        if (mode.equals("halt")) Runtime.getRuntime().halt(1);
        if (mode.startsWith("halt-random:") && new Random().nextInt(100) < Integer.parseInt(mode.substring("halt-random:".length()))) {
            Runtime.getRuntime().halt(1);
        }
        // Only dies when another given mod is installed (a conflict).
        if (mode.startsWith("halt-with:") && FabricLoader.getInstance().isModLoaded(mode.substring("halt-with:".length()))) {
            Runtime.getRuntime().halt(1);
        }
    }

    /** The mode, read once; the mixins ask for it while the game runs. */
    public static String mode() {
        if (cached == null) cached = readMode();
        return cached;
    }

    private static String cached;

    private static String readMode() {
        try {
            return Files.readString(FabricLoader.getInstance().getGameDir().resolve("fixture-mode.txt")).trim();
        } catch (Exception e) {
            return "none";
        }
    }
}
