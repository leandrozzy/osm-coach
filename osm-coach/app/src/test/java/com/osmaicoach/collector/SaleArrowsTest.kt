package com.osmaicoach.collector

import org.junit.Assert.*
import org.junit.Test

class SaleArrowsTest {
    private fun img(arrows: Boolean): PixelProbe.Img {
        val w = 1000; val h = 440
        val px = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        if (arrows) {
            // setas vermelha + verde junto da camisa da ÚLTIMA linha (nome y 0,952; setas y 0,964–0,99)
            for (y in (0.964f * h).toInt()..(0.99f * h).toInt()) for (x in (0.028f * w).toInt()..(0.036f * w).toInt()) px[y * w + x] = 0xFFE01010.toInt()
            for (y in (0.964f * h).toInt()..(0.99f * h).toInt()) for (x in (0.037f * w).toInt()..(0.045f * w).toInt()) px[y * w + x] = 0xFF30D010.toInt()
        }
        return PixelProbe.Img(w, h, px)
    }
    @Test fun lastRowAtScreenBottomStillReadsSaleArrows() {
        assertEquals(true, PixelProbe.saleArrows(img(true), 0.952f))
        assertEquals(false, PixelProbe.saleArrows(img(false), 0.952f))
    }
    @Test fun rowCutByTheEdgeIsUnknown() {
        assertNull(PixelProbe.saleArrows(img(false), 0.97f))
    }
}
