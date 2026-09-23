package dev.holo795.crashsleuth.desktop

import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import java.awt.Window

/**
 * Windows draws the title bar of every Java window in the light colours, whatever the system theme is,
 * until the window asks for the other one. One call does it; anything that goes wrong is left alone,
 * because a title bar is never worth a window that does not open.
 */
object WindowFrame {
    private const val USE_IMMERSIVE_DARK_MODE = 20

    private interface Dwmapi : com.sun.jna.win32.StdCallLibrary {
        fun DwmSetWindowAttribute(hwnd: WinDef.HWND, attribute: Int, value: com.sun.jna.Pointer, size: Int): WinNT.HRESULT
    }

    fun followTheme(window: Window, dark: Boolean) {
        if (!System.getProperty("os.name").lowercase().startsWith("windows")) return
        runCatching {
            val library = Native.load("dwmapi", Dwmapi::class.java)
            val handle = WinDef.HWND(Native.getWindowPointer(window))
            val value = com.sun.jna.Memory(4).apply { setInt(0, if (dark) 1 else 0) }
            library.DwmSetWindowAttribute(handle, USE_IMMERSIVE_DARK_MODE, value, 4)
        }
    }
}
