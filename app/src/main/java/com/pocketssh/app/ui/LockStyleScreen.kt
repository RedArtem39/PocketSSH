package com.pocketssh.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketssh.app.MainViewModel
import com.pocketssh.app.R
import com.pocketssh.app.data.LockAppearance
import com.pocketssh.app.data.LockBackgroundStyle
import com.pocketssh.app.data.LockGlyph
import com.pocketssh.app.data.LockKeyShape
import com.pocketssh.app.data.LockKeyStyle
import com.pocketssh.app.ui.lock.LockBackground
import com.pocketssh.app.ui.lock.LockKey
import com.pocketssh.app.ui.lock.LockPad
import com.pocketssh.app.ui.lock.PinDots
import com.pocketssh.app.ui.lock.accentColor
import com.pocketssh.app.ui.lock.confirmTint
import com.pocketssh.app.ui.lock.vector

/**
 * Lock screen customiser. Every control writes straight through to the view model, so the live
 * preview at the top and the real lock screen are rendered from the same single source of truth —
 * there is no draft copy that could drift from what actually gets saved.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LockStyleScreen(viewModel: MainViewModel, navigate: (String) -> Unit) {
    val appearance by viewModel.lockAppearance.collectAsState()
    val update: (LockAppearance) -> Unit = { viewModel.updateLockAppearance(it) }
    var editingDigit by remember { mutableStateOf<Int?>(null) }
    var editingConfirm by remember { mutableStateOf(false) }

    editingDigit?.let { digit ->
        KeyEditorDialog(
            title = stringResource(R.string.lockstyle_key_n, digit),
            label = appearance.label(digit),
            glyph = appearance.glyph(digit),
            onDismiss = { editingDigit = null },
            onApply = { label, glyph ->
                editingDigit = null
                update(appearance.withLabel(digit, label).withGlyph(digit, glyph))
            },
            onReset = {
                editingDigit = null
                update(appearance.withLabel(digit, digit.toString()).withGlyph(digit, LockGlyph.None))
            },
        )
    }

    if (editingConfirm) {
        ConfirmEditorDialog(
            appearance = appearance,
            onDismiss = { editingConfirm = false },
            onApply = { glyph, color ->
                editingConfirm = false
                update(appearance.copy(confirmGlyph = glyph, confirmColor = color))
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.lockstyle_title)) },
                navigationIcon = {
                    IconButton(onClick = { navigate("settings") }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = { update(LockAppearance()) }) {
                        Icon(Icons.Default.Restore, stringResource(R.string.lockstyle_reset))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Preview(appearance)

            Section(stringResource(R.string.lockstyle_accent)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LockAppearance.AccentPresets.forEach { value ->
                        Swatch(
                            color = Color(value.toInt()),
                            selected = appearance.accent == value,
                            onClick = { update(appearance.copy(accent = value)) },
                        )
                    }
                }
            }

            Section(stringResource(R.string.lockstyle_background)) {
                ChipRow(
                    options = LockBackgroundStyle.entries,
                    selected = appearance.background,
                    label = { backgroundName(it) },
                    onSelect = { update(appearance.copy(background = it)) },
                )
            }

            Section(stringResource(R.string.lockstyle_key_shape)) {
                ChipRow(
                    options = LockKeyShape.entries,
                    selected = appearance.keyShape,
                    label = { shapeName(it) },
                    onSelect = { update(appearance.copy(keyShape = it)) },
                )
            }

            Section(stringResource(R.string.lockstyle_key_style)) {
                ChipRow(
                    options = LockKeyStyle.entries,
                    selected = appearance.keyStyle,
                    label = { styleName(it) },
                    onSelect = { update(appearance.copy(keyStyle = it)) },
                )
            }

            Section(stringResource(R.string.lockstyle_key_size)) {
                Slider(
                    value = appearance.keySizeDp.toFloat(),
                    onValueChange = { update(appearance.copy(keySizeDp = it.toInt())) },
                    valueRange = LockAppearance.MinKeySize.toFloat()..LockAppearance.MaxKeySize.toFloat(),
                    steps = LockAppearance.MaxKeySize - LockAppearance.MinKeySize - 1,
                )
            }

            HorizontalDivider()

            Section(stringResource(R.string.lockstyle_keys)) {
                Text(
                    stringResource(R.string.lockstyle_keys_desc),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (digit in 0..9) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            LockKey(
                                appearance = appearance,
                                size = 56.dp,
                                label = appearance.label(digit),
                                icon = appearance.glyph(digit).vector(),
                                onClick = { editingDigit = digit },
                            )
                            Text(
                                digit.toString(),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LockKey(
                            appearance = appearance,
                            size = 56.dp,
                            icon = appearance.confirmGlyph.vector(),
                            tint = appearance.confirmTint,
                            filled = true,
                            onClick = { editingConfirm = true },
                        )
                        Text(
                            stringResource(R.string.lockstyle_confirm_key),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            HorizontalDivider()

            Section(stringResource(R.string.lockstyle_text)) {
                OutlinedTextField(
                    value = appearance.greeting,
                    onValueChange = { update(appearance.copy(greeting = it.take(48))) },
                    label = { Text(stringResource(R.string.lockstyle_greeting)) },
                    placeholder = { Text(stringResource(R.string.lock_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Section(stringResource(R.string.lockstyle_behaviour)) {
                ToggleRow(stringResource(R.string.lockstyle_show_clock), appearance.showClock) {
                    update(appearance.copy(showClock = it))
                }
                ToggleRow(stringResource(R.string.lockstyle_show_hint), appearance.showHint) {
                    update(appearance.copy(showHint = it))
                }
                ToggleRow(stringResource(R.string.lockstyle_haptics), appearance.haptics) {
                    update(appearance.copy(haptics = it))
                }
                ToggleRow(
                    stringResource(R.string.lockstyle_auto_submit),
                    appearance.autoSubmit,
                    stringResource(R.string.lockstyle_auto_submit_desc),
                ) { update(appearance.copy(autoSubmit = it)) }
            }
        }
    }
}

/** A non-functional miniature of the real screen: same background and key composables, half size. */
@Composable
private fun Preview(appearance: LockAppearance) {
    Card(
        Modifier.fillMaxWidth().height(310.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp))) {
            LockBackground(
                style = appearance.background,
                accent = appearance.accentColor,
                base = MaterialTheme.colorScheme.background,
                modifier = Modifier.fillMaxSize(),
            )
            Column(
                Modifier.fillMaxSize().padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    appearance.greeting.ifBlank { stringResource(R.string.lock_title) },
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(16.dp), contentAlignment = Alignment.Center) {
                    PinDots(length = 2, slots = 4, accent = appearance.accentColor, error = false)
                }
                Spacer(Modifier.height(14.dp))
                LockPad(
                    appearance = appearance,
                    canBackspace = true,
                    canConfirm = true,
                    onDigit = {},
                    onBackspace = {},
                    onConfirm = {},
                    // Scaled down relative to the chosen size so the slider is still visible here,
                    // and capped so the biggest keys do not overflow the card.
                    keySize = (appearance.keySizeDp * 0.62f).dp,
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
            )
        }
    }
}

