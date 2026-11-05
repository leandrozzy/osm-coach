package com.osmaicoach.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Telas reais de Resultado (J6, Otelul 0-4 Dinamo, prints do usuário) imitadas com Fx. */
class ResultScreenTest {
    @Test fun zonesAreReadWhereverTheScrollPutsThem() {
        // print real: "Zonas de ação" no meio da tela (y≈0.28) e os % logo abaixo (y≈0.39), fora da faixa antiga 0.72-0.88
        val o = Fx.ocr(
            Fx.line("Zonas de ação", 0.5f, 0.245f, 0.1f),
            Fx.line("27 %", 0.33f, 0.39f, 0.04f), Fx.line("53 %", 0.5f, 0.39f, 0.04f), Fx.line("20 %", 0.67f, 0.39f, 0.04f),
            Fx.line("Notas", 0.5f, 0.57f, 0.05f)
        )
        assertTrue(Parsers.isMatchResult(o))
        assertEquals(listOf(27, 53, 20), Parsers.matchResult(o).matchReport!!.zones)
    }

    @Test fun eventsAreTypedGoalsSubsAndPlainPlays() {
        val o = Fx.ocr(
            Fx.line("Primeira parte", 0.5f, 0.02f, 0.1f),
            Fx.line("1'", 0.5f, 0.10f, 0.02f), Fx.line("18. Dembélé", 0.93f, 0.10f, 0.08f),
            Fx.line("13'", 0.5f, 0.19f, 0.02f), Fx.line("4. Güler", 0.93f, 0.168f, 0.06f), Fx.line("Assistência: Marquinhos", 0.9f, 0.215f, 0.18f),
            Fx.line("Segunda parte", 0.5f, 0.27f, 0.1f),
            Fx.line("61'", 0.5f, 0.35f, 0.02f), Fx.line("5. Davies", 0.93f, 0.33f, 0.06f), Fx.line("Assistênci...", 0.93f, 0.375f, 0.1f),
            Fx.line("69'", 0.5f, 0.455f, 0.02f), Fx.line("8. V. Edvardsen", 0.09f, 0.43f, 0.1f), Fx.line("20. Cu...", 0.07f, 0.48f, 0.06f),
            Fx.line("Homem do jogo", 0.5f, 0.74f, 0.1f)
        )
        val ev = Parsers.matchResult(o).matchReport!!.events
        assertTrue(ev.toString(), ev.contains("13' (visitante) gol de Güler (assistência: Marquinhos)"))
        assertTrue(ev.toString(), ev.contains("61' (visitante) gol de Davies (com assistência)"))
        assertTrue(ev.toString(), ev.contains("69' (casa) substituição: entra V. Edvardsen, sai Cu"))
        assertTrue(ev.toString(), ev.any { it.startsWith("1' (visitante) lance de Dembélé") })
    }

    @Test fun lowPlayerRatingsConfirmWhereTheTeamFailed() {
        fun g(def: Double?) = Coach.Game(6, "Dinamo", "D", 0, 4, "5-2-3 A", "Jogar pelas alas", 37, 9, 14, 12, "4-3-3 A", null, 73, 89, def, 6.0, 6.0)
        // 4 gols sofridos mas remates equilibrados: só as notas (defesa 4,4) apontam a defesa
        val weak = Coach.lessons(listOf(g(4.4)), null)
        assertTrue(weak.defNeed > 0)
        val review = Coach.review(g(4.4))
        assertTrue(review.wrong.any { it.contains("Defesa com nota média 4,4") })
        assertTrue(Coach.review(g(7.0)).wrong.none { it.contains("nota média") })
    }
}

class EventIconTest {
    private fun img(draw: (IntArray, Int, Int) -> Unit): PixelProbe.Img {
        val w = 400; val h = 200
        val px = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        draw(px, w, h)
        return PixelProbe.Img(w, h, px)
    }
    private fun rect(px: IntArray, w: Int, x0: Int, x1: Int, y0: Int, y1: Int, c: Int) { for (y in y0 until y1) for (x in x0 until x1) px[y * w + x] = c }
    private val RED = 0xFFE01010.toInt()
    private val YEL = 0xFFF5D020.toInt()
    private val GRN = 0xFF20D020.toInt()

    @org.junit.Test fun cruzCartaoETrocaSaoDistintos() {
        val cruz = img { p, w, _ -> rect(p, w, 387, 391, 94, 106, RED); rect(p, w, 383, 395, 98, 102, RED) }
        org.junit.Assert.assertEquals("lesao", PixelProbe.eventIcon(cruz, false, 0.5f))
        val verm = img { p, w, _ -> rect(p, w, 384, 394, 94, 106, RED) }
        org.junit.Assert.assertEquals("vermelho", PixelProbe.eventIcon(verm, false, 0.5f))
        val amar = img { p, w, _ -> rect(p, w, 384, 394, 94, 106, YEL) }
        org.junit.Assert.assertEquals("amarelo", PixelProbe.eventIcon(amar, false, 0.5f))
        val troca = img { p, w, _ -> rect(p, w, 384, 389, 90, 100, GRN); rect(p, w, 389, 394, 100, 110, RED) }
        org.junit.Assert.assertEquals("troca", PixelProbe.eventIcon(troca, false, 0.5f))
        org.junit.Assert.assertNull(PixelProbe.eventIcon(img { _, _, _ -> }, false, 0.5f))
    }

    @org.junit.Test fun resultadoTipaLesao() {
        val o = Fx.ocr(
            Fx.line("10'", 0.50f, 0.30f, 0.03f), Fx.line("Erick Pulga", 0.92f, 0.27f, 0.08f), Fx.line("Rotura no músc…", 0.90f, 0.31f, 0.10f)
        )
        val ev = Parsers.matchResult(o).matchReport!!.events
        org.junit.Assert.assertTrue(ev.toString(), ev.any { it.startsWith("10'") && it.contains("lesão de Erick Pulga") })
    }
}
