package app.pendant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val WORDMARK = "independant"
private const val HIGHLIGHT = "pendant"

/**
 * App wordmark: the project name "independant" in serif lettering with the
 * "pendant" it contains picked out in the accent color — the name is the
 * product: an independent pendant that listens all day. A small drawn glyph
 * (bail ring + hanging stone) sits beside it.
 */
@Composable
fun PendantWordmark(modifier: Modifier = Modifier) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PendantGlyph(Modifier.size(22.dp))
        val highlightStart = WORDMARK.indexOf(HIGHLIGHT)
        Text(
            buildAnnotatedString {
                append(WORDMARK)
                addStyle(
                    SpanStyle(
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    ),
                    highlightStart,
                    highlightStart + HIGHLIGHT.length,
                )
            },
            style = MaterialTheme.typography.titleLarge.copy(
                fontFamily = FontFamily.Serif,
                fontSize = 23.sp,
                letterSpacing = 0.5.sp,
            ),
        )
    }
}

@Composable
private fun PendantGlyph(modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        val h = size.height
        val cx = size.width / 2f
        // Bail: the small ring the chain would run through.
        val bailCenter = Offset(cx, h * 0.15f)
        val bailRadius = h * 0.10f
        drawCircle(
            color = ink,
            radius = bailRadius,
            center = bailCenter,
            style = Stroke(width = h * 0.06f),
        )
        // Connector between bail and stone.
        drawLine(
            color = ink,
            start = Offset(cx, bailCenter.y + bailRadius),
            end = Offset(cx, h * 0.34f),
            strokeWidth = h * 0.055f,
            cap = StrokeCap.Round,
        )
        // Stone: a kite-shaped gem hanging from the bail.
        val stone = Path().apply {
            moveTo(cx, h * 0.32f)
            lineTo(size.width * 0.82f, h * 0.58f)
            lineTo(cx, h * 0.94f)
            lineTo(size.width * 0.18f, h * 0.58f)
            close()
        }
        drawPath(stone, accent)
    }
}
