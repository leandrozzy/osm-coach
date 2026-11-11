package com.osmaicoach.collector

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InsightsTest {
    private fun rule(f: String, st: String, p: Int, m: Int, t: Int, src: String) =
        JSONObject().put("formation", f).put("style", st).put("pressure", p).put("mentality", m).put("tempo", t).put("source", src)

    @Test fun alternativesAndAwayLine() {
        val main = rule("5-2-3 A", "Contra-ataque", 43, 30, 67, "web:osmcoach.com")
            .put("alts", JSONArray().put(rule("4-3-3 B", "Jogar pelas alas", 48, 62, 65, "web:osmnextlevel.com").put("away", JSONObject().put("pressure", 65).put("mentality", 63).put("tempo", 48))))
        val remote = JSONObject().put("at", System.currentTimeMillis()).put("rules", JSONObject().put("U|4-3-3", main))
        val home = Meta.remoteCandidates(-10, true, "4-3-3 A", remote)
        assertEquals(2, home.size)
        assertEquals(48, home[1].pressure)
        val away = Meta.remoteCandidates(-10, false, "4-3-3 A", remote)
        assertEquals(65, away[1].pressure)          // linha "fora" da fonte, sem desconto automático
        assertEquals(48, away[1].tempo)
        assertTrue(away[0].pressure < 43)           // sem linha "fora": desconto padrão
    }

    @Test fun historyChoosesBetweenSources() {
        val main = rule("5-2-3 A", "Contra-ataque", 43, 30, 67, "web:a")
            .put("alts", JSONArray().put(rule("4-3-3 B", "Jogar pelas alas", 48, 62, 65, "web:b")))
        val remote = JSONObject().put("at", System.currentTimeMillis()).put("rules", JSONObject().put("U|4-3-3", main))
        // 8 jogos humanos: 4-3-3 • alas rende muito acima do esperado; 5-2-3 • contra-ataque perde
        val logs = (0 until 8).map { i ->
            val good = i % 2 == 0
            JSONObject().put("human", true).put("result", if (good) "V" else "D").put("formation", if (good) "4-3-3 B" else "5-2-3 A")
                .put("playStyle", if (good) "Jogar pelas alas" else "Contra-ataque").put("mentality", 60).put("myStrength", 90).put("rivalStrength", 100).put("genAt", 1000L + i)
        }
        val t = Meta.resolve(-10, true, "4-3-3 A", remote, logs)!!
        assertEquals("4-3-3", Formations.base(t.formation))
    }

    @Test fun coherence() {
        fun t(style: String, p: Int, m: Int, te: Int, f: String = "4-3-3 A") = JSONObject().put("playStyle", style).put("pressure", p).put("mentality", m).put("tempo", te).put("formation", f).put("advDef", "Defender atrás")
        assertTrue(Why.coherenceRisks(t("Jogar pelas alas", 60, 60, 85)).any { it.contains("cruzamentos") })
        assertTrue(Why.coherenceRisks(t("Jogo de passe", 60, 60, 80)).any { it.contains("circulação") })
        assertTrue(Why.coherenceRisks(t("Contra-ataque", 40, 60, 70, "5-3-2")).any { it.contains("Contra-ataque") })
        assertTrue(Why.coherenceRisks(t("Jogar pelas alas", 65, 65, 62)).isEmpty())
    }

    @Test fun postGameTwoLines() {
        fun g(res: String, my: Int?, rv: Int?, poss: Int? = 40) = Coach.Game(5, "Rival FC", res, 0, 2, "4-3-3 A", "Jogar pelas alas", poss, 5, 12, 10, "4-5-1", null, my, rv)
        val small = PostGame.twoLines(g("D", 90, 92), 2)
        assertTrue(small.next.contains("nada")); assertTrue(small.what.contains("Derrota 0×2"))
        val gap = PostGame.twoLines(g("D", 80, 95), 6)
        assertTrue(gap.next.contains("diferença de força"))
        val mid = PostGame.twoLines(g("D", 95, 92), 6)
        assertTrue(mid.next.contains("meia") || mid.next.contains("Teste"))
    }

    @Test fun reanalysisEve() {
        val at = 10_000_000L
        assertFalse(Reanalysis.eveItem(null, at, at - 600_000).first)
        val ok = JSONObject().put("matchAt", at).put("at", at - 900_000).put("verdict", "manteve").toString()
        assertTrue(Reanalysis.eveItem(ok, at, at - 600_000).first)
        val old = JSONObject().put("matchAt", at).put("at", at - 3_600_000).put("verdict", "manteve").toString()
        assertFalse(Reanalysis.eveItem(old, at, at - 600_000).first)            // leitura de antes da janela não conta
        val mud = JSONObject().put("matchAt", at).put("at", at - 900_000).put("verdict", "mudou").put("detail", "formação 4-4-2 → 4-3-3").toString()
        assertFalse(Reanalysis.eveItem(mud, at, at - 600_000).first)
    }
}
