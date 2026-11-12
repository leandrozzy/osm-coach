package com.osmaicoach.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AtlasTest {
    private fun img(w: Int, h: Int, f: (Int, Int) -> Int) = PixelProbe.Img(w, h, IntArray(w * h) { f(it % w, it / w) })

    @Test fun richSignatureSeparatesFlags() {
        fun flag(c1: Int, c2: Int, c3: Int, scale: Int) = img(100 * scale, 60 * scale) { _, y -> if (y < 20 * scale) c1 else if (y < 40 * scale) c2 else c3 }
        val a1 = PixelProbe.flagRich(flag(0xFF0000AA.toInt(), 0xFFFFFFFF.toInt(), 0xFFCC0000.toInt(), 1), 0.5f, 0.5f, 0.9f, 0.9f)!!
        val a2 = PixelProbe.flagRich(flag(0xFF0000AA.toInt(), 0xFFFFFFFF.toInt(), 0xFFCC0000.toInt(), 3), 0.5f, 0.5f, 0.9f, 0.9f)!!
        val b = PixelProbe.flagRich(flag(0xFF00AA00.toInt(), 0xFFFFDD00.toInt(), 0xFF00AA00.toInt(), 1), 0.5f, 0.5f, 0.9f, 0.9f)!!
        assertTrue(PixelProbe.richDist(a1, a2) < 0.02f)
        assertTrue(PixelProbe.richDist(a1, b) > 0.1f)
    }

    @Test fun darkAreaIsNoFlag() {
        assertNull(PixelProbe.flagRich(img(100, 60) { _, _ -> 0xFF050505.toInt() }, 0.5f, 0.5f, 0.9f, 0.9f))
    }

    @Test fun detectsNationalityFilterScreen() {
        val o = Fx.ocr(
            Fx.line("Nacionalidade", 0.5f, 0.05f),
            Fx.line("Barbadiano", 0.163f, 0.28f), Fx.line("Bareinita", 0.387f, 0.28f), Fx.line("Belga", 0.612f, 0.28f), Fx.line("Belizenho", 0.836f, 0.28f)
        )
        assertEquals(Atlas.Kind.NAT, Atlas.detect(o))
        assertNull(Atlas.detect(Fx.ocr(Fx.line("Plantel", 0.5f, 0.05f), Fx.line("Wirtz", 0.1f, 0.3f))))
        val l = Fx.ocr(Fx.line("Liga", 0.5f, 0.05f), Fx.line("Brasil", 0.163f, 0.3f), Fx.line("Bulgária", 0.387f, 0.3f), Fx.line("Chile", 0.612f, 0.3f), Fx.line("China", 0.836f, 0.3f))
        assertNotNull(Atlas.detect(l))
        assertEquals(Atlas.Kind.LEAGUE, Atlas.detect(l))
    }
}
