package com.osmaicoach.collector

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * Base Mundial — acesso HTTP seguro: só https, só hosts conhecidos (lista fechada), sem IP literal nem destino interno (SSRF), sem seguir redirecionamento para fora da lista,
 * com limite de tamanho, `Accept-Encoding: identity` (a posição em bytes do checkpoint precisa ser exata), ETag/Last-Modified e `Range` para retomar.
 */
object WorldHttp {
    const val UA = "OSMCoach-BaseMundial/1.0 (app Android de apoio ao OSM; dados abertos)"
    /** Hosts autorizados: um por fonte registrada (ver [WorldSources]). */
    val HOSTS = setOf("raw.githubusercontent.com", "flagcdn.com", "www.wikidata.org", "query.wikidata.org", "commons.wikimedia.org", "upload.wikimedia.org", "v3.football.api-sports.io",
        "www.thesportsdb.com", "reep.football", "www.reep.football")

    /** Host extra autorizado em tempo de execução: SÓ o *.supabase.co que o usuário configurou (ver [WorldRemote.cfg]). */
    @Volatile var extraHosts: Set<String> = emptySet()

    fun allowed(url: String): Boolean = runCatching {
        val u = URL(url)
        if (u.protocol != "https") return false
        val host = u.host.lowercase()
        if (host !in HOSTS && host !in extraHosts) return false
        if (host.all { it.isDigit() || it == '.' || it == ':' }) return false
        // destino não pode resolver para rede interna
        InetAddress.getAllByName(host).none { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress }
    }.getOrDefault(false)

    class Resp(val code: Int, val stream: InputStream?, val etag: String?, val lastModified: String?, val length: Long, val conn: HttpURLConnection?) : AutoCloseable {
        override fun close() { runCatching { stream?.close() }; runCatching { conn?.disconnect() } }
        val ok get() = code in 200..299
    }

    /** GET com retomada. `offset > 0` usa Range; `etag` usa If-None-Match (304 = nada novo). Sem seguir redirecionamentos automáticos para fora da lista. */
    fun get(url: String, offset: Long = 0L, etag: String? = null, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 30_000): Resp {
        require(allowed(url)) { "URL não autorizada: $url" }
        var cur = url
        repeat(4) {
            val c = URL(cur).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", UA); c.setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) c.setRequestProperty("Range", "bytes=$offset-")
            if (etag != null && offset == 0L) c.setRequestProperty("If-None-Match", etag)
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            val code = c.responseCode
            if (code in 300..399 && code != 304) {
                val loc = c.getHeaderField("Location")?.let { URL(URL(cur), it).toString() } ?: return Resp(code, null, null, null, -1, c)
                c.disconnect()
                require(allowed(loc)) { "redirecionamento para destino não autorizado" }
                cur = loc; return@repeat
            }
            val stream = if (code in 200..299) c.inputStream else null
            return Resp(code, stream, c.getHeaderField("ETag"), c.getHeaderField("Last-Modified"), c.contentLengthLong, c)
        }
        throw java.io.IOException("redirecionamentos demais")
    }

    /** Resposta pequena em texto (JSON de API), com teto de tamanho. */
    fun text(url: String, headers: Map<String, String> = emptyMap(), maxBytes: Int = 4_000_000): Pair<Int, String?> {
        get(url, headers = headers).use { r ->
            val s = r.stream ?: return Pair(r.code, null)
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192); var n: Int; var total = 0
            while (s.read(buf).also { n = it } > 0) { total += n; if (total > maxBytes) throw java.io.IOException("resposta grande demais"); out.write(buf, 0, n) }
            return Pair(r.code, out.toString("UTF-8"))
        }
    }

    /** POST (envio de imagem e linha de índice ao Supabase). Mesmas regras de destino; sem seguir redirecionamento. (código, corpo curto) */
    fun post(url: String, body: ByteArray, contentType: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 30_000): Pair<Int, String?> {
        require(allowed(url)) { "URL não autorizada: $url" }
        require(body.size <= 2_000_000) { "corpo grande demais" }
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false; c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", UA); c.setRequestProperty("Content-Type", contentType)
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().take(2000).toByteArray().toString(Charsets.UTF_8) }
            return Pair(code, txt)
        } finally { c.disconnect() }
    }
}
