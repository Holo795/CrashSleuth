package dev.holo795.crashsleuth.runner

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Keeps the windows of a test game out of the way: the game takes the focus when its window opens, at every
 * launch of a culprit search. Nothing on screen is needed, the verdict comes from the logs.
 */
interface WindowKeeper {
    /** Before the start: may change how the game is started (a virtual screen on Linux). */
    fun prepare(builder: ProcessBuilder): AutoCloseable = AutoCloseable {}

    /** Once started: may hide the windows as they appear (macOS, Windows). */
    fun watch(process: Process): AutoCloseable = AutoCloseable {}

    companion object {
        /** Leaves the windows alone, when asked to show them. */
        val NONE = object : WindowKeeper {}

        fun forThisSystem(show: Boolean = System.getenv("CRASHSLEUTH_SHOW_GAME") != null): WindowKeeper {
            if (show) return NONE
            val os = System.getProperty("os.name").lowercase()
            return when {
                os.startsWith("mac") -> MacWindowKeeper
                os.startsWith("windows") -> WindowsWindowKeeper
                else -> LinuxWindowKeeper
            }
        }
    }
}

/**
 * macOS: one small JavaScript for Automation script hides the game's application as soon as it shows up
 * (like Cmd-H) and gives the focus back to the application that had it, until the game exits. It only uses
 * AppKit's NSRunningApplication, which needs no permission.
 */
object MacWindowKeeper : WindowKeeper {
    private val SCRIPT = """
        ObjC.import('AppKit');
        function run(argv) {
            const pid = Number(argv[0]);
            const before = $.NSWorkspace.sharedWorkspace.frontmostApplication;
            let seen = false;
            for (;;) {
                const game = $.NSRunningApplication.runningApplicationWithProcessIdentifier(pid);
                if (game.isNil()) { if (seen) return; }
                else {
                    seen = true;
                    if (!game.hidden) game.hide;
                    if (game.active && !before.isNil()) before.activateWithOptions(2);
                }
                delay(0.15);
            }
        }
    """.trimIndent()

    override fun watch(process: Process): AutoCloseable = helper(listOf("osascript", "-l", "JavaScript", "-e", SCRIPT, process.pid().toString()))
}

/**
 * Windows: a PowerShell loop minimises every window of the game without activating it (SW_SHOWMINNOACTIVE)
 * and gives the foreground back to the window that had it, until the game exits.
 */
object WindowsWindowKeeper : WindowKeeper {
    private val SCRIPT = """
        param([int]${'$'}GamePid)
        Add-Type @'
        using System;
        using System.Runtime.InteropServices;
        public static class CrashSleuthWindows {
            public delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);
            [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc proc, IntPtr lParam);
            [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
            [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
            [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr hWnd);
            [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int command);
            [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
            [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
            public static void Keep(uint game, IntPtr before) {
                EnumWindows((hWnd, l) => {
                    uint owner; GetWindowThreadProcessId(hWnd, out owner);
                    if (owner == game && IsWindowVisible(hWnd) && !IsIconic(hWnd)) {
                        ShowWindow(hWnd, 7);
                        if (before != IntPtr.Zero) SetForegroundWindow(before);
                    }
                    return true;
                }, IntPtr.Zero);
            }
        }
        '@
        ${'$'}before = [CrashSleuthWindows]::GetForegroundWindow()
        while (Get-Process -Id ${'$'}GamePid -ErrorAction SilentlyContinue) {
            [CrashSleuthWindows]::Keep([uint32]${'$'}GamePid, ${'$'}before)
            Start-Sleep -Milliseconds 150
        }
    """.trimIndent()

    override fun watch(process: Process): AutoCloseable {
        val script = Files.createTempFile("crashsleuth-windows", ".ps1").also { Files.writeString(it, SCRIPT) }
        val running = helper(listOf("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.toString(), process.pid().toString()))
        return AutoCloseable { running.close(); Files.deleteIfExists(script) }
    }
}

/**
 * Linux: the game runs on a virtual screen (Xvfb) when it is installed, so no window appears at all; rendering
 * is done by Mesa in software, which is enough for a crash test. Without Xvfb the game uses the real screen.
 */
object LinuxWindowKeeper : WindowKeeper {
    override fun prepare(builder: ProcessBuilder): AutoCloseable {
        val xvfb = which("Xvfb") ?: return AutoCloseable {}
        val display = (99..199).firstOrNull { !Path.of("/tmp/.X11-unix/X$it").toFile().exists() && !Path.of("/tmp/.X$it-lock").toFile().exists() }
            ?: return AutoCloseable {}
        val server = runCatching {
            ProcessBuilder(xvfb.toString(), ":$display", "-screen", "0", "854x480x24", "-nolisten", "tcp")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        }.getOrNull() ?: return AutoCloseable {}
        // The virtual screen is ready once its socket exists.
        val socket = Path.of("/tmp/.X11-unix/X$display").toFile()
        repeat(50) { if (!socket.exists() && server.isAlive) Thread.sleep(100) }
        if (!server.isAlive) return AutoCloseable {}
        builder.environment()["DISPLAY"] = ":$display"
        builder.environment().remove("WAYLAND_DISPLAY")
        return AutoCloseable { server.destroy(); server.waitFor(5, TimeUnit.SECONDS) }
    }

    private fun which(name: String): Path? = System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator)
        .map { Path.of(it, name) }.firstOrNull { Files.isExecutable(it) }
}

/** A helper process that lives as long as the watch; it never fails the launch. */
private fun helper(command: List<String>): AutoCloseable {
    val process = runCatching {
        ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    }.getOrNull()
    return AutoCloseable { process?.destroy() }
}
