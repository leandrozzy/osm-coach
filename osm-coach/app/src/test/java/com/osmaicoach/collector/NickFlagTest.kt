package com.osmaicoach.collector

import org.junit.Assert.*
import org.junit.Test

class NickFlagTest {
    private fun img(rx: Float?): PixelProbe.Img {
        val w = 1000; val h = 440
        val px = IntArray(w * h) { 0xFF10182C.toInt() }
        if (rx != null) for (y in (0.3965f * h).toInt()..(0.4265f * h).toInt()) for (x in (rx * w).toInt()..((rx + 0.02f) * w).toInt()) px[y * w + x] = 0xFF1E9E3A.toInt()
        return PixelProbe.Img(w, h, px)
    }
    @Test fun flagUnderRivalNameMeansHumanEvenWithUnreadNick() {
        assertTrue(PixelProbe.nickFlag(img(0.734f), left = false))
        assertFalse(PixelProbe.nickFlag(img(0.734f), left = true))
        assertFalse(PixelProbe.nickFlag(img(null), left = false))
    }
}
