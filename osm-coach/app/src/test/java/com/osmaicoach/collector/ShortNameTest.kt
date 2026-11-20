package com.osmaicoach.collector

import org.junit.Assert.*
import org.junit.Test

class ShortNameTest {
    @Test fun twoLetterDefenderIsRead() {
        val rows = ArrayList<OcrLine>()
        rows += Fx.line("Liefering", 0.05f, 0.228f, 0.05f)
        rows += Fx.line("Posição: 4", 0.08f, 0.149f, 0.1f)
        rows += Fx.line("Defesas", 0.5f, 0.30f, 0.08f)
        fun row(y: Float, name: String, age: String, pos: String, a: String, d: String, m: String, v: String) {
            rows += Fx.line("23", 0.02f, y, 0.02f)
            rows += Fx.line(name, 0.07f, y); rows += Fx.line(age, 0.555f, y, 0.03f); rows += Fx.line(pos, 0.62f, y, 0.03f)
            rows += Fx.line(a, 0.663f, y, 0.025f); rows += Fx.line(d, 0.70f, y, 0.025f); rows += Fx.line(m, 0.737f, y, 0.025f)
            rows += Fx.line(v, 0.95f, y, 0.04f)
        }
        row(0.40f, "Ibañez", "27", "DC", "18", "81", "49", "6,4M")
        row(0.52f, "Ba", "21", "DC", "14", "80", "47", "6,0M")
        val ex = Parsers.squad(Fx.ocr(*rows.toTypedArray()), null)
        assertEquals(listOf("Ibañez", "Ba"), ex.players.map { it.name })
    }

    @Test fun digitForLetterInShortName() {
        assertEquals("Ba", PlayerName.clean("8a", 2))
        assertNull(PlayerName.clean("8a"))
    }
}
