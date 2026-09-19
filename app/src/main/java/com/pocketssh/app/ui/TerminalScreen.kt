package com.pocketssh.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketssh.app.MainViewModel
import com.pocketssh.app.R
import com.pocketssh.app.ssh.ConnectionState
import com.pocketssh.app.ssh.SshSessionManager
import com.pocketssh.app.terminal.ImageFormat
import com.pocketssh.app.terminal.ImagePlacement
import com.pocketssh.app.terminal.TerminalCell
import com.pocketssh.app.terminal.TerminalImageData
import com.pocketssh.app.terminal.TerminalRow
import com.pocketssh.app.terminal.TerminalSnapshot
import java.nio.ByteBuffer
import kotlinx.coroutines.delay

private const val SENTINEL = "​"
private val TerminalBg = Color(0xFF070A0D)
private val TerminalFg = Color(0xFFD7E0E8)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(viewModel: MainViewModel, navigate: (String) -> Unit) {
    val state by viewModel.session.state.collectAsState()
    val screen by viewModel.session.screen.collectAsState()
    val hostKey by viewModel.session.hostKeyRequest.collectAsState()
    var rawMode by remember { mutableStateOf(false) }
    var command by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    val textMeasurer = rememberTextMeasurer()
    val monoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 17.sp)
    val charSize = remember { textMeasurer.measure(AnnotatedString("M"), monoStyle).size }
    LaunchedEffect(charSize) { viewModel.session.setCellPixelSize(charSize.width.toFloat(), charSize.height.toFloat()) }

    // A real terminal cursor blinks — it's a small, authentic touch that shows the session is
    // alive rather than a frozen screenshot. The screen itself is only ~40 rows, so redoing this
    // list twice a second is unnoticeable; it's the scrollback concatenation that used to be the
    // expensive part, and that isn't touched here.
    var cursorOn by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(530)
            cursorOn = !cursorOn
        }
    }

    // Scrollback is append-only and can hold up to 2000 lines, while the live screen is a
    // couple dozen rows that change on every keystroke of remote output. Keeping them as two
    // separate LazyColumn item ranges (instead of concatenating into one list every frame) means
    // scrollback's render items are only rebuilt when scrollback itself actually grows, not on
    // every screen update.
    val scrollbackItems = remember(screen.scrollback) {
        buildRenderItems(screen.scrollback, cursorAbsoluteRow = -1, cursorVisible = false, cursorCol = -1, images = screen.images)
    }
    val screenItems = remember(screen, cursorOn) {
        buildRenderItems(screen.screen, screen.cursorRow, screen.cursorVisible && cursorOn, screen.cursorCol, screen.images)
    }
    val totalItemCount = scrollbackItems.size + screenItems.size
    LaunchedEffect(totalItemCount) { if (totalItemCount > 0) listState.scrollToItem(totalItemCount - 1) }

    hostKey?.let { request -> HostKeyDialog(request, onAnswer = viewModel.session::answerHostKey) }

    Column(Modifier.fillMaxSize().background(TerminalBg).statusBarsPadding()) {
        TerminalTopBar(
            state = state,
            rawMode = rawMode,
            onToggleRaw = { rawMode = !rawMode },
            onCopy = { clipboard.setText(AnnotatedString(plainText(screen))) },
            onBack = { navigate("servers") },
            onDisconnect = { viewModel.session.disconnect() },
        )
        HorizontalDivider(color = Color.White.copy(alpha = .08f))
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .onSizeChanged { size ->
                    if (charSize.width > 0 && charSize.height > 0) {
                        val cols = (size.width / charSize.width).coerceAtLeast(10)
                        val rows = (size.height / charSize.height).coerceAtLeast(4)
                        viewModel.session.resize(cols, rows)
                    }
                },
        ) {
            items(scrollbackItems.size) { index -> RenderItemRow(scrollbackItems[index], monoStyle, charSize) }
            items(screenItems.size) { index -> RenderItemRow(screenItems[index], monoStyle, charSize) }
        }
        SpecialKeysRow(viewModel.session)
        if (rawMode) {
            RawInputBar(enabled = state is ConnectionState.Connected, onKey = viewModel.session::send)
        } else {
            LineInputBar(
                command = command,
                onCommandChange = { command = it },
                enabled = state is ConnectionState.Connected,
                onSend = { viewModel.session.send(command + "\n"); command = "" },
            )
        }
    }
}

