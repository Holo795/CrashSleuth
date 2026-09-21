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
            default -> getLogger().info("Nothing to do");
        }
    }

    private void brokenTask() {
        throw new IllegalStateException("Shop inventory is not loaded");
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
