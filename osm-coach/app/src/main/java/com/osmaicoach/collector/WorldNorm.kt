package com.osmaicoach.collector

import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

/**
 * Base Mundial — normalização e semelhança de nomes (puro, testável). Preserva a grafia original (quem chama guarda o texto bruto); aqui só se calcula a CHAVE de comparação.
 * Exemplos cobertos nos testes: João Félix/Joao Felix, Kylian Mbappé/Mbappe, Red Bull Bragantino/RB Bragantino, Manchester United/Man United, Bayern München/Bayern Munich.
 */
object WorldNorm {
    private val MARKS = Regex("\\p{M}+")
    private val NON_ALNUM = Regex("[^a-z0-9]+")

    /** Minúsculo, sem acento, letras especiais expandidas, só letras/números separados por 1 espaço. */
    fun fold(s: String?): String {
        if (s.isNullOrBlank()) return ""
        var t = s.replace("ß", "ss").replace("ø", "o").replace("Ø", "O").replace("æ", "ae").replace("Æ", "AE").replace("œ", "oe").replace("đ", "d").replace("Đ", "D").replace("ł", "l").replace("Ł", "L").replace("ı", "i").replace("þ", "th")
        t = Normalizer.normalize(t, Normalizer.Form.NFD).replace(MARKS, "").lowercase()
        return NON_ALNUM.replace(t, " ").trim()
    }

    fun tokens(s: String?): List<String> = fold(s).split(' ').filter { it.isNotEmpty() }

    // ---------------------------------------------------------------- clubes
    /** Palavras sem valor para identificar o clube (forma jurídica/prefixo): saem da chave, mas só se sobrar nome. */
    private val CLUB_STOP = setOf("fc", "cf", "sc", "sk", "sv", "ac", "afc", "club", "clube", "football", "futbol", "futebol", "fk", "ks", "nk", "if", "bk", "cd", "ud", "sd", "ca", "cs", "de", "da", "do", "del", "der", "the", "ssc", "as", "cfc", "rcd", "ud", "gnk", "hnk", "kf", "fc.")
    private val CLUB_SYN = mapOf(
        "munchen" to "munich", "muenchen" to "munich", "man" to "manchester", "utd" to "united", "intl" to "international", "inter" to "inter",
        "internazionale" to "inter", "koln" to "cologne", "wien" to "vienna", "napoli" to "napoli", "lisboa" to "lisbon", "sevilla" to "seville"
    )
    private val ROMAN = mapOf("ii" to "II", "iii" to "III", "iv" to "IV", "b" to "B", "c" to "C", "u17" to "U17", "u18" to "U18", "u19" to "U19", "u20" to "U20", "u21" to "U21", "u23" to "U23",
        "reserves" to "RES", "reserve" to "RES", "youth" to "YTH", "juniors" to "JUN", "amateure" to "AMA", "amateurs" to "AMA", "women" to "W", "femenino" to "W", "feminino" to "W", "ladies" to "W", "frauen" to "W", "fem" to "W", "2" to "II", "3" to "III")

    class ClubKey(val core: String, val marker: String)

    /** Chave do clube: núcleo (sem FC/SC…, com sinônimos) + marcador de categoria (II, B, U19, feminino…). Hoffenheim ≠ Hoffenheim II. */
    fun clubKey(name: String?): ClubKey {
        var tk = tokens(name).toMutableList()
        // "RB" = Red Bull; "Man" só como 1ª palavra = Manchester
        tk = tk.flatMap { if (it == "rb") listOf("red", "bull") else listOf(it) }.toMutableList()
        val marker = StringBuilder()
        while (tk.size > 1 && ROMAN[tk.last()] != null) marker.insert(0, ROMAN[tk.removeAt(tk.size - 1)]!!)
        val mapped = tk.mapIndexed { i, w -> if (w == "man" && i != 0) w else CLUB_SYN[w] ?: w }
        val core = mapped.filter { it !in CLUB_STOP }.ifEmpty { mapped }
        return ClubKey(core.joinToString(" "), marker.toString())
    }

