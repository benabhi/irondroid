package com.irondroid.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Thin stroke icons, as if drawn with a pen (RF-UI-02). Drawn in black; `Icon` tints them to the
 * theme's ink.
 */
object InkIcons {
    val Back: ImageVector = stroke("Back") {
        moveTo(19f, 12f)
        lineTo(5f, 12f)
        moveTo(11f, 6f)
        lineTo(5f, 12f)
        lineTo(11f, 18f)
    }

    val Send: ImageVector = stroke("Send") {
        moveTo(4f, 12f)
        lineTo(20f, 12f)
        moveTo(14f, 6f)
        lineTo(20f, 12f)
        lineTo(14f, 18f)
    }

    /** Three dots, drawn as very short round strokes. */
    val More: ImageVector = stroke("More", width = 2.6f) {
        moveTo(12f, 5f)
        lineTo(12f, 5.01f)
        moveTo(12f, 12f)
        lineTo(12f, 12.01f)
        moveTo(12f, 19f)
        lineTo(12f, 19.01f)
    }

    private fun stroke(name: String, width: Float = 1.5f, draw: PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = draw,
            )
            .build()
}
