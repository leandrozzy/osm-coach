package com.osmaicoach.collector

import org.json.JSONArray
import org.json.JSONObject

/**
 * Dicas da comunidade (fórum oficial, Reddit, YouTube) como CANDIDATAS — nunca regra provada e nunca decidem a tática.
 * Cada dica guarda link canônico, autor/canal, data de publicação, data da ÚLTIMA verificação REAL, contexto e o trecho que a fonte de fato mostra.
 * Nada é inventado: sem link não há dica verificada; sem data a data fica "não informada"; sliders nunca são derivados de título/comentário vago;
 * vídeo sem acesso ao conteúdo só guarda título/canal (sem minuto). Atualização em segundo plano, com cache, limite de chamadas e tolerância a falha.
 */
object Tips {
    const val KIND = "tips"
    const val TTL_MS = 12L * 3_600_000L
    const val MAX_CALLS = 4
    const val STALE_DAYS = 30L

    /** Fontes de validação inicial (podem estar bloqueadas para clientes automáticos: a falha é registrada, nunca contornada). */
    val SEEDS = listOf(
        "https://support.onlinesoccermanager.com/hc/pt/articles/4406262539537-Quais-s%C3%A3o-as-melhores-t%C3%A1ticas",
        "https://www.reddit.com/r/onlinesoccermanager/comments/1xt9zpr/best_plan/",
        "https://www.reddit.com/r/onlinesoccermanager/comments/1xq96ga/i_need_help_to_win_this_game_against_bvb_i_need/",
        "https://www.youtube.com/watch?v=a0BTUH2doGM"
    )

    class Tip(
        val url: String, val source: String, val title: String?, val author: String?, val publishedAt: String?, val verifiedAt: Long,
        val formation: String?, val playStyle: String?, val context: String?, val excerpt: String?, val demonstrates: String
    ) {
        fun toJson(): JSONObject = JSONObject().put("url", url).put("source", source).put("title", title ?: JSONObject.NULL).put("author", author ?: JSONObject.NULL)
            .put("published", publishedAt ?: JSONObject.NULL).put("verifiedAt", verifiedAt).put("formation", formation ?: JSONObject.NULL)
            .put("style", playStyle ?: JSONObject.NULL).put("context", context ?: JSONObject.NULL).put("excerpt", excerpt ?: JSONObject.NULL).put("demonstrates", demonstrates)

        companion object {
            fun fromJson(j: JSONObject): Tip? {
                val url = j.optString("url").takeIf { it.startsWith("https://") } ?: return null   // sem link não existe dica verificada
                fun s(k: String) = if (j.isNull(k)) null else j.optString(k).takeIf { it.isNotBlank() }
                return Tip(url, j.optString("source"), s("title"), s("author"), s("published"), j.optLong("verifiedAt", 0L), s("formation"), s("style"), s("context"), s("excerpt"), j.optString("demonstrates"))
            }
        }
    }

    class SourceStatus(val url: String, val lastTry: Long, val lastOk: Long?, val error: String?)