@Composable
private fun TerminalTopBar(
    state: ConnectionState,
    rawMode: Boolean,
    onToggleRaw: () -> Unit,
    onCopy: () -> Unit,
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_servers)) }
        Column(Modifier.weight(1f)) {
            Text(
                when (state) {
                    is ConnectionState.Connected -> state.title
                    ConnectionState.Connecting -> stringResource(R.string.terminal_connecting)
                    else -> stringResource(R.string.terminal_title_default)
                },
                fontWeight = FontWeight.SemiBold,
            )
            Text(stateLabel(state), color = stateColor(state), fontSize = 11.sp)
        }
        IconButton(onClick = onToggleRaw) {
            Crossfade(targetState = rawMode, animationSpec = tween(150), label = "input-mode-icon") { raw ->
                if (raw) {
                    Icon(Icons.Default.Terminal, stringResource(R.string.cd_switch_to_line_input))
                } else {
                    Icon(Icons.Default.Keyboard, stringResource(R.string.cd_switch_to_raw_input))
                }
            }
        }
        IconButton(onClick = onCopy) { Icon(Icons.Default.ContentCopy, stringResource(R.string.cd_copy_output)) }
        if (state !is ConnectionState.Disconnected) {
            TextButton(onClick = onDisconnect) { Text(stringResource(R.string.action_disconnect), color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun SpecialKeysRow(session: SshSessionManager) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TerminalKey(stringResource(R.string.key_esc)) { session.send("\u001B") }
        TerminalKey(stringResource(R.string.key_tab)) { session.send("\t") }
        TerminalKey("↑") { session.send("\u001B[A") }
        TerminalKey("↓") { session.send("\u001B[B") }
        TerminalKey("←") { session.send("\u001B[D") }
        TerminalKey("→") { session.send("\u001B[C") }
        TerminalKey(stringResource(R.string.key_ctrl_c)) { session.send("\u0003") }
        TerminalKey(stringResource(R.string.key_ctrl_d)) { session.send("\u0004") }
    }
}

@Composable
private fun TerminalKey(label: String, action: () -> Unit) {
    Surface(onClick = action, shape = RoundedCornerShape(7.dp), color = Color(0xFF19212A)) {
        Text(label, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

@Composable
private fun LineInputBar(command: String, onCommandChange: (String) -> Unit, enabled: Boolean, onSend: () -> Unit) {
    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = command,
            onValueChange = onCommandChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.terminal_command_placeholder)) },
            singleLine = true,
            enabled = enabled,
        )
        Spacer(Modifier.width(8.dp))
        FilledIconButton(
            onClick = onSend,
            enabled = enabled && command.isNotEmpty(),
            modifier = Modifier.size(52.dp),
        ) { Icon(Icons.Default.Send, stringResource(R.string.cd_send)) }
    }
}

@Composable
private fun RawInputBar(enabled: Boolean, onKey: (String) -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(SENTINEL, TextRange(1))) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(enabled) { if (enabled) runCatching { focusRequester.requestFocus() } }
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp).clickable { focusRequester.requestFocus() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Keyboard, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.terminal_raw_mode_hint),
            Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
        BasicTextField(
            value = field,
            onValueChange = { new ->
                val oldLen = field.text.length
                val newText = new.text
                when {
                    newText.length > oldLen -> onKey(newText.replaceFirst(SENTINEL, ""))
                    newText.length < oldLen -> onKey("")
                }
                field = TextFieldValue(SENTINEL, TextRange(1))
            },
            modifier = Modifier.size(24.dp).focusRequester(focusRequester),
            enabled = enabled,
            textStyle = TextStyle(color = Color.Transparent),
            cursorBrush = SolidColor(Color.Transparent),
        )
    }
}

@Composable
private fun stateLabel(state: ConnectionState) = when (state) {
    ConnectionState.Disconnected -> stringResource(R.string.terminal_state_disconnected)
    ConnectionState.Connecting -> stringResource(R.string.terminal_state_negotiating)
    is ConnectionState.Connected -> stringResource(R.string.terminal_state_connected)
    is ConnectionState.Failed -> state.message
}

private sealed interface RenderItem {
    data class Text(val row: TerminalRow, val cursorCol: Int) : RenderItem
    data class Image(val placement: ImagePlacement, val data: TerminalImageData?) : RenderItem
}

private fun buildRenderItems(
    allRows: List<TerminalRow>,
    cursorAbsoluteRow: Int,
    cursorVisible: Boolean,
    cursorCol: Int,
    images: Map<Int, TerminalImageData>,
): List<RenderItem> {
    val items = mutableListOf<RenderItem>()
    var i = 0
    while (i < allRows.size) {
        val placement = allRows[i].image
        if (placement != null) {
            items += RenderItem.Image(placement, images[placement.imageId])
            i += placement.rowSpan.coerceAtLeast(1)
        } else {
            val cc = if (cursorVisible && i == cursorAbsoluteRow) cursorCol else -1
            items += RenderItem.Text(allRows[i], cc)
            i++
        }
    }
    return items
}

@Composable
private fun RenderItemRow(item: RenderItem, monoStyle: TextStyle, charSize: IntSize) {
    when (item) {
        is RenderItem.Text -> {
            // Keyed on the row's own content, so an unchanged line (most of a htop/vim screen,
            // most of the time) skips rebuilding its AnnotatedString on every emitted frame.
            val annotated = remember(item.row, item.cursorCol) { rowToAnnotatedString(item.row, item.cursorCol) }
            Text(text = annotated, style = monoStyle, color = TerminalFg)
        }
        is RenderItem.Image -> TerminalImageRow(item.placement, item.data, charSize)
    }
}

