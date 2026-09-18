package com.pocketssh.app.terminal

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Inflater
import kotlin.math.ceil

/**
 * Minimal ANSI/VT100 terminal emulator: enough SGR/cursor/erase/alt-screen support for
 * shells and full-screen programs (vim, htop, less, mc) to render correctly, plus a subset
 * of the kitty graphics protocol (direct/base64 transmission only — file-based transmission
 * refers to paths on the remote host's filesystem, which is meaningless to a client-side
 * emulator over SSH, so it's intentionally unsupported, matching what kitty itself does).
 * Pure Kotlin, no Android dependencies, so it can be unit tested on the plain JVM.
 */
class TerminalEmulator(
    initialCols: Int = 80,
    initialRows: Int = 24,
    private val onResponse: (String) -> Unit = {},
) {
    private var cols = initialCols
    private var rows = initialRows

    private var main = Buffer(cols, rows)
    private var alt = Buffer(cols, rows)
    private var usingAlt = false
    private val scrollback = ArrayDeque<TerminalRow>()
    private val maxScrollback = 2000

    private val current: Buffer get() = if (usingAlt) alt else main

    private var state = ParserState.NORMAL
    private val paramBuffer = StringBuilder()
    private var isPrivate = false
    private var pendingStSwallow = false

    private var curFg = -1
    private var curBg = -1
    private var curFgRgb = -1
    private var curBgRgb = -1
    private var curBold = false
    private var curUnderline = false
    private var curReverse = false

    private var cursorVisible = true
    private var pendingWrap = false

    private var savedCursorRow = 0
    private var savedCursorCol = 0

    private var cellPixelWidth = 8f
    private var cellPixelHeight = 16f

    private val apcBuffer = StringBuilder()
    private var pendingImagePayload: StringBuilder? = null
    private var pendingImageControl: Map<String, String>? = null
    private val images = LinkedHashMap<Int, TerminalImageData>()
    private var totalImageBytes = 0L
    private val maxImages = 24
    private val maxImageBytes = 48L * 1024 * 1024

    @Synchronized
    fun feed(text: String) {
        for (ch in text) processChar(ch)
    }

    @Synchronized
    fun resize(newCols: Int, newRows: Int) {
        if (newCols <= 0 || newRows <= 0 || (newCols == cols && newRows == rows)) return
        cols = newCols
        rows = newRows
        main.resize(newCols, newRows)
        alt.resize(newCols, newRows)
    }

    @Synchronized
    fun setCellPixelSize(width: Float, height: Float) {
        if (width > 0f) cellPixelWidth = width
        if (height > 0f) cellPixelHeight = height
    }

    @Synchronized
    fun reset() {
        main = Buffer(cols, rows)
        alt = Buffer(cols, rows)
        usingAlt = false
        scrollback.clear()
        curFg = -1; curBg = -1; curFgRgb = -1; curBgRgb = -1
        curBold = false; curUnderline = false; curReverse = false
        cursorVisible = true
        pendingWrap = false
        pendingStSwallow = false
        state = ParserState.NORMAL
        apcBuffer.clear()
        pendingImagePayload = null
        pendingImageControl = null
        images.clear()
        totalImageBytes = 0
    }

    @Synchronized
    fun snapshot(): TerminalSnapshot {
        val buf = current
        return TerminalSnapshot(
            scrollback = if (usingAlt) emptyList() else scrollback.toList(),
            screen = buf.cells.mapIndexed { r, row -> TerminalRow(row.toList(), buf.rowImages[r]) },
            cursorRow = buf.cursorRow,
            cursorCol = buf.cursorCol,
            cursorVisible = cursorVisible,
            cols = cols,
            rows = rows,
            images = images.toMap(),
        )
    }

    private fun processChar(ch: Char) {
        if (pendingStSwallow) {
            pendingStSwallow = false
            if (ch == '\\') return
        }
        when (state) {
            ParserState.NORMAL -> when (ch) {
                '\u001B' -> state = ParserState.ESC
                '\r' -> { current.cursorCol = 0; pendingWrap = false }
                '\n' -> lineFeed()
                '\b' -> { if (current.cursorCol > 0) current.cursorCol--; pendingWrap = false }
                '\t' -> {
                    val next = ((current.cursorCol / 8) + 1) * 8
                    current.cursorCol = next.coerceAtMost(cols - 1)
                    pendingWrap = false
                }
                '\u0007' -> {}
                else -> if (ch.code >= 0x20) writeChar(ch)
            }
            ParserState.ESC -> handleEsc(ch)
            ParserState.CSI -> handleCsi(ch)
            ParserState.OSC -> handleOsc(ch)
            ParserState.APC -> handleApc(ch)
            ParserState.CHARSET -> state = ParserState.NORMAL
        }
    }

    private fun handleEsc(ch: Char) {
        when (ch) {
            '[' -> { state = ParserState.CSI; paramBuffer.clear(); isPrivate = false }
            ']' -> state = ParserState.OSC
            '_' -> { state = ParserState.APC; apcBuffer.clear() }
            '(', ')', '*', '+' -> state = ParserState.CHARSET
            '7' -> { savedCursorRow = current.cursorRow; savedCursorCol = current.cursorCol; state = ParserState.NORMAL }
            '8' -> {
                current.cursorRow = savedCursorRow.coerceIn(0, rows - 1)
                current.cursorCol = savedCursorCol.coerceIn(0, cols - 1)
                state = ParserState.NORMAL
            }
            'D' -> { lineFeed(); state = ParserState.NORMAL }
            'M' -> { reverseLineFeed(); state = ParserState.NORMAL }
            'E' -> { current.cursorCol = 0; lineFeed(); state = ParserState.NORMAL }
            'c' -> { reset(); state = ParserState.NORMAL }
            else -> state = ParserState.NORMAL
        }
    }

    private fun handleCsi(ch: Char) {
        when {
            ch == '?' && paramBuffer.isEmpty() -> isPrivate = true
            ch.isDigit() || ch == ';' -> paramBuffer.append(ch)
            else -> {
                val params = paramBuffer.toString().split(';').mapNotNull { it.toIntOrNull() }
                dispatchCsi(ch, params)
                state = ParserState.NORMAL
            }
        }
    }

    private fun handleOsc(ch: Char) {
        when (ch) {
            '\u0007' -> state = ParserState.NORMAL
            '\u001B' -> { state = ParserState.NORMAL; pendingStSwallow = true }
            else -> {}
        }
    }

    private fun handleApc(ch: Char) {
        when (ch) {
            '\u0007' -> { finishApc(); state = ParserState.NORMAL }
            '\u001B' -> { finishApc(); state = ParserState.NORMAL; pendingStSwallow = true }
            else -> apcBuffer.append(ch)
        }
    }

    private fun dispatchCsi(final: Char, rawParams: List<Int>) {
        if (isPrivate) {
            when (final) {
                'h' -> rawParams.forEach { setPrivateMode(it, true) }
                'l' -> rawParams.forEach { setPrivateMode(it, false) }
            }
            isPrivate = false
            return
        }

        fun p(i: Int, default: Int = 1) = rawParams.getOrNull(i)?.takeIf { it != 0 } ?: default
        fun p0(i: Int, default: Int = 0) = rawParams.getOrNull(i) ?: default

        when (final) {
            'A' -> current.cursorRow = (current.cursorRow - p(0)).coerceIn(0, rows - 1)
            'B' -> current.cursorRow = (current.cursorRow + p(0)).coerceIn(0, rows - 1)
            'C' -> current.cursorCol = (current.cursorCol + p(0)).coerceIn(0, cols - 1)
            'D' -> current.cursorCol = (current.cursorCol - p(0)).coerceIn(0, cols - 1)
            'E' -> { current.cursorRow = (current.cursorRow + p(0)).coerceIn(0, rows - 1); current.cursorCol = 0 }
            'F' -> { current.cursorRow = (current.cursorRow - p(0)).coerceIn(0, rows - 1); current.cursorCol = 0 }
            'G' -> current.cursorCol = (p(0) - 1).coerceIn(0, cols - 1)
            'H', 'f' -> {
                current.cursorRow = (p(0) - 1).coerceIn(0, rows - 1)
                current.cursorCol = (p(1) - 1).coerceIn(0, cols - 1)
            }
            'J' -> eraseDisplay(p0(0, 0))
            'K' -> eraseLine(p0(0, 0))
            'r' -> {
                val top = (p(0) - 1).coerceIn(0, rows - 1)
                val bottom = (p(1, rows) - 1).coerceIn(0, rows - 1)
                if (top < bottom) { current.scrollTop = top; current.scrollBottom = bottom }
                current.cursorRow = 0
                current.cursorCol = 0
            }
            'm' -> applySgr(rawParams.ifEmpty { listOf(0) })
            else -> {}
        }
        pendingWrap = false
    }

    private fun setPrivateMode(code: Int, enabled: Boolean) {
        when (code) {
            25 -> cursorVisible = enabled
            47, 1047, 1049 -> switchAltBuffer(enabled)
        }
    }

    private fun switchAltBuffer(enable: Boolean) {
        if (enable == usingAlt) return
        if (enable) {
            alt.clearAll()
            alt.cursorRow = main.cursorRow.coerceIn(0, rows - 1)
            alt.cursorCol = main.cursorCol.coerceIn(0, cols - 1)
            usingAlt = true
        } else {
            usingAlt = false
        }
        pendingWrap = false
    }

    private fun applySgr(codes: List<Int>) {
        var i = 0
        while (i < codes.size) {
            when (val code = codes[i]) {
                0 -> {
                    curFg = -1; curBg = -1; curFgRgb = -1; curBgRgb = -1
                    curBold = false; curUnderline = false; curReverse = false
                }
                1 -> curBold = true
                4 -> curUnderline = true
                7 -> curReverse = true
                22 -> curBold = false
                24 -> curUnderline = false
                27 -> curReverse = false
                39 -> { curFg = -1; curFgRgb = -1 }
                49 -> { curBg = -1; curBgRgb = -1 }
                in 30..37 -> { curFg = code - 30; curFgRgb = -1 }
                in 40..47 -> { curBg = code - 40; curBgRgb = -1 }
                in 90..97 -> { curFg = code - 90 + 8; curFgRgb = -1 }
                in 100..107 -> { curBg = code - 100 + 8; curBgRgb = -1 }
                38, 48 -> {
                    val isFg = code == 38
                    when (codes.getOrNull(i + 1)) {
                        5 -> {
                            val idx = codes.getOrNull(i + 2) ?: 0
                            if (isFg) { curFg = idx; curFgRgb = -1 } else { curBg = idx; curBgRgb = -1 }
                            i += 2
                        }
                        2 -> {
                            val r = codes.getOrNull(i + 2) ?: 0
                            val g = codes.getOrNull(i + 3) ?: 0
                            val b = codes.getOrNull(i + 4) ?: 0
                            val rgb = (r shl 16) or (g shl 8) or b
                            if (isFg) curFgRgb = rgb else curBgRgb = rgb
                            i += 4
                        }
                        else -> {}
                    }
                }
                else -> {}
            }
            i++
        }
    }

    private fun eraseDisplay(mode: Int) {
        val buf = current
        when (mode) {
            0 -> clearCells(buf.cursorRow, buf.cursorCol, buf.rows - 1, buf.cols - 1)
            1 -> clearCells(0, 0, buf.cursorRow, buf.cursorCol)
            2, 3 -> {
                for (r in 0 until buf.rows) for (c in 0 until buf.cols) buf.cells[r][c] = TerminalCell()
                buf.rowImages.fill(null)
                if (mode == 3 && !usingAlt) scrollback.clear()
            }
        }
    }

    private fun clearCells(fromRow: Int, fromCol: Int, toRow: Int, toCol: Int) {
        val buf = current
        for (r in fromRow..toRow) {
            val startCol = if (r == fromRow) fromCol else 0
            val endCol = if (r == toRow) toCol else buf.cols - 1
            for (c in startCol..endCol) buf.cells[r][c] = TerminalCell()
        }
    }

    private fun eraseLine(mode: Int) {
        val buf = current
        val row = buf.cursorRow
        when (mode) {
            0 -> for (c in buf.cursorCol until buf.cols) buf.cells[row][c] = TerminalCell()
            1 -> for (c in 0..buf.cursorCol) buf.cells[row][c] = TerminalCell()
            2 -> for (c in 0 until buf.cols) buf.cells[row][c] = TerminalCell()
        }
    }

    private fun writeChar(ch: Char) {
        val buf = current
        if (pendingWrap) {
            buf.cursorCol = 0
            lineFeed()
            pendingWrap = false
        }
        buf.cells[buf.cursorRow][buf.cursorCol] = TerminalCell(
            char = ch, fg = curFg, bg = curBg, fgRgb = curFgRgb, bgRgb = curBgRgb,
            bold = curBold, underline = curUnderline, reverse = curReverse,
        )
        if (buf.cursorCol == buf.cols - 1) {
            pendingWrap = true
        } else {
            buf.cursorCol++
        }
    }

    private fun lineFeed() {
        val buf = current
        when {
            buf.cursorRow == buf.scrollBottom -> {
                if (buf.scrollTop == 0 && !usingAlt) {
                    scrollback.addLast(TerminalRow(buf.cells[buf.scrollTop].toList(), buf.rowImages[buf.scrollTop]))
                    if (scrollback.size > maxScrollback) scrollback.removeFirst()
                }
                for (r in buf.scrollTop until buf.scrollBottom) {
                    buf.cells[r] = buf.cells[r + 1]
                    buf.rowImages[r] = buf.rowImages[r + 1]
                }
                buf.cells[buf.scrollBottom] = Array(buf.cols) { TerminalCell() }
                buf.rowImages[buf.scrollBottom] = null
            }
            buf.cursorRow < rows - 1 -> buf.cursorRow++
        }
        pendingWrap = false
    }

    private fun reverseLineFeed() {
        val buf = current
        if (buf.cursorRow == buf.scrollTop) {
            for (r in buf.scrollBottom downTo buf.scrollTop + 1) {
                buf.cells[r] = buf.cells[r - 1]
                buf.rowImages[r] = buf.rowImages[r - 1]
            }
            buf.cells[buf.scrollTop] = Array(buf.cols) { TerminalCell() }
            buf.rowImages[buf.scrollTop] = null
        } else if (buf.cursorRow > 0) {
            buf.cursorRow--
        }
    }

    // --- Kitty graphics protocol (direct/base64 transmission only) ---

    private fun finishApc() {
        val content = apcBuffer.toString()
        apcBuffer.clear()
        if (content.startsWith("G")) handleKittyGraphics(content.substring(1))
    }

    private fun parseControl(part: String): Map<String, String> = part.split(',').mapNotNull { kv ->
        val eq = kv.indexOf('=')
        if (eq <= 0) null else kv.substring(0, eq) to kv.substring(eq + 1)
    }.toMap()

    private fun handleKittyGraphics(content: String) {
        val semi = content.indexOf(';')
        val controlPart = if (semi >= 0) content.substring(0, semi) else content
        val payloadPart = if (semi >= 0) content.substring(semi + 1) else ""
        val control = parseControl(controlPart)

        val buffer = pendingImagePayload
        if (buffer != null) {
            buffer.append(payloadPart)
        } else if (control["m"] == "1") {
            pendingImagePayload = StringBuilder(payloadPart)
            pendingImageControl = control
            return
        }
        if (control["m"] == "1") return

        val fullControl = pendingImageControl ?: control
        val fullPayload = (pendingImagePayload ?: StringBuilder(payloadPart)).toString()
        pendingImagePayload = null
        pendingImageControl = null

        when (fullControl["a"] ?: "t") {
            "d" -> handleKittyDelete(fullControl)
            "q" -> handleKittyQuery(fullControl)
            "p" -> placeImage(fullControl["i"]?.toIntOrNull() ?: 0, fullControl)
            "T" -> handleKittyTransmit(fullControl, fullPayload, display = true)
            else -> handleKittyTransmit(fullControl, fullPayload, display = false)
        }
    }

    private fun handleKittyTransmit(control: Map<String, String>, payloadBase64: String, display: Boolean) {
        val id = control["i"]?.toIntOrNull() ?: 0
        if (payloadBase64.isNotEmpty()) {
            val raw = runCatching { Base64.getDecoder().decode(payloadBase64) }.getOrNull() ?: return
            val bytes = if (control["o"] == "z") runCatching { inflate(raw) }.getOrNull() ?: return else raw
            val format = when (control["f"]?.toIntOrNull() ?: 32) {
                100 -> ImageFormat.PNG
                24 -> ImageFormat.RGB
                else -> ImageFormat.RGBA
            }
            val dimensions = when (format) {
                ImageFormat.PNG -> pngDimensions(bytes)
                else -> {
                    val w = control["s"]?.toIntOrNull()
                    val h = control["v"]?.toIntOrNull()
                    if (w != null && h != null) w to h else null
                }
            } ?: return
            val (width, height) = dimensions
            if (width <= 0 || height <= 0) return
            storeImage(id, TerminalImageData(format, bytes, width, height))
        }
        if (display) placeImage(id, control)
    }

    private fun handleKittyDelete(control: Map<String, String>) {
        when (control["d"] ?: "a") {
            "i", "I" -> {
                val id = control["i"]?.toIntOrNull()
                if (id != null) {
                    images.remove(id)?.let { totalImageBytes -= it.bytes.size }
                    for (buf in listOf(main, alt)) {
                        for (r in buf.rowImages.indices) {
                            if (buf.rowImages[r]?.imageId == id) buf.rowImages[r] = null
                        }
                    }
                }
            }
            else -> {
                images.clear()
                totalImageBytes = 0
                for (buf in listOf(main, alt)) buf.rowImages.fill(null)
            }
        }
    }

    private fun handleKittyQuery(control: Map<String, String>) {
        val id = control["i"] ?: "0"
        onResponse("\u001B_Gi=$id;OK\u001B\\")
    }

    private fun placeImage(id: Int, control: Map<String, String>) {
        val data = images[id] ?: return
        val buf = current
        val colSpan = (control["c"]?.toIntOrNull()?.takeIf { it > 0 }
            ?: ceil(data.pixelWidth / cellPixelWidth).toInt().coerceAtLeast(1)).coerceAtMost(cols)
        val rowSpan = (control["r"]?.toIntOrNull()?.takeIf { it > 0 }
            ?: ceil(data.pixelHeight / cellPixelHeight).toInt().coerceAtLeast(1)).coerceAtMost(rows)
        buf.rowImages[buf.cursorRow] = ImagePlacement(id, buf.cursorCol, colSpan, rowSpan)
    }

    private fun storeImage(id: Int, data: TerminalImageData) {
        images.remove(id)?.let { totalImageBytes -= it.bytes.size }
        images[id] = data
        totalImageBytes += data.bytes.size
        while ((images.size > maxImages || totalImageBytes > maxImageBytes) && images.isNotEmpty()) {
            val oldestId = images.keys.first()
            images.remove(oldestId)?.let { totalImageBytes -= it.bytes.size }
        }
    }

    private fun pngDimensions(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 24) return null
        val signature = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        for (i in signature.indices) if (bytes[i] != signature[i]) return null
        fun be32(offset: Int) = ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
        return be32(16) to be32(20)
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream(data.size * 3)
        val buffer = ByteArray(4096)
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            if (count == 0 && inflater.needsInput()) break
            out.write(buffer, 0, count)
        }
        inflater.end()
        return out.toByteArray()
    }

    private enum class ParserState { NORMAL, ESC, CSI, OSC, APC, CHARSET }

    private class Buffer(var cols: Int, var rows: Int) {
        var cells: Array<Array<TerminalCell>> = Array(rows) { Array(cols) { TerminalCell() } }
        var rowImages: Array<ImagePlacement?> = arrayOfNulls(rows)
        var cursorRow = 0
        var cursorCol = 0
        var scrollTop = 0
        var scrollBottom = rows - 1

        fun resize(newCols: Int, newRows: Int) {
            val oldCells = cells
            val oldImages = rowImages
            cells = Array(newRows) { r -> Array(newCols) { c -> oldCells.getOrNull(r)?.getOrNull(c) ?: TerminalCell() } }
            rowImages = Array(newRows) { r -> oldImages.getOrNull(r) }
            cols = newCols
            rows = newRows
            scrollTop = 0
            scrollBottom = newRows - 1
            cursorRow = cursorRow.coerceIn(0, newRows - 1)
            cursorCol = cursorCol.coerceIn(0, newCols - 1)
        }

        fun clearAll() {
            cells = Array(rows) { Array(cols) { TerminalCell() } }
            rowImages = arrayOfNulls(rows)
            cursorRow = 0
            cursorCol = 0
        }
    }
}
