package com.osmaicoach.collector

/**
 * Nomes de liga do ATLAS (aprendidos por OCR dos filtros do OSM) vêm com erros ("22 Divisão A dos EUA", "2a DivisãoB dos EUA", "Brasil, 2 Divisão"): o "ª" é lido como "2", "9", "a"…
 * Aqui cada nome vira (país, divisão, subgrupo) e as variações do mesmo nome se juntam numa só linha. Só para EXIBIR: o Atlas guardado não é alterado.
 */
object LeagueNames {
    class Canon(val countryText: String, val tier: Int, val sub: String?) {
        val key get() = "${WorldNorm.fold(countryText)}|$tier|${sub ?: ""}"
        val tierLabel get() = "${tier}ª divisão" + (sub?.let { " $it" } ?: "")
    }

    private val SUBFORM = Regex("""^(.+?)\s*,\s*(\d{1,2})\s*[ªaAºo°]?\s*divis[ãa]o\s*([A-Za-z])?\s*$""", RegexOption.IGNORE_CASE)
    private val PREFORM = Regex("""^(\d{1,2})\s*[ªaAºo°]?\s*divis[ãa]o\s*([A-Za-z])?\s+(?:d[aoe]s?)\s+(.+)$""", RegexOption.IGNORE_CASE)

    /** "22"/"29" = 2 + "ª" lida como dígito: acima de 12 divisões não existe, então vale o primeiro dígito. */
    private fun tierOf(n: String): Int? = n.toIntOrNull()?.let { if (it > 12) n.take(1).toInt() else it }?.takeIf { it in 1..12 }

    /** `isCountry` diz se o texto é um país conhecido (nome sozinho = 1ª divisão do país). */
    fun canon(raw: String, isCountry: (String) -> Boolean): Canon? {
        val s = raw.trim().replace(Regex("\\s+"), " ")
        SUBFORM.find(s)?.let { m -> val t = tierOf(m.groupValues[2]) ?: return null; return Canon(m.groupValues[1].trim(), t, m.groupValues[3].uppercase().takeIf { it.isNotEmpty() }) }
        PREFORM.find(s)?.let { m -> val t = tierOf(m.groupValues[1]) ?: return null; return Canon(m.groupValues[3].trim(), t, m.groupValues[2].uppercase().takeIf { it.isNotEmpty() }) }
        if (isCountry(s)) return Canon(s, 1, null)
        return null
    }

    /** Apelidos comuns de país nas listas do jogo (EUA etc.) para achar o ISO. */
    val ALIAS = mapOf("eua" to "us", "estados unidos" to "us", "estados unidos da america" to "us", "reino unido" to "gb", "russia" to "ru", "india" to "in", "coreia do sul" to "kr", "emirados arabes unidos" to "ae")

    class Group(val canon: Canon, val variants: List<String>)
    /** Junta as variações do mesmo (país, divisão, subgrupo); o que não se encaixa em nenhum padrão vai em `rest`. */
    fun group(names: Collection<String>, isCountry: (String) -> Boolean): Pair<List<Group>, List<String>> {
        val map = LinkedHashMap<String, MutableList<Pair<Canon, String>>>(); val rest = ArrayList<String>()
        for (n in names) { val c = canon(n, isCountry); if (c == null) rest.add(n) else map.getOrPut(c.key) { ArrayList() }.add(c to n) }
        return Pair(map.values.map { Group(it.first().first, it.map { p -> p.second }) }.sortedWith(compareBy({ WorldNorm.fold(it.canon.countryText) }, { it.canon.tier }, { it.canon.sub ?: "" })), rest.sorted())
    }
}
