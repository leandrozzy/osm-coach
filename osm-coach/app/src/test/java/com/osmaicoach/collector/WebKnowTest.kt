package com.osmaicoach.collector

import org.junit.Assert.*
import org.junit.Test

class WebKnowTest {
    private val lib = "Overview The values below are robust. 02 6.1 Dominant 4-3-3 A Wing Play Use when: clear favorite, especially against narrow or defensive shapes. " +
        "Setting Value Formation 4-3-3 A Game plan Wing Play Forwards Attack Only Midfield Stay in Position Defence Defend Deep; Attacking Full-backs only if clearly dominant " +
        "Pressing 65–75 Style 65–75 Tempo 60–75 Marking Zonal Offside No by default Risk: feeding a counterattack by pushing both full-backs. " +
        "04 6.4 4-5-1 Shoot on Sight Use when: equal/slightly weaker against 4-3-3 or another attacking side. Setting Value Formation 4-5-1 Game plan Shoot on Sight " +
        "Forwards Attack Only or Support Midfield Midfield Stay in Position; Protect Defence if clearly weaker Defence Defend Deep Pressing 40–55 Style 30–50 Tempo 55–70 " +
        "Marking Zonal Offside No Risk: possession and shots can remain sterile. " +
        "07 6.7 5-2-3 fast counter Use when: clear underdog against an aggressive 4-3-3 and your forwards are better. Setting Value Formation 5-2-3 A or B Game plan Counter Attack " +
        "Forwards Attack Only Midfield Protect Defence Defence Defend Deep Pressing 35–52 Style 20–40 Tempo 60–75 Marking Zonal Offside No Choose A when. " +
        "11 6.10 4-2-4 direct chase Use when: goals are mandatory and the risk no longer applies. Setting Value Formation 4-2-4 A/B Game plan Long Ball Forwards Attack Only " +
        "Midfield Push Forward Defence Defend Deep Pressing 60–75 Style 75–90 Tempo 70–85 Marking Zonal Offside No Risk: surrender. Continue with context"

    @Test fun libraryParsesRules() {
        val c = WebKnow.parseLibrary(lib)
        val f = c.first { it.key == "F|?" }.rule
        assertEquals("4-3-3 A", f.getString("formation")); assertEquals("Jogar pelas alas", f.getString("style"))
        assertEquals(70, f.getInt("pressure")); assertEquals("Atacar apenas", f.getString("attack")); assertEquals("Defender atrás", f.getString("def"))
        assertEquals("Manter posições", f.getString("mid")); assertEquals("À zona", f.getString("marking")); assertEquals("Não", f.getString("offside"))
        // 4-5-1 vale para "parelho" e "mais fraco" contra 4-3-3
        assertTrue(c.any { it.key == "E|4-3-3" && it.rule.getString("formation") == "4-5-1" })
        assertTrue(c.any { it.key == "U|4-3-3" && it.rule.getString("formation") == "4-5-1" })
        assertEquals("Atacar apenas", c.first { it.key == "E|4-3-3" }.rule.getString("attack"))
        // 5-2-3: azarão contra 4-3-3, variante A, contra-ataque, meio protege a defesa
        val u = c.first { it.key == "U|4-3-3" && it.rule.getString("formation") == "5-2-3 A" }.rule
        assertEquals("Contra-ataque", u.getString("style")); assertEquals("Ajudar a defesa", u.getString("mid"))
        // desespero (gols obrigatórios) não vira regra
        assertTrue(c.none { it.rule.getString("formation").startsWith("4-2-4") })
        assertTrue(c.first { it.key == "F|?" }.rule.optString("risk").startsWith("feeding"))
    }

