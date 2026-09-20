package com.pocketssh.app.ui.lock

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.pocketssh.app.data.LockBackgroundStyle
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * The animated backdrop behind the keypad.
 *
 * Every style is one continuously looping 0..1 phase driven by a single infinite transition, and
 * everything else is derived from it arithmetically — no per-frame allocation of animation state,
 * and no second clock that could drift out of step with the first.
 */
@Composable
fun LockBackground(
    style: LockBackgroundStyle,
    accent: Color,
    base: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier.background(base)) {
        when (style) {
            LockBackgroundStyle.Aurora -> Aurora(accent)
            LockBackgroundStyle.Waves -> Waves(accent)
            LockBackgroundStyle.Grid -> Grid(accent)
            LockBackgroundStyle.Rain -> Rain(accent)
            LockBackgroundStyle.Solid -> Unit
        }
    }
}

@Composable
private fun phase(durationMs: Int): Float {
    val transition = rememberInfiniteTransition(label = "lock-bg")
    val value by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMs, easing = LinearEasing), RepeatMode.Restart),
        label = "lock-bg-phase",
    )
    return value
}

/** Three slow radial blobs on Lissajous paths — they never repeat the same arrangement twice. */
@Composable
private fun Aurora(accent: Color) {
    val t = phase(24_000)
    val secondary = lerp(accent, Color(0xFF93B7FF), 0.55f)
    val tertiary = lerp(accent, Color(0xFFB18CFF), 0.7f)
    Canvas(Modifier.fillMaxSize()) {
        val angle = t * 2f * PI.toFloat()
        blob(accent, 0.30f + 0.18f * sin(angle), 0.22f + 0.10f * sin(angle * 1.3f + 1f), 0.75f)
        blob(secondary, 0.72f + 0.16f * sin(angle * 0.8f + 2f), 0.34f + 0.12f * sin(angle * 1.1f), 0.65f)
        blob(tertiary, 0.50f + 0.24f * sin(angle * 0.6f + 4f), 0.78f + 0.10f * sin(angle * 0.9f + 3f), 0.85f)
    }
}

private fun DrawScope.blob(color: Color, fx: Float, fy: Float, radiusFactor: Float) {
    val radius = size.minDimension * radiusFactor
    val center = Offset(size.width * fx, size.height * fy)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.26f), color.copy(alpha = 0.10f), Color.Transparent),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

/** Stacked sine curves travelling sideways, brightest at the bottom of the screen. */
@Composable
private fun Waves(accent: Color) {
    val t = phase(14_000)
    Canvas(Modifier.fillMaxSize()) {
        val lines = 9
        for (i in 0 until lines) {
            val depth = i / (lines - 1f)
            val baseY = size.height * (0.45f + depth * 0.55f)
            val amplitude = size.height * (0.02f + depth * 0.05f)
            val path = Path().apply {
                moveTo(0f, baseY)
                var x = 0f
                while (x <= size.width) {
                    val wave = sin((x / size.width) * 4f * PI.toFloat() + t * 2f * PI.toFloat() + i * 0.5f)
                    lineTo(x, baseY + wave * amplitude)
                    x += 8f
                }
            }
            drawPath(
                path = path,
                color = accent.copy(alpha = 0.06f + depth * 0.16f),
                style = Stroke(width = 1.5f.dp.toPx()),
            )
        }
    }
}

/** A static lattice with a soft band sweeping down it, like a terminal refresh. */
@Composable
private fun Grid(accent: Color) {
    val t = phase(6_000)
    Canvas(Modifier.fillMaxSize()) {
        val step = 34.dp.toPx()
        val line = accent.copy(alpha = 0.07f)
        var x = 0f
        while (x <= size.width) {
            drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            x += step
        }
        var y = 0f
        while (y <= size.height) {
            drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += step
        }
        val bandHeight = size.height * 0.28f
        // Start a full band above the top edge so the sweep enters and leaves off-screen instead
        // of popping into existence at y = 0.
        val bandTop = -bandHeight + t * (size.height + bandHeight)
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, accent.copy(alpha = 0.13f), Color.Transparent),
                startY = bandTop,
                endY = bandTop + bandHeight,
            ),
            topLeft = Offset(0f, bandTop),
            size = Size(size.width, bandHeight),
        )
    }
}

private const val RainColumns = 22

/**
 * Falling glyph streaks. Column offsets and speeds come from a seeded [Random] resolved once, so
 * the pattern looks irregular but is stable across recompositions — re-rolling it every frame
 * would make the columns jitter instead of fall.
 */
@Composable
private fun Rain(accent: Color) {
    val t = phase(9_000)
    val columns = remember {
        val random = Random(seed = 20250920)
        List(RainColumns) { Triple(random.nextFloat(), 0.6f + random.nextFloat() * 1.6f, 0.25f + random.nextFloat() * 0.4f) }
    }
    Canvas(Modifier.fillMaxSize()) {
        val columnWidth = size.width / RainColumns
        columns.forEachIndexed { index, (offset, speed, length) ->
            val streakHeight = size.height * length
            val travel = size.height + streakHeight
            val progress = ((t * speed + offset) % 1f)
            val top = -streakHeight + progress * travel
            val x = columnWidth * (index + 0.5f)
            drawLine(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, accent.copy(alpha = 0.22f), accent.copy(alpha = 0.75f)),
                    startY = top,
                    endY = top + streakHeight,
                ),
                start = Offset(x, top),
                end = Offset(x, top + streakHeight),
                strokeWidth = 2f.dp.toPx(),
            )
        }
    }
}
