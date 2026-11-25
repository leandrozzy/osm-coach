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