@Composable
private fun TerminalImageRow(placement: ImagePlacement, data: TerminalImageData?, charSize: IntSize) {
    val density = LocalDensity.current
    val bitmap = remember(data) { data?.let { decodeImage(it) } }
    val heightDp = with(density) { (charSize.height * placement.rowSpan).toDp() }
    Box(Modifier.height(heightDp)) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .padding(start = with(density) { (charSize.width * placement.colStart).toDp() })
                    .width(with(density) { (charSize.width * placement.colSpan).toDp() })
                    .height(heightDp),
            )
        }
    }
}

private const val MAX_IMAGE_PIXELS = 16_000_000L

private fun decodeImage(data: TerminalImageData): ImageBitmap? {
    if (data.pixelWidth <= 0 || data.pixelHeight <= 0) return null
    if (data.pixelWidth.toLong() * data.pixelHeight.toLong() > MAX_IMAGE_PIXELS) return null
    return try {
        when (data.format) {
            ImageFormat.PNG -> BitmapFactory.decodeByteArray(data.bytes, 0, data.bytes.size)?.asImageBitmap()
            ImageFormat.RGBA -> {
                val bitmap = Bitmap.createBitmap(data.pixelWidth, data.pixelHeight, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(data.bytes))
                bitmap.asImageBitmap()
            }
            ImageFormat.RGB -> {
                val rgba = ByteArray(data.pixelWidth * data.pixelHeight * 4)
                var src = 0
                var dst = 0
                while (src + 2 < data.bytes.size && dst + 3 < rgba.size) {
                    rgba[dst] = data.bytes[src]
                    rgba[dst + 1] = data.bytes[src + 1]
                    rgba[dst + 2] = data.bytes[src + 2]
                    rgba[dst + 3] = 0xFF.toByte()
                    src += 3
                    dst += 4
                }
                val bitmap = Bitmap.createBitmap(data.pixelWidth, data.pixelHeight, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
                bitmap.asImageBitmap()
            }
        }
    } catch (_: Throwable) {
        null
    }
}

@Composable
private fun stateColor(state: ConnectionState) = when (state) {
    is ConnectionState.Connected -> MaterialTheme.colorScheme.primary
    is ConnectionState.Failed -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun plainText(screen: TerminalSnapshot): String =
    (screen.scrollback + screen.screen).joinToString("\n") { row -> rowText(row).trimEnd() }

private fun rowText(row: TerminalRow): String = buildString { row.cells.forEach { append(it.char) } }

private fun rowToAnnotatedString(row: TerminalRow, cursorCol: Int = -1): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < row.cells.size) {
        val cell = row.cells[i]
        val (fg, bg) = resolveColors(cell, i == cursorCol)
        var j = i + 1
        while (j < row.cells.size) {
            val next = row.cells[j]
            val (nfg, nbg) = resolveColors(next, j == cursorCol)
            if (nfg != fg || nbg != bg || next.bold != cell.bold || next.underline != cell.underline) break
            j++
        }
        val text = buildString { for (k in i until j) append(row.cells[k].char) }
        withStyle(
            SpanStyle(
                color = fg,
                background = bg,
                fontWeight = if (cell.bold) FontWeight.Bold else FontWeight.Normal,
                textDecoration = if (cell.underline) TextDecoration.Underline else null,
            ),
        ) { append(text) }
        i = j
    }
}

private fun resolveColors(cell: TerminalCell, isCursor: Boolean = false): Pair<Color, Color> {
    var fg = when {
        cell.fgRgb >= 0 -> Color(cell.fgRgb or (0xFF shl 24))
        cell.fg >= 0 -> paletteColor(cell.fg)
        else -> TerminalFg
    }
    var bg = when {
        cell.bgRgb >= 0 -> Color(cell.bgRgb or (0xFF shl 24))
        cell.bg >= 0 -> paletteColor(cell.bg)
        else -> Color.Unspecified
    }
    if (cell.reverse xor isCursor) {
        val effectiveBg = if (bg == Color.Unspecified) TerminalBg else bg
        val t = fg
        fg = effectiveBg
        bg = t
    }
    return fg to bg
}

private val ansi16 = listOf(
    Color(0xFF1A1A1A), Color(0xFFCC5555), Color(0xFF55AA55), Color(0xFFAA9955),
    Color(0xFF5599CC), Color(0xFFAA55AA), Color(0xFF55AAAA), Color(0xFFBBBBBB),
    Color(0xFF666666), Color(0xFFFF6E6E), Color(0xFF6EFF6E), Color(0xFFFFFF6E),
    Color(0xFF6EAFFF), Color(0xFFFF6EFF), Color(0xFF6EFFFF), Color(0xFFFFFFFF),
)

private fun paletteColor(index: Int): Color = when {
    index < 16 -> ansi16[index]
    index < 232 -> {
        val i = index - 16
        val r = i / 36
        val g = (i / 6) % 6
        val b = i % 6
        fun level(v: Int) = if (v == 0) 0 else 55 + v * 40
        Color(level(r), level(g), level(b))
    }
    index < 256 -> {
        val gray = (8 + (index - 232) * 10).coerceIn(0, 255)
        Color(gray, gray, gray)
    }
    else -> TerminalFg
}
