package com.osmaicoach.collector

import org.junit.Assert.*
import org.junit.Test

class PhotoFitTest {
    private fun scene(w: Int, h: Int): IntArray {
        val px = IntArray(w * h) { 0xFF2E2E32.toInt() }   // fundo cinza escuro liso
        fun put(x: Int, y: Int, c: Int) { if (x in 0 until w && y in 0 until h) px[y * w + x] = c }
        val cx = w / 2; val cy = (h * 0.34).toInt(); val rx = (w * 0.10).toInt(); val ry = (h * 0.16).toInt()
        for (y in 0 until h) for (x in 0 until w) {
            val dx = (x - cx).toDouble() / rx; val dy = (y - cy).toDouble() / ry
            if (dx * dx + dy * dy <= 1.0) put(x, y, 0xFFC68B6B.toInt())                       // rosto (pele)
            else if (dx * dx + dy * dy <= 1.35 && y < cy - ry / 2) put(x, y, 0xFF5A3A1E.toInt())   // cabelo
            else if (y > cy + ry * 1.2 && Math.abs(x - cx) < w * 0.28) put(x, y, 0xFF1E3CAA.toInt()) // camisa azul
        }
        return px
    }

    @Test fun removesStudioBackgroundAndFramesHeadAndShoulders() {
        val w = 400; val h = 300
        val f = PhotoFit.fit(scene(w, h), w, h, 96)
        assertTrue(f.removedBg)
        // canto = fundo claro padrão
        assertEquals(PhotoFit.BG, f.px[0])
        assertEquals(PhotoFit.BG, f.px[95])
        // o centro-alto tem pele (não o fundo)
        val c = f.px[40 * 96 + 48]
        assertNotEquals(PhotoFit.BG, c)
        val r = (c shr 16) and 255; val b = c and 255
        assertTrue("pele: r>b", r > b + 20)
    }

    @Test fun plainImageWithoutFaceFallsBackToSquare() {
        val w = 120; val h = 160
        val px = IntArray(w * h) { 0xFF808080.toInt() }
        val f = PhotoFit.fit(px, w, h, 64)
        assertTrue(!f.removedBg)
        assertEquals(64 * 64, f.px.size)
    }
}
