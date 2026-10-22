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

    /** A warning triangle, for a meter at 0 (RF-UI-01: no color, an icon). */
    val Alert: ImageVector = stroke("Alert", width = 1.8f) {
        moveTo(12f, 4f)
        lineTo(21f, 20f)
        lineTo(3f, 20f)
        close()
        moveTo(12f, 10f)
        lineTo(12f, 14f)
        moveTo(12f, 17f)
        lineTo(12f, 17.01f)
    }

    // The debilities (IRON-68), one simple pen drawing each.

    /** Wounded: a bandage cross. */
    val Wounded: ImageVector = stroke("Wounded") {
        moveTo(12f, 5f)
        lineTo(12f, 19f)
        moveTo(5f, 12f)
        lineTo(19f, 12f)
    }

    /** Shaken: a trembling line. */
    val Shaken: ImageVector = stroke("Shaken") {
        moveTo(3f, 12f)
        quadTo(6f, 6f, 9f, 12f)
        quadTo(12f, 18f, 15f, 12f)
        quadTo(18f, 6f, 21f, 12f)
    }

    /** Unprepared: an empty bowl. */
    val Unprepared: ImageVector = stroke("Unprepared") {
        moveTo(4f, 11f)
        lineTo(20f, 11f)
        moveTo(5f, 11f)
        quadTo(12f, 23f, 19f, 11f)
    }

    /** Encumbered: a heavy weight. */
    val Encumbered: ImageVector = stroke("Encumbered") {
        moveTo(9f, 8f)
        lineTo(5f, 20f)
        lineTo(19f, 20f)
        lineTo(15f, 8f)
        close()
        moveTo(10f, 8f)
        quadTo(12f, 3f, 14f, 8f)
    }

    /** Maimed: a broken line. */
    val Maimed: ImageVector = stroke("Maimed") {
        moveTo(4f, 17f)
        lineTo(10f, 13f)
        lineTo(9f, 11f)
        moveTo(14f, 13f)
        lineTo(15f, 11f)
        lineTo(20f, 7f)
    }

    /** Corrupted: a dark drop. */
    val Corrupted: ImageVector = stroke("Corrupted") {
        moveTo(12f, 3f)
        quadTo(20f, 13f, 12f, 21f)
        quadTo(4f, 13f, 12f, 3f)
        moveTo(12f, 10f)
        lineTo(12f, 16f)
    }

    /** Cursed: a watching eye. */
    val Cursed: ImageVector = stroke("Cursed") {
        moveTo(3f, 12f)
        quadTo(12f, 3f, 21f, 12f)
        quadTo(12f, 21f, 3f, 12f)
        moveTo(12f, 10f)
        lineTo(12f, 14f)
    }

    /** Tormented: a jagged bolt. */
    val Tormented: ImageVector = stroke("Tormented") {
        moveTo(13f, 3f)
        lineTo(7f, 13f)
        lineTo(12f, 13f)
        lineTo(10f, 21f)
        lineTo(17f, 10f)
        lineTo(12f, 10f)
        close()
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
