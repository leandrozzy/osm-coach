package com.osmaicoach.collector

import org.junit.Assert.*
import org.junit.Test

/** Bandeiras REAIS do OSM (recortes do print de 25/11/26: Albânia e Nigéria) contra as bandeiras do Flagpedia (flagcdn). */
class FlagIdentTest {
    private fun px(name: String): Triple<IntArray, Int, Int> {
        val b = javaClass.getResourceAsStream("/flags/$name")!!.readBytes()
        val w = b[0].toInt() and 0xFF; val h = b[1].toInt() and 0xFF
        val out = IntArray(w * h) { i -> ((b[2 + i * 3].toInt() and 0xFF) shl 16) or ((b[3 + i * 3].toInt() and 0xFF) shl 8) or (b[4 + i * 3].toInt() and 0xFF) }
        return Triple(out, w, h)
    }
    private val refs: List<FlagIdent.Ref> by lazy {
        val names = java.io.File(javaClass.getResource("/flags/al.rgb")!!.toURI()).parentFile.listFiles()!!.filter { it.name.length == 6 && it.name.endsWith(".rgb") }
        names.map { f ->
            val (p, w, h) = px(f.name); val mx = (w * 0.03f).toInt(); val my = (h * 0.04f).toInt()
            FlagIdent.Ref(f.name.removeSuffix(".rgb"), FlagIdent.cells(p, w, mx, my, w - mx, h - my, 16, 10),
                FlagIdent.cells(p, w, (w * 0.08f).toInt(), (h * 0.10f).toInt(), w - (w * 0.08f).toInt(), h - (h * 0.10f).toInt(), 8, 5), FlagIdent.labGrid(p, w, mx, my, w - mx, h - my, 24, 16))
        }
    }
    private fun pickOf(name: String): String? {
        val (p, w, h) = px(name); val x0 = (w * 0.08f).toInt(); val y0 = (h * 0.10f).toInt()
        return FlagIdent.pick(FlagIdent.cells(p, w, x0, y0, w - x0, h - y0, 16, 10), FlagIdent.labGrid(p, w, x0, y0, w - x0, h - y0, 24, 16), refs)
    }

    /** Bandeiras REAIS do OSM (recortes dos prints de 25/11/26) e o país certo. */
    private val truth = mapOf(
        "osm_al1" to "al", "osm_al3" to "al", "osm_al_myrta" to "al", "osm_al_kurti" to "al", "osm_ml_traore" to "ml", "osm_ci_doukouo" to "ci",
        "osm_ng" to "ng", "osm_ng_muhammad" to "ng", "osm_ng_ogah" to "ng", "osm_lb_trad" to "lb", "osm_tr_yagmur" to "tr", "osm_ar_gamarra" to "ar", "osm_xk_azizi" to "xk"
    )

    @Test fun realOsmFlagsAreIdentifiedCorrectly() {
        val wrong = truth.filter { (n, iso) -> pickOf("$n.rgb") != iso }.map { (n, iso) -> "$n queria $iso" }
        assertTrue("erradas: $wrong", wrong.isEmpty())
    }

    @Test fun lookAlikeFlagsAreNotMixedUp() {
        // pares que o Atlas (aprendido do OSM) trocava no aparelho: Albânia×Turquia, Mali×Senegal, Argentina×El Salvador
        assertEquals("ml", pickOf("osm_ml_traore.rgb"))
        assertEquals("ar", pickOf("osm_ar_gamarra.rgb"))
        assertEquals("al", pickOf("osm_al1.rgb"))
        assertEquals("tr", pickOf("osm_tr_yagmur.rgb"))
    }
}