    // ------------------------------------------------------------------ leitura do que a página realmente mostra
    private fun meta(html: String, vararg names: String): String? {
        for (n in names) {
            val a = Regex("<meta[^>]+(?:property|name)=[\"']" + Regex.escape(n) + "[\"'][^>]*content=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(html)
            val b = Regex("<meta[^>]+content=[\"']([^\"']*)[\"'][^>]*(?:property|name)=[\"']" + Regex.escape(n) + "[\"']", RegexOption.IGNORE_CASE).find(html)
            (a ?: b)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

    fun canonical(html: String, fallback: String): String {
        val c = Regex("<link[^>]+rel=[\"']canonical[\"'][^>]*href=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?: meta(html, "og:url")
        return (c ?: fallback).takeIf { it.startsWith("https://") } ?: fallback
    }

    /** Data só se a página a informa (meta/JSON-LD/time); senão null ("não informada"). Nunca estimada. */
    fun published(html: String): String? =
        meta(html, "article:published_time", "datePublished", "og:article:published_time")
            ?: Regex("\"datePublished\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            ?: Regex("<time[^>]+datetime=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)

    fun author(html: String): String? =
        meta(html, "author", "article:author") ?: Regex("\"author\"\\s*:\\s*\\{[^}]*\"name\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)

    private val FORM_RX = Regex("\\b([3-6]-\\d-\\d(?:-\\d)?)\\s*([ABCDabcd])?\\b")

    /** Frases (trechos literais) que citam uma formação; a formação e o estilo só entram se aparecem NA MESMA frase. Sliders nunca são lidos. */
    fun extract(text: String): List<Triple<String, String?, String>> {
        val out = ArrayList<Triple<String, String?, String>>()
        for (sentence in text.split(Regex("(?<=[.!?])\\s+"))) {
            val m = FORM_RX.find(sentence) ?: continue
            val form = Formations.parse(m.value) ?: continue
            val style = Osm.style(sentence)
            out.add(Triple(form, style, sentence.trim().take(240)))
            if (out.size >= 3) break
        }
        return out
    }

    /** Página HTML (fórum/Reddit) → dicas. `fetchedAt` = momento REAL do download. */
    fun parsePage(url: String, html: String, source: String, fetchedAt: Long): List<Tip> {
        val canon = canonical(html, url)
        val text = WebKnow.toText(html)
        val title = meta(html, "og:title") ?: Regex("<title[^>]*>([^<]+)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.trim()
        val pub = published(html); val au = author(html)
        return extract(text).map { (form, style, ex) ->
            Tip(canon, source, title, au, pub, fetchedAt, form, style, null, ex,
                if (style != null) "A página cita $form com ${style}; é candidato, sem prova de resultado" else "A página cita a formação $form; estilo não indicado na mesma frase")
        }
    }

    /** YouTube sem acesso ao conteúdo: só título/canal (oEmbed). NUNCA cita minuto, formação ou slider do vídeo. */
    fun parseVideo(url: String, oembedJson: String, fetchedAt: Long): Tip? {
        val j = runCatching { JSONObject(oembedJson) }.getOrNull() ?: return null
        val title = j.optString("title").takeIf { it.isNotBlank() } ?: return null
        val id = Regex("[?&]v=([\\w-]{6,})").find(url)?.groupValues?.get(1) ?: return null
        return Tip("https://www.youtube.com/watch?v=$id", "YouTube", title, j.optString("author_name").takeIf { it.isNotBlank() }, null, fetchedAt, null, null, null, null,
            "Só título e canal foram acessados; o conteúdo do vídeo NÃO foi visto nem transcrito")
    }

    // ------------------------------------------------------------------ armazenamento e fusão
    /** Atualização NÃO renova o que não foi verificado: quem foi baixado agora ganha nova data; o resto mantém a data antiga. Sem link nunca entra. */
    fun merge(old: List<Tip>, fetched: Map<String, List<Tip>>): List<Tip> {
        val out = LinkedHashMap<String, Tip>()
        fun key(t: Tip) = t.url + "|" + (t.formation ?: "") + "|" + (t.playStyle ?: "") + "|" + (t.excerpt ?: "").take(40)
        val refreshedUrls = fetched.keys
        for (t in old) if (t.url !in refreshedUrls && fetched.values.none { l -> l.any { it.url == t.url } }) out[key(t)] = t
        for (l in fetched.values) for (t in l) if (t.url.startsWith("https://")) out[key(t)] = t
        return out.values.toList()
    }

    suspend fun load(repo: Repo): Pair<List<Tip>, List<SourceStatus>> {
        val j = repo.dao.plan(0, KIND)?.json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return Pair(emptyList(), emptyList())
        val tips = j.optJSONArray("items")?.let { a -> (0 until a.length()).mapNotNull { Tip.fromJson(a.getJSONObject(it)) } } ?: emptyList()
        val st = j.optJSONObject("sources")?.let { o -> o.keys().asSequence().map { k -> o.getJSONObject(k).let { SourceStatus(k, it.optLong("try"), if (it.isNull("ok")) null else it.optLong("ok"), if (it.isNull("err")) null else it.optString("err")) } }.toList() } ?: emptyList()
        return Pair(tips, st)
    }

    private fun save(tips: List<Tip>, st: Map<String, SourceStatus>, now: Long) = JSONObject()
        .put("items", JSONArray(tips.map { it.toJson() }))
        .put("sources", JSONObject().also { o -> st.forEach { (k, v) -> o.put(k, JSONObject().put("try", v.lastTry).put("ok", v.lastOk ?: JSONObject.NULL).put("err", v.error ?: JSONObject.NULL)) } })
        .put("at", now).toString()

    /** Idade em dias da última verificação REAL; null se nunca. */
    fun ageDays(t: Tip, now: Long): Long? = if (t.verifiedAt <= 0L) null else (now - t.verifiedAt) / 86_400_000L
    fun stale(t: Tip, now: Long): Boolean = (ageDays(t, now) ?: Long.MAX_VALUE) > STALE_DAYS

    /** Quanto a dica apoia a tática recomendada, sem fundir A/B nem alas/passe. */
    fun support(t: Tip, tactic: Tactic): String {
        val f = t.formation ?: return "sem formação citada"
        val sameBase = Formations.base(f) == Formations.base(tactic.formation)
        val sameExact = f == tactic.formation || (f.length == Formations.base(f).length && sameBase)   // fonte sem letra não distingue A/B
        val ts = t.playStyle
        return when {
            !sameBase -> "outra formação ($f)"
            !sameExact -> "formação base igual, mas a fonte indica $f e a recomendada é ${tactic.formation}"
            ts == null -> "mesma formação; estilo não indicado"
            ts != tactic.playStyle -> "mesma formação, estilo diferente ($ts)"
            else -> "mesma formação e estilo"
        }
    }

    /** Atualiza em segundo plano: no máximo [MAX_CALLS] chamadas, no máximo 1×/12 h, nunca lança erro. `fetch` injetável nos testes. */
    suspend fun refresh(repo: Repo, now: Long = System.currentTimeMillis(), force: Boolean = false, fetch: (String) -> Pair<Int, String?> = { WebKnow.httpGet(it) }): Boolean = try {
        val (oldTips, oldSt) = load(repo)
        val last = oldSt.maxOfOrNull { it.lastTry } ?: 0L
        if (!force && now - last < TTL_MS) false
        else {
            val st = LinkedHashMap<String, SourceStatus>().also { m -> oldSt.forEach { m[it.url] = it } }
            val got = LinkedHashMap<String, List<Tip>>()
            var calls = 0
            for (u in SEEDS) {
                if (calls >= MAX_CALLS) break
                calls++
                val isVideo = "youtube.com" in u
                val (code, body) = if (isVideo) fetch("https://www.youtube.com/oembed?format=json&url=" + java.net.URLEncoder.encode(u, "UTF-8")) else fetch(u)
                val prev = st[u]
                if (code !in 200..299 || body.isNullOrBlank()) {
                    st[u] = SourceStatus(u, now, prev?.lastOk, if (code <= 0) "sem rede" else "acesso negado ou indisponível (HTTP $code)")
                    continue
                }
                val tips = if (isVideo) listOfNotNull(parseVideo(u, body, now)) else parsePage(u, body, if ("reddit" in u) "Reddit" else "Suporte OSM", now)
                if (tips.isEmpty()) { st[u] = SourceStatus(u, now, now, "página aberta, mas sem formação/estilo citados") ; got[u] = emptyList(); continue }
                st[u] = SourceStatus(u, now, now, null); got[u] = tips
            }
            // quem foi baixado agora substitui; o resto continua com a data antiga
            val merged = merge(oldTips, got)
            repo.dao.putPlan(PlanEntity(0, KIND, save(merged, st, now), now))
            true
        }
    } catch (e: Exception) { false }
}
