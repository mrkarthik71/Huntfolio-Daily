package app.assistant.ui.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The Apex stroke icons (24-unit grid, 2-unit round strokes), drawn for the Instrument panel look
 * instead of stock Material glyphs. Tint them like any icon; the stroke colour is replaced by the tint.
 */
object ApexIcons {
    private fun stroke(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, VIEWPORT, VIEWPORT)
        paths.forEach { d ->
            b.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    /** Today: a clock face. */
    val Today: ImageVector by lazy { stroke("today", "M12 4a8 8 0 1 0 0 16a8 8 0 1 0 0-16Z", "M12 8v4l3 2") }

    /** Plan: a rising line toward goals. */
    val Plan: ImageVector by lazy { stroke("plan", "M4 18 9 12l4 3 7-9", "M16 6h4v4") }

    /** Money: a rupee mark. */
    val Money: ImageVector by lazy { stroke("money", "M7 5h10", "M7 9h10", "M10 5c3 0 5 1.6 5 4s-2 4-5 4H8l7 7") }

    /** Inbox: a tray. */
    val Inbox: ImageVector by lazy { stroke("inbox", "M4 13h4l2 3h4l2-3h4", "M5 5h14l1 8v6H4v-6Z") }

    /** Ask: a microphone. */
    val Mic: ImageVector by lazy { stroke("mic", "M12 3a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3Z", "M5 10a7 7 0 0 0 14 0", "M12 17v4") }

    /** Evening review: a crescent. */
    val Review: ImageVector by lazy { stroke("review", "M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5Z") }

    /** Settings: three sliders. */
    val Settings: ImageVector by lazy {
        stroke("settings", "M4 6h9", "M17 6h3", "M15 4v4", "M4 12h3", "M11 12h9", "M9 10v4", "M4 18h11", "M19 18h1", "M17 16v4")
    }

    private const val VIEWPORT = 24f
    private const val STROKE = 2f
}
