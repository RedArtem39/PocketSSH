package com.pocketssh.app.terminal

data class TerminalCell(
    val char: Char = ' ',
    val fg: Int = -1,
    val bg: Int = -1,
    val fgRgb: Int = -1,
    val bgRgb: Int = -1,
    val bold: Boolean = false,
    val underline: Boolean = false,
    val reverse: Boolean = false,
)

enum class ImageFormat { PNG, RGB, RGBA }

/** Not a data class: a ByteArray field would give equals()/hashCode() reference semantics anyway. */
class TerminalImageData(
    val format: ImageFormat,
    val bytes: ByteArray,
    val pixelWidth: Int,
    val pixelHeight: Int,
)

data class ImagePlacement(
    val imageId: Int,
    val colStart: Int,
    val colSpan: Int,
    val rowSpan: Int,
)

data class TerminalRow(val cells: List<TerminalCell>, val image: ImagePlacement? = null)

data class TerminalSnapshot(
    val scrollback: List<TerminalRow>,
    val screen: List<TerminalRow>,
    val cursorRow: Int,
    val cursorCol: Int,
    val cursorVisible: Boolean,
    val cols: Int,
    val rows: Int,
    val images: Map<Int, TerminalImageData> = emptyMap(),
)
