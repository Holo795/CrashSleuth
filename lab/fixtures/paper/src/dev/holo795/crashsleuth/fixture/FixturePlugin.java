package dev.holo795.crashsleuth.fixture;

import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Misbehaves on purpose so the lab can record what real Paper servers print. The mode is read from
 * fixture-mode.txt in the server directory.
 */
public final class FixturePlugin extends JavaPlugin {
    private Object missingService;

    @Override
    public void onEnable() {
        String mode = readMode();
        getLogger().info("CrashSleuth fixture mode: " + mode);
        switch (mode) {
            case "enable-npe" -> missingService.toString();
            case "task-exception" -> Bukkit.getScheduler().runTaskTimer(this, this::brokenTask, 100L, 40L);
            case "main-thread-hang" -> Bukkit.getScheduler().runTaskLater(this, this::blockMainThread, 100L);
            // Every tick takes 70 ms of real work in the plugin: the server falls behind without stopping.
            case "lag" -> Bukkit.getScheduler().runTaskTimer(this, this::recalculatePrices, 100L, 1L);
            // The main thread and a plugin thread each hold the lock the other one waits for.
            case "deadlock" -> Bukkit.getScheduler().runTaskLater(this, this::lockInOrder, 100L);
            // Keeps what it allocates: memory runs out, in the heap or in the container around it.
            case "leak" -> Bukkit.getScheduler().runTaskTimer(this, this::cacheEverything, 100L, 1L);
            // Stops the JVM at once: no exception, no crash report, nothing in the logs to point at us.
            case "halt" -> Runtime.getRuntime().halt(1);
            // Built against the 1.20.4 internals, like an outdated plugin: NoClassDefFoundError on any other version.
            case "nms-old" -> getLogger().info("Server internals: " + org.bukkit.craftbukkit.v1_20_R3.CraftServer.version());
            default -> {
                // "halt-random:60": dies on 60 % of the starts, like a crash that does not happen every time.
                if (mode.startsWith("halt-random:") && new java.util.Random().nextInt(100) < Integer.parseInt(mode.substring("halt-random:".length()))) {
                    Runtime.getRuntime().halt(1);
                }
                // "halt-with:OtherPlugin": only dies when another given plugin is installed (a conflict).
                if (mode.startsWith("halt-with:") && Bukkit.getPluginManager().getPlugin(mode.substring("halt-with:".length())) != null) {
                    Runtime.getRuntime().halt(1);
                }
                getLogger().info("Nothing to do");
            }
        }
    }

    private void brokenTask() {
        throw new IllegalStateException("Shop inventory is not loaded");
    }

    private double prices;

    private void recalculatePrices() {
        long end = System.nanoTime() + 70_000_000L;
        while (System.nanoTime() < end) {
            prices += Math.sqrt(prices + 1.0);
        }
    }

    private final Object shopLock = new Object();
    private final Object bankLock = new Object();

    private void lockInOrder() {
        Thread sync = new Thread(() -> {
            synchronized (bankLock) {
                pause(500);
                synchronized (shopLock) {
                    getLogger().info("bank synced");
                }
            }
        }, "Bank-Sync");
        sync.start();
        synchronized (shopLock) {
            pause(500);
            synchronized (bankLock) {
                getLogger().info("shop saved");
            }
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private final java.util.List<long[]> cache = new java.util.ArrayList<>();

    private void cacheEverything() {
        long[] block = new long[2 * 1024 * 1024];
        java.util.Arrays.fill(block, cache.size());
        cache.add(block);
    }

    private void blockMainThread() {
        try {
            Thread.sleep(120_000L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private String readMode() {
        try {
            return Files.readString(Path.of("fixture-mode.txt")).trim();
        } catch (Exception e) {
            return "none";
        }
    }
}
