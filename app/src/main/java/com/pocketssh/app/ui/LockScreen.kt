package com.pocketssh.app.ui

import android.text.format.DateFormat
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.pocketssh.app.MainViewModel
import com.pocketssh.app.R
import com.pocketssh.app.data.LockAppearance
import com.pocketssh.app.ui.lock.LockBackground
import com.pocketssh.app.ui.lock.LockEase
import com.pocketssh.app.ui.lock.LockPad
import com.pocketssh.app.ui.lock.PinDots
import com.pocketssh.app.ui.lock.accentColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/** Cap enforced both here and by the PIN field in settings. */
private const val MaxPinDigits = 9

/** Slots drawn when the stored PIN length is unknown (a PIN saved before it was recorded). */
private const val FallbackDotSlots = 4

@Composable
fun LockScreen(viewModel: MainViewModel) {
    val appearance by viewModel.lockAppearance.collectAsState()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val activity = context as? FragmentActivity

    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var success by remember { mutableStateOf(false) }
    var errorPulse by remember { mutableIntStateOf(0) }
    val pinLength = remember { viewModel.pinLength() }

    val biometricAvailable = remember {
        activity != null &&
            BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG or BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    // stringResource() only works inside composition; the biometric prompt fires later from a
    // callback, so its copy is captured here rather than looked up when tryBiometric() runs.
    val biometricTitle = stringResource(R.string.biometric_prompt_title)
    val biometricNegative = stringResource(R.string.biometric_negative_button)

    val appear = remember { Animatable(0f) }
    val shake = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) }

    fun tap() {
        if (appearance.haptics) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun succeed() {
        success = true
        if (appearance.haptics) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun tryBiometric() {
        val host = activity ?: return
        val prompt = BiometricPrompt(
            host,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    succeed()
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

    // Linear on purpose: LockPad applies its own easing per key, and easing twice turns the
    // stagger into a clump.
    LaunchedEffect(Unit) {
        appear.animateTo(1f, tween(820, easing = LinearEasing))
    }
    LaunchedEffect(Unit) { if (biometricAvailable) tryBiometric() }

    // The whole header translates; nothing is re-laid-out, so the shake cannot leave the row
    // resting anywhere but its original position.
    LaunchedEffect(errorPulse) {
        if (errorPulse == 0) return@LaunchedEffect
        shake.snapTo(0f)
        shake.animateTo(
            targetValue = 0f,
            animationSpec = keyframes {
                durationMillis = 440
                0f at 0
                -15f at 55
                13f at 110
                -9f at 175
                6f at 240
                -3f at 310
                0f at 440
            },
        )
    }

    LaunchedEffect(error) {
        if (error) {
            delay(650)
            pin = ""
            error = false
        }
    }

    LaunchedEffect(success) {
        if (success) {
            // Let the dots finish filling and the pad settle before the screen hands over — an
            // instant swap makes a correct PIN feel identical to a rejected one.
            exit.animateTo(1f, tween(320, easing = LockEase))
            viewModel.commitUnlock()
        }
    }

    fun submit(candidate: String) {
        if (candidate.isEmpty() || success) return
        if (viewModel.verifyPin(candidate)) {
            succeed()
        } else {
            error = true
            errorPulse++
            if (appearance.haptics) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    fun onDigit(digit: Int) {
        if (success) return
        tap()
        val base = if (error) "" else pin
        if (base.length >= MaxPinDigits) return
        val next = base + digit
        pin = next
        error = false
        if (appearance.autoSubmit && pinLength > 0 && next.length == pinLength) submit(next)
    }

    val slots = if (pinLength > 0) pinLength else maxOf(pin.length, FallbackDotSlots)
    val accent = appearance.accentColor

    Box(Modifier.fillMaxSize()) {
        LockBackground(
            style = appearance.background,
            accent = accent,
            base = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize(),
        )
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .graphicsLayer {
                    alpha = 1f - exit.value
                    val zoom = 1f + 0.07f * exit.value
                    scaleX = zoom
                    scaleY = zoom
                },
        ) {
            // 3 keys plus 2 gaps of 0.3 key each: the pad is exactly 3.6 keys wide, so this is
            // the largest key that still fits without the row being clipped on a narrow phone.
            val keySize = minOf(appearance.keySizeDp.dp, maxWidth / 3.6f)
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Column(
                    Modifier.graphicsLayer { translationX = shake.value },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (appearance.showClock) {
                        Clock(appear.value)
                        Spacer(Modifier.height(20.dp))
                    }
                    Greeting(appearance, appear.value)
                    Spacer(Modifier.height(18.dp))
                    Box(
                        Modifier.fillMaxWidth().height(22.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        PinDots(length = pin.length, slots = slots, accent = accent, error = error)
                    }
                    Spacer(Modifier.height(10.dp))
                    StatusLine(appearance, pin.isEmpty(), error)
                }
                Spacer(Modifier.height(30.dp))
                LockPad(
                    appearance = appearance,
                    canBackspace = pin.isNotEmpty(),
                    canConfirm = pin.isNotEmpty(),
                    onDigit = ::onDigit,
                    onBackspace = {
                        tap()
                        pin = if (error) "" else pin.dropLast(1)
                        error = false
                    },
                    onConfirm = { submit(pin) },
                    appear = appear.value,
                    keySize = keySize,
                    enabled = !success,
                )
                // Reserved whether or not the button is there, so enrolling a fingerprint later
                // does not shift the pad up the screen.
                Box(Modifier.height(52.dp), contentAlignment = Alignment.Center) {
                    if (biometricAvailable) {
                        TextButton(
                            onClick = { tryBiometric() },
                            modifier = Modifier.graphicsLayer { alpha = LockEase.transform(((appear.value - 0.55f) / 0.45f).coerceIn(0f, 1f)) },
                        ) {
                            Icon(Icons.Default.Fingerprint, null, tint = accent, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.action_use_biometrics), color = accent)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Clock(appear: Float) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            // Re-align to the top of the next minute instead of polling on a fixed period, so a
            // displayed minute is never more than a second stale.
            delay(60_000 - (System.currentTimeMillis() % 60_000))
        }
    }
    val timeFormat = remember { DateFormat.getTimeFormat(context) }
    val dateFormat = remember { SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()) }
    val date = Date(now)
    val progress = LockEase.transform((appear / 0.5f).coerceIn(0f, 1f))
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * -24.dp.toPx()
        },
    ) {
        Text(
            timeFormat.format(date),
            fontSize = 58.sp,
            fontWeight = FontWeight.Light,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            dateFormat.format(date).replaceFirstChar { it.titlecase(Locale.getDefault()) },
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Greeting(appearance: LockAppearance, appear: Float) {
    val progress = LockEase.transform(((appear - 0.1f) / 0.5f).coerceIn(0f, 1f))
    Text(
        text = appearance.greeting.ifBlank { stringResource(R.string.lock_title) },
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = progress
                translationY = (1f - progress) * 16.dp.toPx()
            },
    )
}

/**
 * The line under the dots. Fixed height so the hint appearing and the error replacing it never
 * move the keypad.
 */
@Composable
private fun StatusLine(appearance: LockAppearance, empty: Boolean, error: Boolean) {
    Box(Modifier.height(20.dp), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = error,
            enter = fadeIn(tween(120)) + slideInVertically { it / 2 },
            exit = fadeOut(tween(120)) + slideOutVertically { it / 2 },
        ) {
            Text(
                stringResource(R.string.lock_wrong_pin),
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp,
            )
        }
        AnimatedVisibility(
            visible = !error && empty && appearance.showHint,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(120)),
        ) {
            Text(
                stringResource(R.string.lock_enter_pin_hint),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
        }
    }
}
