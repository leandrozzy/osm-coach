package com.osmaicoach.collector

import org.junit.Assert.*
import org.junit.Test

class CrestFlagTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun goldBoxFindsShieldFrame() {
        val w = 120; val h = 130
        val px = IntArray(w * h) { rgb(20, 40, 90) }             // cenário azul
        for (y in 40..100) for (x in 30..90) {                    // moldura: dourada nos lados e na base; o TOPO tem realce branco (não conta)
            val edge = x < 34 || x > 86 || y > 96
            if (edge) px[y * w + x] = rgb(212, 160, 30)
        }
        for (x in 34..86) for (y in 22..26) px[y * w + x] = rgb(245, 240, 230)   // realce branco do topo
        for (i in 0 until 40) px[(5 + i % 3) * w + 100 + i % 10] = rgb(230, 200, 150) // brilho de baixa saturação: não conta
        val b = CrestCut.goldBox(px, w, h)!!
        assertEquals(30, b[0]); assertEquals(90, b[2]); assertEquals(100, b[3])
        assertEquals(100 - Math.round(61 * 1.06f), b[1])        // topo = base − 1,06 × largura (inclui o realce que não é dourado)
    }

    @Test fun goldBoxRejectsNonShield() {
        val w = 100; val h = 100
        assertNull(CrestCut.goldBox(IntArray(w * h) { rgb(20, 40, 90) }, w, h))   // sem dourado
        val wide = IntArray(w * h) { rgb(20, 40, 90) }
        for (x in 5..95) for (y in 40..44) wide[y * w + x] = rgb(212, 160, 30)    // faixa dourada larga: não é escudo
        assertNull(CrestCut.goldBox(wide, w, h))
    }

    @Test fun flagImageQualityPrefix() {
        assertEquals(12.5f, Flags.imgQuality("q:125|AAAA")!!, 0.01f)
        assertEquals("AAAA", Flags.imgPayload("q:125|AAAA"))
        assertEquals("AAAA", Flags.imgPayload("AAAA"))          // formato antigo continua valendo
        assertEquals(0f, Flags.imgQuality("AAAA")!!, 0.01f)
        assertNull(Flags.imgQuality(null))
    }

    @Test fun leagueNamesWithoutCountry() {
        assertEquals("🇧🇷", Flags.emojiOfCompetition("Brasileirão Uzm"))
        assertEquals("🇺🇿", Flags.emojiOfCompetition("Superliga Uzbeque Uzm"))
    }

    @Test fun calendarStripGivesCrestSpotsEvenWithPlayedGames() {
        // frame real 7/11 (S1): faixa dos clubes em y 0,30/0,34 e cards com PLACAR (sem escudo) a partir de y 0,60
        val o = Fx.ocr(
            Fx.line("Fortuna Düsseldorf", 0.08f, 0.30f, 0.12f, 0.04f), Fx.line("leandrozzy", 0.08f, 0.34f, 0.08f),
            Fx.line("Rot-Weiss Essen", 0.22f, 0.30f, 0.12f, 0.04f), Fx.line("aldek_1", 0.22f, 0.34f, 0.06f),
            Fx.line("MSV Duisburg", 0.50f, 0.30f, 0.12f, 0.04f), Fx.line("Daniisz31", 0.50f, 0.34f, 0.07f),
            Fx.line("Fortuna Düsseldorf", 0.50f, 0.50f, 0.25f, 0.06f),
            Fx.line("Jornada 1", 0.09f, 0.60f, 0.08f), Fx.line("18-10-26", 0.09f, 0.69f, 0.08f), Fx.line("0-0", 0.09f, 0.77f, 0.05f),
            Fx.line("Waldhof Mannheim", 0.09f, 0.89f, 0.12f), Fx.line("Josep 2007", 0.09f, 0.94f, 0.08f)
        )
        val ex = Parsers.calendar(o, null)
        assertEquals(3, ex.crests.size)
        val c = ex.crests.first { it.team.startsWith("Rot-Weiss") }
        assertEquals(0.22f, c.x, 0.001f); assertEquals(0.215f, c.y, 0.001f)       // escudo = 0,085 acima do nome
    }

    @Test fun flagColumnIsAFixedLayoutConstant() {
        assertEquals(0.5914f, Parsers.FLAG_X, 0.0001f)
    }
}
