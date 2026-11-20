package com.osmaicoach.collector

import org.junit.Assert.*
import org.junit.Test

class FaceCutTest {
    @Test fun navyBackgroundBecomesLightAndFaceStays() {
        val s = 60
        val px = IntArray(s * s) { 0xFF0A2850.toInt() }                       // azul-marinho do cartão
        for (y in 0 until s) for (x in 0 until s) if ((x - 30) * (x - 30) + (y - 30) * (y - 30) < 14 * 14) px[y * s + x] = 0xFFC88C64.toInt()   // rosto
        for (x in 0 until s) px[x] = 0xFF9BE000.toInt()                        // moldura verde no topo
        val out = FaceCut.cutout(px, s)
        assertEquals(0xFFC88C64.toInt(), out[30 * s + 30])                    // centro do rosto intacto
        val corner = out[5 * s + 5]
        assertTrue(((corner shr 16) and 255) > 230 && (corner and 255) > 230)  // canto = fundo claro
        val top = out[1 * s + 30]
        assertTrue(((top shr 16) and 255) > 230)                              // a moldura some
    }
}