    // ---------------------------------------------------------------- jogadores
    class PlayerKey(val full: String, val surname: String, val initial: String, val given: List<String>)

    /** Nome do jogador: chave completa, sobrenome (última palavra; "van/de/da…" colam no sobrenome) e inicial do 1º nome ("A. Kurti" → a). */
    fun playerKey(name: String?): PlayerKey {
        val tk = tokens(name)
        if (tk.isEmpty()) return PlayerKey("", "", "", emptyList())
        val particles = setOf("van", "von", "de", "da", "do", "dos", "das", "del", "della", "di", "der", "den", "le", "la", "el", "al", "bin", "ben", "ibn", "mac", "mc", "st", "ter", "ten")
        var si = tk.size - 1
        while (si > 0 && tk[si - 1] in particles) si--
        val surname = tk.subList(si, tk.size).joinToString(" ")
        val given = tk.subList(0, si)
        return PlayerKey(tk.joinToString(" "), surname, given.firstOrNull()?.take(1) ?: "", given)
    }

    // ---------------------------------------------------------------- semelhança
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    fun levRatio(a: String, b: String): Double { val m = max(a.length, b.length); return if (m == 0) 1.0 else 1.0 - levenshtein(a, b).toDouble() / m }

    fun jaroWinkler(s1: String, s2: String): Double {
        if (s1 == s2) return if (s1.isEmpty()) 0.0 else 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        val md = max(0, max(s1.length, s2.length) / 2 - 1)
        val m1 = BooleanArray(s1.length); val m2 = BooleanArray(s2.length)
        var matches = 0
        for (i in s1.indices) {
            val lo = max(0, i - md); val hi = min(s2.length - 1, i + md)
            for (j in lo..hi) if (!m2[j] && s1[i] == s2[j]) { m1[i] = true; m2[j] = true; matches++; break }
        }
        if (matches == 0) return 0.0
        var t = 0; var k = 0
        for (i in s1.indices) if (m1[i]) { while (!m2[k]) k++; if (s1[i] != s2[k]) t++; k++ }
        val m = matches.toDouble()
        val jaro = (m / s1.length + m / s2.length + (m - t / 2.0) / m) / 3.0
        var l = 0
        while (l < min(4, min(s1.length, s2.length)) && s1[l] == s2[l]) l++
        return jaro + l * 0.1 * (1 - jaro)
    }

    /** Fonética simples (Soundex adaptado, só para desempate de grafias parecidas: "Mohamed/Muhammad"). */
    fun phonetic(s: String): String {
        val f = fold(s).replace(" ", "")
        if (f.isEmpty()) return ""
        val codes = "01230120022455012623010202"   // a..z
        val sb = StringBuilder().append(f[0])
        var last = codes[f[0] - 'a'].takeIf { f[0] in 'a'..'z' } ?: '0'
        for (c in f.drop(1)) {
            if (c !in 'a'..'z') continue
            val cd = codes[c - 'a']
            if (cd != '0' && cd != last) sb.append(cd)
            last = cd
            if (sb.length >= 5) break
        }
        return sb.toString().padEnd(5, '0')
    }

    /** Semelhança 0..100 entre duas chaves de nome (maior entre Jaro-Winkler, Levenshtein e sobreposição de palavras). */
    fun nameSim(a: String, b: String): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        if (a == b) return 100
        val ta = a.split(' ').toSet(); val tb = b.split(' ').toSet()
        val inter = ta.intersect(tb).size.toDouble()
        val tokenSet = if (ta.isEmpty() || tb.isEmpty()) 0.0 else inter / max(ta.size, tb.size)
        val sorted = jaroWinkler(a.split(' ').sorted().joinToString(" "), b.split(' ').sorted().joinToString(" "))
        return (100 * max(max(jaroWinkler(a, b), levRatio(a, b)), max(tokenSet, sorted * 0.97))).toInt().coerceIn(0, 100)
    }
}
