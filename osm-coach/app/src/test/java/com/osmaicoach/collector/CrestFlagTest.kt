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

    @Test fun placeholderIsNotACrest() {
        val dark = IntArray(2500) { (0xFF shl 24) or (22 shl 16) or (45 shl 8) or 95 }        // fundo azul do estádio com "?" apagado
        assertFalse(CrestCut.looksLikeCrest(dark))
        val crest = IntArray(2500) { i -> if (i % 4 == 0) (0xFF shl 24) or (200 shl 16) or (30 shl 8) or 30 else (0xFF shl 24) or (22 shl 16) or (45 shl 8) or 95 }
        assertTrue(CrestCut.looksLikeCrest(crest))                                           // 25% de vermelho vivo
    }

    @Test fun flagBoxCorrectsOffset() {
        val w = 51; val h = 35
        val px = IntArray(w * h) { (0xFF shl 24) or (255 shl 16) or (255 shl 8) or 255 }      // fundo branco da linha
        for (y in 5..28) for (x in 12..46) px[y * w + x] = (0xFF shl 24) or (200 shl 16) or (30 shl 8) or 30   // bandeira 35×24 deslocada p/ a direita
        val b = Flags.flagBox(px, w, h, 34, 24)!!
        assertEquals(12, b[0]); assertEquals(46, b[2]); assertEquals(5, b[1]); assertEquals(28, b[3])
        assertNull(Flags.flagBox(px, w, h, 20, 24))                                         // largura fora do esperado: não acha
    }

    @Test fun copperRimIsFoundToo() {
        val w = 110; val h = 100
        val px = IntArray(w * h) { rgb(15, 30, 70) }
        for (y in 45..90) for (x in 25..75) if (x < 29 || x > 71 || y > 86) px[y * w + x] = rgb(190, 100, 45)   // cobre: matiz ≈ 24°
        val b = CrestCut.goldBox(px, w, h, 55)!!
        assertEquals(25, b[0]); assertEquals(75, b[2]); assertEquals(90, b[3])
    }

    @Test fun scoreTextIsNeverACrest() {
        val w = 110; val h = 100
        val px = IntArray(w * h) { rgb(20, 38, 90) }
        for (y in 40..60) for (x in 20..90) if ((x / 6) % 2 == 0) px[y * w + x] = rgb(250, 250, 250)   // "0-3" em branco sobre o card azul
        assertNull(CrestCut.goldBox(px, w, h, 55))
        assertNull(CrestCut.contrastBox(px, w, h, 55, 60))                                          // largo e baixo: não tem proporção de escudo
        assertFalse(CrestCut.looksLikeCrest(px))
    }

    @Test fun silverCrestFoundByContrast() {
        val w = 110; val h = 100
        val px = IntArray(w * h) { rgb(15, 30, 70) }
        for (y in 20..80) for (x in 25..80) px[y * w + x] = rgb(230, 230, 235)
        val b = CrestCut.contrastBox(px, w, h, 56, 61)!!
        assertEquals(25, b[0]); assertEquals(80, b[2]); assertEquals(20, b[1]); assertEquals(80, b[3])
    }

    @Test fun nomeCortadoCasaComNomeCompleto() {
        assertTrue(CrestCut.score(Txt.key("Young Violets"), Txt.key("Young Violets Austria Wien")) >= 0.85)
        assertTrue(CrestCut.score(Txt.key("SK Sturm"), Txt.key("SK Rapid")) < 0.85)
    }

    @Test fun anelAzulMarinhoAchaEscudoDeQualquerMoldura() {
        val w = 120; val h = 120
        val px = IntArray(w * h) { (0xFF shl 24) or (60 shl 16) or (110 shl 8) or 140 }
        val navy = (0xFF shl 24) or (15 shl 16) or (25 shl 8) or 60
        // anel 61x64 (como o quadro real do hub: 60x63), moldura PRATEADA por fora (sem cor) e mancha escura no canto (HUD)
        for (y in 25..88) for (x in 30..90) if (x < 34 || x > 86 || y < 29 || y > 84) px[y * w + x] = navy
        for (y in 0..12) for (x in 0..9) px[y * w + x] = navy
        val b = CrestCut.ringBox(px, w, h, 68)!!
        assertEquals(26.0, b[0].toDouble(), 1.0); assertEquals(94.0, b[2].toDouble(), 1.5); assertEquals(19.0, b[1].toDouble(), 1.5); assertEquals(94.0, b[3].toDouble(), 1.5)
    }

    @Test fun clubeDaWebCasaPeloNome() {
        assertTrue(CrestWeb.sameClub("Red Bull Bragantino", "Bragantino"))
        assertTrue(CrestWeb.sameClub("Liefering", "FC Liefering"))
        assertTrue(CrestWeb.sameClub("Young Violets Austria Wien", "Young Violets Austria Wien"))
        assertTrue(CrestWeb.sameClub("Fortuna Düsseldorf", "Fortuna Dusseldorf"))
        assertFalse(CrestWeb.sameClub("Hertha Wels", "Hertha BSC"))
        assertFalse(CrestWeb.sameClub("SKU Amstetten", "SK Sturm Graz"))
    }

    @Test fun miolo8x5DaBandeiraTemCoresPorFaixa() {
        // 48x32: moldura branca de 3 px e miolo em 3 faixas horizontais (preto, vermelho, amarelo) como a Alemanha
        val w = 48; val h = 32
        val px = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        for (y in 3 until 29) for (x in 3 until 45) px[y * w + x] = when { y < 11 -> 0xFF000000.toInt(); y < 21 -> 0xFFDD0000.toInt(); else -> 0xFFFFCE00.toInt() }
        val g = Atlas.gridOf(px, w, (w * 0.08f).toInt(), (h * 0.10f).toInt(), w - (w * 0.08f).toInt(), h - (h * 0.10f).toInt())
        assertEquals(240, g.length)
        assertEquals("000000", g.substring(0, 6))           // 1ª linha: preto
        assertEquals("ffce00", g.substring(4 * 8 * 6, 4 * 8 * 6 + 6))   // última linha: amarelo
    }

    @Test fun repositorioEscolheArquivoPeloNome() {
        val paths = listOf("logos/austria/Amstetten.svg", "logos/austria/ASK_Voitsberg.svg", "logos/austria/SW_Bregenz.svg", "logos/austria/Austria_National_Team.svg",
            "logos/brazil/RB_Bragantino.svg", "logos/germany/SV_Waldhof_Mannheim.svg", "logos/germany/Fortuna_Düsseldorf.svg", "logos/england/Sporting_X.svg", "logos/portugal/Sporting_X.svg")
        assertEquals("logos/austria/Amstetten.svg", CrestWeb.pickPath("SKU Amstetten", paths))
        assertEquals("logos/austria/ASK_Voitsberg.svg", CrestWeb.pickPath("ASK Voitsberg", paths))
        assertEquals("logos/brazil/RB_Bragantino.svg", CrestWeb.pickPath("Red Bull Bragantino", paths))
        assertEquals("logos/germany/SV_Waldhof_Mannheim.svg", CrestWeb.pickPath("Waldhof Mannheim", paths))
        assertEquals("logos/germany/Fortuna_Düsseldorf.svg", CrestWeb.pickPath("Fortuna Düsseldorf", paths))
        assertNull(CrestWeb.pickPath("Sporting X", paths))            // mesmo nome em 2 países: ambíguo
        assertNull(CrestWeb.pickPath("Austria", paths))               // seleção nacional não é clube
    }

    @Test fun nomeDoPaisEmVezDoGentilico() {
        assertEquals("Brasil", Flags.countryLabel("Brasileiro"))
        assertEquals("Noruega", Flags.countryLabel("Norueguês"))
        assertEquals("Alemanha", Flags.countryLabel("Alemão"))
        assertEquals("Inglaterra", Flags.countryLabel("Inglês"))
        assertEquals("Costa Rica", Flags.countryLabel("Costa-riquenho"))
        assertEquals("Samoa", Flags.countryLabel("Samoano"))
        assertEquals("fr", Flags.iso("Guadalupino")?.let { "fr" })   // território conhecido (gp), não nulo
    }

    @Test fun corNomeadaDistingueFrancaDeIrlandaMesmoComBrilhoDiferente() {
        // França do OSM (azul claro brilhante | branco | vermelho vivo) × França real (azul marinho | branco | vermelho) × Irlanda (verde | branco | laranja)
        fun tri(a: Int, b: Int, c: Int): FlagIdent.Cells {
            val px = IntArray(48 * 32) { i -> when ((i % 48) / 16) { 0 -> a; 1 -> b; else -> c } }
            return FlagIdent.cells(px, 48, 0, 0, 48, 32, 16, 10)
        }
        val osmFr = tri(0xFF3C50BE.toInt(), 0xFFFFFFFF.toInt(), 0xFFEA3A2A.toInt())
        val fr = tri(0xFF002654.toInt(), 0xFFFFFFFF.toInt(), 0xFFCE1126.toInt())
        val ie = tri(0xFF169B62.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF883E.toInt())
        assertTrue(FlagIdent.dist(osmFr, fr) < FlagIdent.dist(osmFr, ie))
    }

    @Test fun variacoesDoNomeDoClube() {
        val v = CrestWeb.variants("Schwarz-Weiß Bregenz")
        assertTrue(v.any { it == "Schwarz-Weiss Bregenz" })
        val w = CrestWeb.variants("SKU Amstetten")
        assertTrue(w.any { it == "Amstetten" })
        val x = CrestWeb.variants("Mirassol FC (SP)")
        assertTrue(x.any { it == "Mirassol FC" })
        val y = CrestWeb.variants("Red Bull Bragantino", alias = "Bragantino")
        assertEquals("Bragantino", y.first())
        assertTrue(y.any { it == "RB Bragantino" })
    }

    @Test fun fasesDaCopaEVagasAindaNaoDecididasNaoSaoClubes() {
        for (n in listOf("Fase preliminar", "Oitavos-de-final", "Quartos de final", "Meias finais", "Final", "ASD", "ANNIY", "ANNYEA")) assertTrue(n, Fixtures.notAClub(n))
        for (n in listOf("Flamengo", "Floridsdorfer AC", "Annecy FC", "Fortuna Düsseldorf", "Young Violets Austria Wien")) assertFalse(n, Fixtures.notAClub(n))
    }
}
