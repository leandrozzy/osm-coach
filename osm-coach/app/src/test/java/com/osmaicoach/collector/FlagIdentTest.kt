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
                FlagIdent.cells(p, w, (w * 0.08f).toInt(), (h * 0.10f).toInt(), w - (w * 0.08f).toInt(), h - (h * 0.10f).toInt(), 8, 5))
        }
    }
    private fun thumb(name: String): Pair<FlagIdent.Cells, FlagIdent.Cells> {
        val (p, w, h) = px(name); val x0 = (w * 0.08f).toInt(); val y0 = (h * 0.10f).toInt()
        return Pair(FlagIdent.cells(p, w, x0, y0, w - x0, h - y0, 16, 10), FlagIdent.cells(p, w, x0, y0, w - x0, h - y0, 8, 5))
    }

    @Test fun albanianFlagIsAlbaniaWithoutAtlas() {
        for (n in listOf("osm_al1.rgb", "osm_al3.rgb")) { val (a, b) = thumb(n); assertEquals(n, "al", FlagIdent.pick(a, b, refs, emptyMap())) }
    }

    @Test fun nigerianFlagIsNigeria() {
        val (a, b) = thumb("osm_ng.rgb"); assertEquals("ng", FlagIdent.pick(a, b, refs, emptyMap()))
    }

    @Test fun wrongAtlasEntryCannotTurnAlbaniaIntoTurkey() {
        // o Atlas aprendeu a bandeira da Albânia sob o nome "Turquia" (linha vizinha da lista): não pode mandar
        val (a, b) = thumb("osm_al1.rgb")
        assertEquals("al", FlagIdent.pick(a, b, refs, mapOf("tr" to listOf(b))))
    }
}