    @Test fun counterPage() {
        val t = "How to Counter 4-3-3 A in OSM Situation Formation Game plan Pressing / style / tempo Evidence Stronger 4-3-3 B Passing Game 55–65 / 55–65 / 50–65 supported · medium " +
            "Approximately equal 4-5-1 Shoot on Sight 40–55 / 30–50 / 55–70 supported · medium Weaker 5-2-3 A Counter Attack 35–52 / 20–40 / 60–75 supported · medium These are"
        val libMap = WebKnow.parseLibrary(lib).associate { it.rule.getString("formation") to it.rule }
        val c = WebKnow.parseCounter(t, libMap)
        assertEquals(3, c.size)
        val w = c.first { it.key == "U|4-3-3" }.rule
        assertEquals("5-2-3 A", w.getString("formation")); assertEquals(43, w.getInt("pressure")); assertEquals(30, w.getInt("mentality")); assertEquals("Ajudar a defesa", w.getString("mid"))
        assertEquals("Jogo de passe", c.first { it.key == "F|4-3-3" }.rule.getString("style"))
    }

    @Test fun nextLevelPage() {
        val t = "How to Counter 4-3-3 A Wing Play Counter-Tactic Table Location Squad Strength Recommended Formation Pressure Style Tempo " +
            "Home Stronger 4-3-3 A (Passing Game) 70 74 62 Home Equal 4-3-3 B (Wing Play) 67 70 54 Home Weaker 4-3-3 B (Wing Play) 48 62 65 " +
            "Away Stronger 4-3-3 B (Wing Play) 70 74 65 Away Equal 4-3-3 B (Wing Play) 68 69 54 Away Weaker 4-3-3 B (Wing Play) 65 63 48 Frequently"
        val c = WebKnow.parseNextLevel(t, emptyMap())
        assertEquals(3, c.size)
        val u = c.first { it.key == "U|4-3-3" }.rule
        assertEquals("4-3-3 B", u.getString("formation")); assertEquals(48, u.getInt("pressure")); assertEquals(63, u.getJSONObject("away").getInt("mentality"))
        assertEquals("Jogo de passe", c.first { it.key == "F|4-3-3" }.rule.getString("style"))
    }

    @Test fun mergeKeepsAlternatives() {
        val a = WebKnow.parseCounter("How to Counter 4-3-3 A Weaker 5-2-3 A Counter Attack 35–52 / 20–40 / 60–75 supported · medium", emptyMap())
        val b = WebKnow.parseNextLevel("How to Counter 4-3-3 A Home Weaker 4-3-3 B (Wing Play) 48 62 65", emptyMap())
        val m = WebKnow.mergeCands(a + b).getJSONObject("U|4-3-3")
        assertEquals(1, m.getJSONArray("alts").length())
    }

    @Test fun bands() {
        val c = WebKnow.parseBandsCoach("Formation 4-3-3 A Game plan Wing Play Pressing 70 ( 65–75 ) Style 70 ( 65–75 ) Tempo 67 ( 60–75 ) Marking Zonal")!!
        assertEquals("4-3-3 A", c.first); assertEquals(60, c.second.getJSONArray("tempo").getInt(0))
        val a = WebKnow.parseBandsApp("Best 4-3-3 A tactic in OSM 3 min Style: 55-70. Tempo: 50-70. Pressing: 46-60 unless")!!
        assertEquals(46, a.second.getJSONArray("pressure").getInt(0))
    }

    @Test fun flags() {
        assertEquals("🇧🇷", Flags.emojiOf("Brasileiro")); assertEquals("🇩🇪", Flags.emojiOf("Alemão")); assertEquals("🇩🇪", Flags.emojiOfCompetition("Alemanha, 3ª Divisão"))
        assertEquals("🇦🇹", Flags.emojiOfCompetition("Áustria 2ª Div")); assertEquals("🇪🇸", Flags.emojiOf("Espanhol ou Português"))
        assertEquals("🇬🇼", Flags.emojiOfCompetition("Guiné-Bissau, Liga")); assertNull(Flags.emojiOf("Marciano")); assertNull(Flags.emojiOfCompetition("Liga Mundial")); assertEquals("🇺🇿", Flags.emojiOfCompetition("Superliga Uzbeque Uzm")); assertNull(Flags.emojiOfCompetition("Premier League"))
        assertEquals("🇨🇭", Flags.emojiOf("Suíço")); assertTrue(Flags.emojiOf("Inglês")!!.length > 4)
    }
}
