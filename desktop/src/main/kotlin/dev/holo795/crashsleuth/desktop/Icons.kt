package dev.holo795.crashsleuth.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Line icons drawn for CrashSleuth on a 24 grid, stroked at 1.8 so they sit well next to text. */
object Icons {
    private fun icon(name: String, vararg paths: String, fill: Boolean = false): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { data ->
                addPath(
                    pathData = PathParser().parsePathString(data).toNodes(),
                    fill = if (fill) SolidColor(Color.Black) else null,
                    stroke = if (fill) null else SolidColor(Color.Black),
                    strokeLineWidth = 1.8f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    /** The logo: a magnifying glass over a broken line. */
    val Logo = icon("logo", "M10.5 3.5a7 7 0 1 1 0 14a7 7 0 1 1 0-14z", "M15.6 15.6L20.5 20.5", "M6.5 10.5h2l1.2-2.2l1.6 4.4l1.2-2.2h1.5")
    val Folder = icon("folder", "M3.5 7.5a2 2 0 0 1 2-2h4l2 2h7a2 2 0 0 1 2 2v7.5a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z")
    val File = icon("file", "M7 3.5h6.5l4.5 4.5v11a1.5 1.5 0 0 1-1.5 1.5h-9.5a1.5 1.5 0 0 1-1.5-1.5v-14a1.5 1.5 0 0 1 1.5-1.5z", "M13.5 3.5v4.5h4.5", "M9 13h6", "M9 16.5h4")
    val Package = icon("package", "M12 3l8 4.5v9l-8 4.5l-8-4.5v-9z", "M4 7.5l8 4.5l8-4.5", "M12 12v9")
    val Server = icon("server", "M4.5 4.5h15v6h-15z", "M4.5 13.5h15v6h-15z", "M8 7.5h.01", "M8 16.5h.01")
    val Game = icon("game", "M7 8h10a4 4 0 0 1 4 4v1.5a3 3 0 0 1-5.4 1.8l-.9-1.3h-5.4l-.9 1.3a3 3 0 0 1-5.4-1.8v-1.5a4 4 0 0 1 4-4z", "M8 11v3", "M6.5 12.5h3", "M15.5 12h.01", "M17.5 13.5h.01")
    val Back = icon("back", "M14.5 5.5l-6.5 6.5l6.5 6.5")
    val Chevron = icon("chevron", "M9.5 6l6 6l-6 6")
    val Check = icon("check", "M5 12.5l4.5 4.5l9.5-10")
    val Alert = icon("alert", "M12 4l9 16h-18z", "M12 10v4", "M12 17h.01")
    val Cross = icon("cross", "M6.5 6.5l11 11", "M17.5 6.5l-11 11")
    val Info = icon("info", "M12 3.5a8.5 8.5 0 1 1 0 17a8.5 8.5 0 1 1 0-17z", "M12 11v5", "M12 8h.01")
    val Clock = icon("clock", "M12 3.5a8.5 8.5 0 1 1 0 17a8.5 8.5 0 1 1 0-17z", "M12 7.5v4.5l3 2")
    val Play = icon("play", "M8 5.5l11 6.5l-11 6.5z")
    val Stop = icon("stop", "M7 7h10v10h-10z")
    val Copy = icon("copy", "M9 9h10v10h-10z", "M5 15v-10h10")
    val Search = icon("search", "M10.5 4.5a6 6 0 1 1 0 12a6 6 0 1 1 0-12z", "M15 15l4.5 4.5")
    val Bolt = icon("bolt", "M13 3l-8 11h6l-1 7l8-11h-6z")
    val Shield = icon("shield", "M12 3.5l7 3v5.5c0 4.2-3 7.3-7 8.5c-4-1.2-7-4.3-7-8.5v-5.5z", "M9 12l2.2 2.2l3.8-4")
    val Layers = icon("layers", "M12 4l8.5 4.5l-8.5 4.5l-8.5-4.5z", "M3.5 13l8.5 4.5l8.5-4.5")
    val Share = icon("share", "M12 4v11", "M8 7.5l4-3.5l4 3.5", "M5 13v5.5h14v-5.5")
    val Globe = icon("globe", "M12 3.5a8.5 8.5 0 1 1 0 17a8.5 8.5 0 1 1 0-17z", "M3.5 12h17", "M12 3.5c2.4 2.4 3.5 5.2 3.5 8.5s-1.1 6.1-3.5 8.5c-2.4-2.4-3.5-5.2-3.5-8.5s1.1-6.1 3.5-8.5z")
    val Sparkle = icon("sparkle", "M12 4l1.8 5.2l5.2 1.8l-5.2 1.8l-1.8 5.2l-1.8-5.2l-5.2-1.8l5.2-1.8z")
    val Dot = icon("dot", "M12 8a4 4 0 1 1 0 8a4 4 0 1 1 0-8z", fill = true)
}
