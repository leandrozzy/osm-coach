package com.osmaicoach.collector

import org.junit.Assert.*
import org.junit.Test

/** Design system (25/11): legibilidade da paleta (WCAG) e alvos de toque. Só aparência: nenhum dado ou regra é testado aqui. */
class DesignTest {
    @Test fun textHasStrongContrastOnEverySurface() {
        for (bg in listOf(DS.BG, DS.SURFACE, DS.ELEVATED)) {
            assertTrue("texto principal em ${bg}", DS.contrast(DS.TEXT, bg) >= 7.0)
            assertTrue("texto secundário em ${bg}", DS.contrast(DS.TEXT2, bg) >= 4.5)
        }
    }

    @Test fun accentColorsAreReadableAsTextOnSurfaces() {
        for (c in listOf(DS.LIME, DS.CYAN, DS.AMBER, DS.GREEN)) for (bg in listOf(DS.BG, DS.SURFACE, DS.ELEVATED)) assertTrue("acento $c em $bg", DS.contrast(c, bg) >= 4.5)
        for (bg in listOf(DS.BG, DS.SURFACE)) assertTrue("vermelho em $bg", DS.contrast(DS.RED, bg) >= 4.5)
    }

    @Test fun primaryActionTextIsReadableOnLime() {
        assertTrue(DS.contrast(DS.ON_LIME, DS.LIME) >= 7.0)
    }

    @Test fun legacyPaletteIsMappedToTheNewTokens() {
        assertEquals(DS.BG, C.BG); assertEquals(DS.SURFACE, C.SURFACE); assertEquals(DS.ELEVATED, C.SURFACE2)
        assertEquals(DS.LIME, C.PRIMARY); assertEquals(DS.AMBER, C.GOLD); assertEquals(DS.RED, C.BAD)
    }

    @Test fun touchTargetsAndSpacing() {
        assertTrue(DS.TOUCH.value >= 48f)
        assertTrue(DS.RADIUS_CARD.value in 12f..18f && DS.RADIUS_CONTROL.value in 12f..18f)
    }

    @Test fun borderIsDistinguishableFromSurfaceButSubtle() {
        val r = DS.contrast(DS.BORDER, DS.SURFACE)
        assertTrue(r in 1.1..3.0)
    }
}

class DesignKitTest {
    @Test fun parseDm_leDataDoCardDoOsm() {
        assertEquals(Triple(25, 10, 2026), parseDm("25/11/2026"))
        assertEquals(Triple(5, 0, 2026), parseDm("05/01/26"))
        assertEquals(Triple(3, 11, null), parseDm("sáb 3/12"))
        assertNull(parseDm("32/13/2026"))
        assertNull(parseDm(null))
        assertNull(parseDm("sem data"))
    }
    @Test fun nomesDeMesEDiaDaSemana() {
        assertEquals("novembro", monthName(10))
        assertEquals("janeiro", monthName(-3))
        val c = java.util.Calendar.getInstance().apply { clear(); set(2026, 10, 25) }   // quarta-feira
        assertEquals("qua", ptDow(c))
    }
}

class WidgetDesignTest {
    private val now = 1_000_000_000_000L
    @Test fun urgencyColorFollowsTheAppRule() {
        assertEquals(0xFFEF5350.toInt(), SlotWidget.urgencyColor(now + 30 * 60_000L, now))        // ≤ 1 h
        assertEquals(0xFFF5B942.toInt(), SlotWidget.urgencyColor(now + 3 * 3_600_000L, now))      // dentro das 8 h
        assertEquals(0xFF4CCB7A.toInt(), SlotWidget.urgencyColor(now + 10 * 3_600_000L, now))     // depois
        assertEquals(0xFFA8B7C8.toInt(), SlotWidget.urgencyColor(null, now))
        assertEquals(0xFFA8B7C8.toInt(), SlotWidget.urgencyColor(now - 1000L, now))
    }

    @Test fun staleTextOnlyWhenOld() {
        fun row(stale: Boolean, readAt: Long?) = SlotWidget.Row(1, "S1", "", "", stale, 0, readAt)
        assertEquals("", SlotWidget.staleText(row(false, now - 3_600_000L), now))
        assertEquals("⚠ lido há 14 h", SlotWidget.staleText(row(true, now - 14 * 3_600_000L), now))
        assertEquals("⚠ lido há 3 d", SlotWidget.staleText(row(true, now - 80 * 3_600_000L), now))
        assertEquals("⚠ sem leitura", SlotWidget.staleText(row(true, null), now))
    }
}
