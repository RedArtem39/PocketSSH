package com.pocketssh.app.ui

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.pocketssh.app.MainViewModel
import com.pocketssh.app.R
import kotlinx.coroutines.delay

@Composable
fun LockScreen(viewModel: MainViewModel) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val biometricAvailable = remember {
        activity != null &&
            BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG or BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    // stringResource() only works inside composition; the biometric prompt fires later from a
    // callback, so its copy is captured here rather than looked up when tryBiometric() runs.
    val biometricTitle = stringResource(R.string.biometric_prompt_title)
    val biometricNegative = stringResource(R.string.biometric_negative_button)

    fun tryBiometric() {
        val host = activity ?: return
        val prompt = BiometricPrompt(
            host,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    viewModel.unlockWithBiometric()
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(biometricTitle)
                .setAllowedAuthenticators(BIOMETRIC_STRONG or BIOMETRIC_WEAK)
                .setNegativeButtonText(biometricNegative)
                .build(),
        )
    }

    LaunchedEffect(Unit) { if (biometricAvailable) tryBiometric() }

    // No position math at all on purpose: an offset-based shake here fought with the Crossfade
    // and font metrics enough to visibly drift sideways. A color flash can't drift — there's
    // nothing to accumulate.
    LaunchedEffect(error) {
        if (error) {
            delay(500)
            pin = ""
            error = false
        }
    }

    fun submit() {
        if (!viewModel.unlock(pin)) {
            error = true
        }
    }

    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.titleMedium)
                // Fixed width here too (matching PinDots' own reservation) so the empty <-> typing
                // states never change the Crossfade container's size — that's what was causing
                // "Enter your PIN" to visibly jump sideways when the last digit was erased.
                Box(
                    Modifier.height(20.dp).width(DotStride * MaxPinDigits - DotGap),
                    contentAlignment = Alignment.Center,
                ) {
                    Crossfade(targetState = pin.isEmpty(), label = "pin-hint") { empty ->
                        if (empty) {
                            Text(
                                stringResource(R.string.lock_enter_pin_hint),
                                Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                            )
                        } else {
                            PinDots(pin.length, error)
                        }
                    }
                }
                if (error) Text(stringResource(R.string.lock_wrong_pin), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
            PinPad(
                canConfirm = pin.isNotEmpty(),
                canBackspace = pin.isNotEmpty(),
                onDigit = { digit ->
                    val base = if (error) "" else pin
                    if (base.length < 9) pin = base + digit
                    error = false
                },
                onBackspace = {
                    pin = if (error) "" else pin.dropLast(1)
                    error = false
                },
                onConfirm = { submit() },
            )
            if (biometricAvailable) {
                TextButton(onClick = { tryBiometric() }) {
                    Icon(Icons.Default.Fingerprint, null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_use_biometrics))
                }
            }
        }
    }
}

// Has to match the real max (see the `.take(9)`/`length < 9` caps below) — reserving less means
// a longer PIN overflows the centered box and the whole row reads as off-center instead.
private const val MaxPinDigits = 9
private val DotSize = 12.dp
private val DotGap = 10.dp
private val DotStride = DotSize + DotGap

@Composable
private fun PinDots(length: Int, error: Boolean, modifier: Modifier = Modifier) {
    val dotColor by animateColorAsState(
        targetValue = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        animationSpec = tween(150),
        label = "pin-dot-color",
    )
    // Reserve room for the maximum possible PIN up front and place each dot at a fixed slot by
    // index, instead of a Row that re-centers (and so shifts every existing dot) each time the
    // count changes. Existing dots then never move — new ones just appear to the right.
    Box(modifier.width(DotStride * MaxPinDigits - DotGap).height(DotSize)) {
        for (i in 0 until length) {
            Box(
                Modifier
                    .offset(x = DotStride * i)
                    .size(DotSize)
                    .background(dotColor, CircleShape),
            )
        }
    }
}

@Composable
private fun PinPad(
    canConfirm: Boolean,
    canBackspace: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        for (row in listOf("123", "456", "789")) {
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                for (digit in row) DigitButton(digit) { onDigit(digit) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            ActionButton(Icons.AutoMirrored.Filled.Backspace, enabled = canBackspace, onClick = onBackspace)
            DigitButton('0') { onDigit('0') }
            ActionButton(Icons.Default.Check, enabled = canConfirm, onClick = onConfirm, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun PadPressScale(): Pair<MutableInteractionSource, Modifier> {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh),
        label = "pad-press-scale",
    )
    return interactionSource to Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
}

@Composable
private fun DigitButton(digit: Char, onClick: () -> Unit) {
    val (interactionSource, scaleModifier) = PadPressScale()
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.size(72.dp).then(scaleModifier),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(digit.toString(), fontSize = 26.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val (interactionSource, scaleModifier) = PadPressScale()
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        enabled = enabled,
        shape = CircleShape,
        color = Color.Transparent,
        modifier = Modifier.size(72.dp).then(scaleModifier),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (enabled) tint else tint.copy(alpha = 0.3f))
        }
    }
}
