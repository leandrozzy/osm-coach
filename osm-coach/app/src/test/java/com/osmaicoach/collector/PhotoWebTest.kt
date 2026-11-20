package com.osmaicoach.collector

import org.junit.Assert.*
import org.junit.Test

class PhotoWebTest {
    private fun c(id: Int, name: String, first: String, last: String, age: Int?, nat: String?) =
        PhotoWeb.Cand(id, name, first, last, age, nat, "https://media.api-sports.io/football/players/$id.png")

    @Test fun termIsLongestWordWithoutAccent() {
        assertEquals("hudson", PhotoWeb.searchTerm("Hudson-Odoi"))
        assertEquals("marquinhos", PhotoWeb.searchTerm("Marquinhos"))
        assertNull(PhotoWeb.searchTerm("Ba"))
        assertEquals("ven", PhotoWeb.searchTerm("van de Ven"))     // partícula não vira termo (antes dava "van" e 0 resultados)
        assertEquals("dijk", PhotoWeb.searchTerm("van Dijk"))
        assertEquals("paz", PhotoWeb.searchTerm("N. Paz"))
    }

    @Test fun oneCandidateWithNameAndAgeIsAccepted() {
        val r = PhotoWeb.pick("Van Dijk", 35, "nl", listOf(c(1, "V. van Dijk", "Virgil", "van Dijk", 35, "Netherlands"), c(2, "J. Dijk", "John", "Dijk", 20, "England")))
        assertEquals("ok", r.state); assertEquals(1, r.cand!!.id)
    }

    @Test fun wrongAgeIsNotAccepted() {
        val r = PhotoWeb.pick("Stones", 32, "gb", listOf(c(1, "J. Stones", "John", "Stones", 19, "England")))
        assertEquals("miss", r.state); assertNull(r.cand)
    }

    @Test fun namesakesAreSplitByNationalityElseAmbiguous() {
        val a = c(1, "R. Silva", "Rafael", "Silva", 31, "Brazil"); val b = c(2, "A. Silva", "Andre", "Silva", 31, "Portugal")
        assertEquals(1, PhotoWeb.pick("Silva", 31, "br", listOf(a, b)).cand!!.id)
        assertEquals("amb", PhotoWeb.pick("Silva", 31, null, listOf(a, b)).state)
    }

    @Test fun dailyLimitIsDetected() {
        val (l, daily, minute) = PhotoWeb.parseCands("""{"errors":{"requests":"You have reached the request limit for the day"},"results":0,"response":[]}""")
        assertTrue(daily); assertFalse(minute); assertTrue(l.isEmpty())
        val ok = PhotoWeb.parseCands("""{"errors":[],"results":1,"response":[{"player":{"id":9,"name":"Kobel","firstname":"Gregor","lastname":"Kobel","age":28,"nationality":"Switzerland","photo":"https://media.api-sports.io/football/players/9.png"}}]}""")
        assertFalse(ok.second); assertEquals(1, ok.first.size); assertEquals("Switzerland", ok.first[0].nationality)
    }

    @Test fun bestScoreWinsWhenClearlyAheadOtherwiseAmbiguous() {
        // vários "Dijk" de idade parecida: o de nome inteiro igual e país certo vence com folga
        val v = c(1, "V. van Dijk", "Virgil", "van Dijk", 35, "Netherlands")
        val o = c(2, "S. Dijk", "Sam", "Dijk", 34, "Belgium")
        assertEquals(1, PhotoWeb.pick("van Dijk", 35, "nl", listOf(v, o)).cand!!.id)
        // dois homônimos iguais em tudo: ambíguo
        val a = c(3, "A. Silva", "Andre", "Silva", 31, "Brazil"); val b = c(4, "B. Silva", "Bruno", "Silva", 31, "Brazil")
        assertEquals("amb", PhotoWeb.pick("Silva", 31, "br", listOf(a, b)).state)
    }
}
