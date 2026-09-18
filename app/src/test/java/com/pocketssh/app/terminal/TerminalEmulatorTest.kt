package com.pocketssh.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class TerminalEmulatorTest {

    @Test
    fun `plain text lands at cursor`() {
        val emulator = TerminalEmulator(10, 3)
        emulator.feed("hi")
        val snapshot = emulator.snapshot()
        assertEquals('h', snapshot.screen[0].cells[0].char)
        assertEquals('i', snapshot.screen[0].cells[1].char)
        assertEquals(0, snapshot.cursorRow)
        assertEquals(2, snapshot.cursorCol)
    }

    @Test
    fun `carriage return and line feed move cursor`() {
        val emulator = TerminalEmulator(10, 3)
        emulator.feed("ab\r\ncd")
        val snapshot = emulator.snapshot()
        assertEquals('a', snapshot.screen[0].cells[0].char)
        assertEquals('c', snapshot.screen[1].cells[0].char)
        assertEquals('d', snapshot.screen[1].cells[1].char)
    }

    @Test
    fun `tab moves to next stop of eight`() {
        val emulator = TerminalEmulator(20, 2)
        emulator.feed("a\tb")
        val snapshot = emulator.snapshot()
        assertEquals('a', snapshot.screen[0].cells[0].char)
        assertEquals('b', snapshot.screen[0].cells[8].char)
    }

    @Test
    fun `cursor position escape moves cursor`() {
        val emulator = TerminalEmulator(10, 3)
        emulator.feed("\u001B[2;3Hx")
        val snapshot = emulator.snapshot()
        assertEquals('x', snapshot.screen[1].cells[2].char)
    }

    @Test
    fun `sgr sets foreground color index`() {
        val emulator = TerminalEmulator(10, 3)
        emulator.feed("\u001B[31mred\u001B[0m")
        val snapshot = emulator.snapshot()
        assertEquals(1, snapshot.screen[0].cells[0].fg)
        assertEquals(-1, snapshot.screen[0].cells[0].bg)
    }

    @Test
    fun `erase display clears everything on mode 2`() {
        val emulator = TerminalEmulator(5, 2)
        emulator.feed("hello")
        emulator.feed("\u001B[2J")
        val snapshot = emulator.snapshot()
        assertEquals(' ', snapshot.screen[0].cells[0].char)
    }

    @Test
    fun `alternate screen buffer restores main content on exit`() {
        val emulator = TerminalEmulator(5, 2)
        emulator.feed("main1")
        emulator.feed("\u001B[?1049h")
        emulator.feed("\u001B[1;1H")
        emulator.feed("alt12")
        val altSnapshot = emulator.snapshot()
        assertEquals('a', altSnapshot.screen[0].cells[0].char)

        emulator.feed("\u001B[?1049l")
        val restored = emulator.snapshot()
        assertEquals('m', restored.screen[0].cells[0].char)
    }

    @Test
    fun `line feed past bottom row scrolls into scrollback`() {
        val emulator = TerminalEmulator(5, 2)
        emulator.feed("aaaaa\r\nbbbbb\r\nccccc")
        val snapshot = emulator.snapshot()
        assertEquals(1, snapshot.scrollback.size)
        assertEquals('a', snapshot.scrollback[0].cells[0].char)
        assertEquals('b', snapshot.screen[0].cells[0].char)
        assertEquals('c', snapshot.screen[1].cells[0].char)
    }

    @Test
    fun `resize pads and truncates`() {
        val emulator = TerminalEmulator(5, 2)
        emulator.feed("hello")
        emulator.resize(3, 2)
        val shrunk = emulator.snapshot()
        assertEquals(3, shrunk.cols)
        assertEquals('h', shrunk.screen[0].cells[0].char)
        assertEquals('l', shrunk.screen[0].cells[2].char)

        emulator.resize(6, 2)
        val grown = emulator.snapshot()
        assertEquals(' ', grown.screen[0].cells[5].char)
    }

    @Test
    fun `kitty transmit and display places image at cursor`() {
        val emulator = TerminalEmulator(80, 24)
        val payload = encodePng(160, 80)
        emulator.feed("\u001B_Ga=T,i=1,f=100;$payload\u001B\\")
        val snapshot = emulator.snapshot()
        val placement = snapshot.screen[0].image
        assertNotNull(placement)
        assertEquals(1, placement!!.imageId)
        assertEquals(0, placement.colStart)
        assertEquals(20, placement.colSpan)
        assertEquals(5, placement.rowSpan)
        assertTrue(snapshot.images.containsKey(1))
    }

    @Test
    fun `chunked transmission reassembles before displaying`() {
        val emulator = TerminalEmulator(80, 24)
        val payload = encodePng(80, 16)
        val half = payload.length / 2
        emulator.feed("\u001B_Ga=T,i=2,f=100,m=1;${payload.substring(0, half)}\u001B\\")
        assertNull(emulator.snapshot().screen[0].image)

        emulator.feed("\u001B_Gm=0;${payload.substring(half)}\u001B\\")
        val placement = emulator.snapshot().screen[0].image
        assertNotNull(placement)
        assertEquals(2, placement!!.imageId)
    }

    @Test
    fun `delete by id removes stored image and placement`() {
        val emulator = TerminalEmulator(80, 24)
        val payload = encodePng(80, 16)
        emulator.feed("\u001B_Ga=T,i=3,f=100;$payload\u001B\\")
        assertTrue(emulator.snapshot().images.containsKey(3))

        emulator.feed("\u001B_Ga=d,d=i,i=3\u001B\\")
        val snapshot = emulator.snapshot()
        assertFalse(snapshot.images.containsKey(3))
        assertNull(snapshot.screen[0].image)
    }

    @Test
    fun `query action responds over the callback`() {
        var response: String? = null
        val emulator = TerminalEmulator(80, 24, onResponse = { response = it })
        emulator.feed("\u001B_Ga=q,i=7\u001B\\")
        assertEquals("\u001B_Gi=7;OK\u001B\\", response)
    }

    @Test
    fun `explicit columns and rows override pixel based sizing`() {
        val emulator = TerminalEmulator(80, 24)
        val payload = encodePng(800, 800)
        emulator.feed("\u001B_Ga=T,i=4,f=100,c=10,r=3;$payload\u001B\\")
        val placement = emulator.snapshot().screen[0].image
        assertNotNull(placement)
        assertEquals(10, placement!!.colSpan)
        assertEquals(3, placement.rowSpan)
    }

    private fun encodePng(width: Int, height: Int): String {
        val bytes = ByteArray(24)
        val signature = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
        signature.copyInto(bytes, 0)
        fun putBe32(offset: Int, value: Int) {
            bytes[offset] = ((value shr 24) and 0xFF).toByte()
            bytes[offset + 1] = ((value shr 16) and 0xFF).toByte()
            bytes[offset + 2] = ((value shr 8) and 0xFF).toByte()
            bytes[offset + 3] = (value and 0xFF).toByte()
        }
        putBe32(8, 13)
        "IHDR".toByteArray().copyInto(bytes, 12)
        putBe32(16, width)
        putBe32(20, height)
        return Base64.getEncoder().encodeToString(bytes)
    }
}
