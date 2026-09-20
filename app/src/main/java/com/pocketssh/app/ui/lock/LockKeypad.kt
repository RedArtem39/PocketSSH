package com.pocketssh.app.ui.lock

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketssh.app.data.LockAppearance
import com.pocketssh.app.data.LockKeyStyle

/** Strong ease-out, matching the screen transitions in PocketSshApp so the app has one motion feel. */
internal val LockEase = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

private const val KeyStaggerStep = 0.05f
private const val KeyStaggerSpan = 0.45f

/** Rows of digits followed by the action row; -1 marks backspace, -2 marks confirm. */
private val PadRows = listOf(
    listOf(1, 2, 3),
    listOf(4, 5, 6),
    listOf(7, 8, 9),
    listOf(-1, 0, -2),
)

@Composable
fun LockPad(
    appearance: LockAppearance,
    canBackspace: Boolean,
    canConfirm: Boolean,
    onDigit: (Int) -> Unit,
    onBackspace: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    /** 0..1 entrance progress; keys fade and rise in sequence as it advances. */
    appear: Float = 1f,
    keySize: Dp = appearance.keySizeDp.dp,
    enabled: Boolean = true,
) {
    val gap = keySize * 0.3f
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap * 0.65f)) {
        PadRows.forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEachIndexed { columnIndex, slot ->
                    val order = rowIndex * 3 + columnIndex
                    val progress = LockEase.transform(
                        ((appear - order * KeyStaggerStep) / KeyStaggerSpan).coerceIn(0f, 1f),
                    )
                    val entrance = Modifier.graphicsLayer {
                        alpha = progress
                        translationY = (1f - progress) * 26.dp.toPx()
                        scaleX = 0.8f + 0.2f * progress
                        scaleY = 0.8f + 0.2f * progress
                    }
                    when (slot) {
                        -1 -> LockKey(
                            appearance = appearance,
                            size = keySize,
                            modifier = entrance,
                            icon = Icons.AutoMirrored.Filled.Backspace,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            bare = true,
                            enabled = enabled && canBackspace,
                            onClick = onBackspace,
                        )
                        -2 -> LockKey(
                            appearance = appearance,
                            size = keySize,
                            modifier = entrance,
                            icon = appearance.confirmGlyph.vector(),
                            tint = appearance.confirmTint,
                            filled = true,
                            enabled = enabled && canConfirm,
                            onClick = onConfirm,
                        )
                        else -> LockKey(
                            appearance = appearance,
                            size = keySize,
                            modifier = entrance,
                            label = appearance.label(slot),
                            icon = appearance.glyph(slot).vector(),
                            enabled = enabled,
                            onClick = { onDigit(slot) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * One key. [bare] keys (backspace) never draw a container so the pad does not look lopsided, and
 * [filled] keys (confirm) always do even in the Ghost style — an invisible confirm button would
 * leave nothing to aim at.
 */
@Composable
fun LockKey(
    appearance: LockAppearance,
    size: Dp,
    modifier: Modifier = Modifier,
    label: String = "",
    icon: ImageVector? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    bare: Boolean = false,
    filled: Boolean = false,
    onClick: () -> Unit = {},
) {
    val accent = appearance.accentColor
    val shape = appearance.keyShape.shape()
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh),
        label = "lock-key-scale",
    )
    val press by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(if (pressed) 90 else 260),
        label = "lock-key-press",
    )

    // A halo that outlives the press: the pointer is usually gone within ~80ms, far shorter than
    // the ring looks good for, so it runs off a click counter rather than the pressed flag.
    var ripples by remember { mutableIntStateOf(0) }
    val ripple = remember { Animatable(1f) }
    LaunchedEffect(ripples) {
        if (ripples > 0) {
            ripple.snapTo(0f)
            ripple.animateTo(1f, tween(480, easing = LockEase))
        }
    }

    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val container = when {
        bare -> Color.Transparent
        filled -> tint.copy(alpha = 0.18f)
        else -> when (appearance.keyStyle) {
            LockKeyStyle.Glass -> surfaceVariant.copy(alpha = 0.34f)
            LockKeyStyle.Solid -> surfaceVariant.copy(alpha = 0.92f)
            LockKeyStyle.Outline, LockKeyStyle.Ghost -> Color.Transparent
        }
    }
    val borderColor = when {
        bare -> Color.Transparent
        filled -> tint.copy(alpha = 0.55f)
        else -> when (appearance.keyStyle) {
            LockKeyStyle.Glass -> Color.White.copy(alpha = 0.10f)
            LockKeyStyle.Outline -> accent.copy(alpha = 0.40f)
            LockKeyStyle.Solid, LockKeyStyle.Ghost -> Color.Transparent
        }
    }
    val dim = if (enabled) 1f else 0.28f
    val containerNow = lerp(container, accent.copy(alpha = 0.30f), press).let { it.copy(alpha = it.alpha * dim) }

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            if (ripples == 0) return@Canvas
            val radius = this.size.minDimension * (0.45f + 0.28f * ripple.value)
            drawCircle(
                color = accent.copy(alpha = 0.30f * (1f - ripple.value)),
                radius = radius,
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
        Surface(
            onClick = {
                ripples++
                onClick()
            },
            enabled = enabled,
            interactionSource = interactionSource,
            shape = shape,
            color = containerNow,
            modifier = Modifier
                .size(size)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .then(
                    if (borderColor.alpha == 0f) Modifier
                    else Modifier.border(1.dp, borderColor.copy(alpha = borderColor.alpha * dim), shape),
                ),
        ) {
            Box(Modifier.fillMaxSize().clip(shape), contentAlignment = Alignment.Center) {
                if (appearance.keyStyle == LockKeyStyle.Glass && !bare) {
                    // Light source from the top-left, the way a frosted panel catches it.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.07f), Color.Transparent))),
                    )
                }
                KeyContent(label, icon, tint.copy(alpha = dim), size)
            }
        }
    }
}

@Composable
private fun KeyContent(label: String, icon: ImageVector?, color: Color, size: Dp) {
    when {
        icon != null && label.isNotBlank() -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(size * 0.28f))
            Text(label, color = color, fontSize = (size.value * 0.19f).sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
        icon != null -> Icon(icon, null, tint = color, modifier = Modifier.size(size * 0.38f))
        label.isNotBlank() -> Text(
            text = label,
            color = color,
            // Multi-character labels get proportionally smaller so a 3-character one still fits.
            fontSize = (size.value * (if (label.length > 1) 0.24f else 0.36f)).sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

val DotSize = 13.dp
val DotGap = 12.dp

/**
 * PIN indicator. Slots are laid out at fixed offsets rather than in a Row that re-centres, so an
 * existing dot never shifts sideways when the count changes.
 */
@Composable
fun PinDots(
    length: Int,
    slots: Int,
    accent: Color,
    error: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = if (error) MaterialTheme.colorScheme.error else accent
    val stride = DotSize + DotGap
    // The slots are placed with offset(), which does not contribute to measurement, so without an
    // explicit width this Box collapses to a single dot and the row hangs off to one side of
    // wherever the parent centres it. Animated because the count grows on the fallback path.
    val width by animateDpAsState(pinDotsWidth(slots), tween(220, easing = LockEase), label = "pin-dots-width")
    Box(modifier.width(width).height(DotSize)) {
        for (i in 0 until maxOf(slots, 1)) {
            val fill by animateFloatAsState(
                targetValue = if (i < length) 1f else 0f,
                animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow),
                label = "pin-dot-fill",
            )
            Box(
                Modifier
                    .offset(x = stride * i)
                    .size(DotSize)
                    .graphicsLayer {
                        // fill*(1-fill)*4 peaks at 1 mid-flight and is 0 at both ends, so the dot
                        // overshoots on the way in and settles without a second spring to tune.
                        val pop = 1f + 0.22f * (fill * (1f - fill) * 4f)
                        scaleX = (0.5f + 0.5f * fill) * pop
                        scaleY = (0.5f + 0.5f * fill) * pop
                    }
                    .background(color.copy(alpha = fill), CircleShape)
                    .border(1.5.dp, color.copy(alpha = 0.35f * (1f - fill)), CircleShape),
            )
        }
    }
}

/** Width [PinDots] occupies for a given slot count — callers reserve it so nothing re-centres. */
fun pinDotsWidth(slots: Int): Dp = if (slots <= 0) 0.dp else (DotSize + DotGap) * slots - DotGap
