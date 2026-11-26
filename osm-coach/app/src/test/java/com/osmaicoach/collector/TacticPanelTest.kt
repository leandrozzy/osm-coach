package com.osmaicoach.collector

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Painel ROLADO do editor de tática, com as leituras reais do diagnóstico de 25/11 (22:32–22:34, slot 1). */
class TacticPanelTest {
    private fun scr(vararg l: Triple<String, Float, Float>) = Fx.ocr(*l.map { Fx.line(it.first, it.second, it.third) }.toTypedArray(),
        Fx.line("22,4M", 0.18f, 0.04f), Fx.line("133", 0.27f, 0.04f), Fx.line("FREE", 0.33f, 0.05f))

    @Test fun pressureAndStyleSlidersAreReadFromTheNumberBoxes() {
        val o = scr(Triple("Pressão", 0.50f, 0.16f), Triple("Equilibrado", 0.50f, 0.20f), Triple("Estilo", 0.50f, 0.55f), Triple("Defensivo", 0.50f, 0.59f),
            Triple("Temporização", 0.50f, 0.94f), Triple("Passes rápidos", 0.50f, 0.98f), Triple("42", 0.95f, 0.35f), Triple("33", 0.95f, 0.74f))
        val p = Parsers.tacticPanel(o)
        assertEquals("42", p["pressure"]); assertEquals("33", p["mentality"])
        assertNull(p["tempo"])                      // o número do 3º controle ainda não apareceu: não inventa
        assertEquals(ScreenType.TACTIC, ScreenClassifier.classify(o, true))
    }

    @Test fun markingOffsideAndTempoComeFromTheLowerPanel() {
        val o = scr(Triple("Marcação", 0.26f, 0.54f), Triple("À zona", 0.26f, 0.58f), Triple("Temporização", 0.50f, 0.15f), Triple("Passes rápidos", 0.50f, 0.19f),
            Triple("Fazer fora-de-jogo", 0.74f, 0.54f), Triple("Não", 0.74f, 0.58f), Triple("74", 0.95f, 0.34f))
        val p = Parsers.tacticPanel(o)
        assertEquals("74", p["tempo"]); assertEquals("À zona", p["marking"]); assertEquals("Não", p["offside"])
        assertEquals(ScreenType.TACTIC, ScreenClassifier.classify(o, true))
    }

    @Test fun numberThatContradictsTheBandWrittenBelowTheLabelIsDiscarded() {
        val o = scr(Triple("Pressão", 0.50f, 0.20f), Triple("Chegar perto do adversário", 0.50f, 0.24f), Triple("Estilo", 0.50f, 0.59f), Triple("Neutro", 0.50f, 0.63f),
            Triple("65", 0.95f, 0.39f), Triple("17", 0.95f, 0.78f))      // 17 não é "Neutro" (41–60): leitura errada
        val p = Parsers.tacticPanel(o)
        assertEquals("65", p["pressure"]); assertNull(p["mentality"])
    }

    @Test fun rivalAnalysisLayoutIsNotTheEditor() {
        val o = scr(Triple("Marcação", 0.67f, 0.68f), Triple("À zona", 0.67f, 0.72f), Triple("Fazer fora-de-jogo", 0.95f, 0.68f), Triple("Não", 0.95f, 0.72f))
        assertTrue(Parsers.tacticPanel(o).isEmpty())
        assertFalse(Parsers.isTacticPanel(o))
    }

    @Test fun diffComparesSlidersWithTolerance() {
        val plan = JSONObject().put("pressure", 42).put("mentality", 33).put("tempo", 74).put("offside", "Não")
        assertTrue(Parsers.tacticDiff(JSONObject().put("pressure", "44").put("mentality", "33").put("tempo", "74").put("offside", "Não"), plan, null).isEmpty())
        val d = Parsers.tacticDiff(JSONObject().put("pressure", "65").put("offside", "Sim"), plan, null)
        assertEquals(2, d.size)
    }
}