@Composable
private fun Swatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.onBackground else Color.White.copy(alpha = 0.15f),
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(28.dp).background(color, CircleShape))
        if (selected) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.background, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (subtitle != null) {
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun KeyEditorDialog(
    title: String,
    label: String,
    glyph: LockGlyph,
    onDismiss: () -> Unit,
    onApply: (String, LockGlyph) -> Unit,
    onReset: () -> Unit,
) {
    var text by remember { mutableStateOf(label) }
    var picked by remember { mutableStateOf(glyph) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.lockstyle_key_editor_desc),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(LockAppearance.MaxLabelLength) },
                    label = { Text(stringResource(R.string.lockstyle_key_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                )
                GlyphPicker(picked) { picked = it }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(text, picked) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) { Text(stringResource(R.string.lockstyle_reset)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

@Composable
private fun ConfirmEditorDialog(
    appearance: LockAppearance,
    onDismiss: () -> Unit,
    onApply: (LockGlyph, Long) -> Unit,
) {
    var picked by remember { mutableStateOf(appearance.confirmGlyph) }
    var color by remember { mutableStateOf(appearance.confirmColor) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lockstyle_confirm_key)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GlyphPicker(picked, allowNone = false) { picked = it }
                Text(stringResource(R.string.lockstyle_confirm_color), fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 0 is the "follow the accent" sentinel, rendered with the accent itself so
                    // the swatch always shows the colour it will actually produce.
                    Swatch(appearance.accentColor, color == 0L) { color = 0L }
                    LockAppearance.AccentPresets.forEach { value ->
                        Swatch(Color(value.toInt()), color == value) { color = value }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(picked, color) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GlyphPicker(selected: LockGlyph, allowNone: Boolean = true, onSelect: (LockGlyph) -> Unit) {
    val options = if (allowNone) LockGlyph.entries else LockGlyph.entries.filter { it != LockGlyph.None }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            val chosen = option == selected
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .border(
                        width = if (chosen) 2.dp else 1.dp,
                        color = if (chosen) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.12f),
                        shape = CircleShape,
                    )
                    .clickable { onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                val icon = option.vector()
                if (icon == null) {
                    Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Icon(
                        icon,
                        null,
                        tint = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun backgroundName(style: LockBackgroundStyle): String = stringResource(
    when (style) {
        LockBackgroundStyle.Aurora -> R.string.lockstyle_bg_aurora
        LockBackgroundStyle.Waves -> R.string.lockstyle_bg_waves
        LockBackgroundStyle.Grid -> R.string.lockstyle_bg_grid
        LockBackgroundStyle.Rain -> R.string.lockstyle_bg_rain
        LockBackgroundStyle.Solid -> R.string.lockstyle_bg_solid
    },
)

@Composable
private fun shapeName(shape: LockKeyShape): String = stringResource(
    when (shape) {
        LockKeyShape.Circle -> R.string.lockstyle_shape_circle
        LockKeyShape.Squircle -> R.string.lockstyle_shape_squircle
        LockKeyShape.Rounded -> R.string.lockstyle_shape_rounded
    },
)

@Composable
private fun styleName(style: LockKeyStyle): String = stringResource(
    when (style) {
        LockKeyStyle.Glass -> R.string.lockstyle_style_glass
        LockKeyStyle.Solid -> R.string.lockstyle_style_solid
        LockKeyStyle.Outline -> R.string.lockstyle_style_outline
        LockKeyStyle.Ghost -> R.string.lockstyle_style_ghost
    },
)
