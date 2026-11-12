package com.osmaicoach.collector

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Bandeiras de países nítidas do Flagpedia (flagcdn.com: 254 bandeiras em domínio público, uso livre). Baixa o PNG do código ISO uma vez
 * para `flags/<iso>.png`; o Elenco mostra essa imagem quando o país do jogador foi identificado (Atlas), senão usa a miniatura do jogo.
 */
object FlagWeb {
    private val queue = LinkedHashSet<String>()
    private val miss = HashSet<String>()
    @Volatile private var worker = false
    private val lock = Any()

    fun file(filesDir: File, iso: String?): File? {
        val c = iso?.lowercase()?.takeIf { it.matches(Regex("[a-z]{2}(-[a-z]{2,3})?")) } ?: return null
        return File(filesDir, "flags/$c.png").takeIf { it.exists() }
    }

    fun request(ctx: Context, iso: String?) {
        val c = iso?.lowercase()?.takeIf { it.matches(Regex("[a-z]{2}(-[a-z]{2,3})?")) } ?: return
        if (Settings.get(ctx, "web_crests", "1") != "1") return
        if (file(ctx.filesDir, c) != null) return
        synchronized(lock) {
            if (c in miss || !queue.add(c)) return
            if (worker) return
            worker = true
        }
        AppScope.scope.launch {
            try {
                while (true) {
                    val next = synchronized(lock) { queue.firstOrNull()?.also { queue.remove(it) } } ?: break
                    val ok = runCatching { fetch(ctx, next) }.getOrDefault(false)
                    if (!ok) synchronized(lock) { miss.add(next) } else UiBus.bump()
                    delay(150)
                }
            } finally { synchronized(lock) { worker = false } }
        }
    }

    private fun fetch(ctx: Context, iso: String): Boolean {
        val c = URL("https://flagcdn.com/w160/$iso.png").openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "OSMCoach/5")
        if (c.responseCode !in 200..299) return false
        val bytes = c.inputStream.use { it.readBytes() }
        if (bytes.size < 100 || bytes.size > 200_000) return false
        val f = File(ctx.filesDir, "flags/$iso.png"); f.parentFile?.mkdirs()
        f.writeBytes(bytes)
        return true
    }

    @Volatile private var allStarted = false

    /**
     * Baixa TODAS as bandeiras do Flagpedia (~250, ~3 KB cada, uma vez só): servem de referência para descobrir o país de cada jogador
     * (`FlagIdent`) e de imagem nítida para o Elenco. Roda em segundo plano; não repete enquanto o app está aberto.
     */
    fun ensureAll(ctx: Context) {
        if (allStarted || FlagIdent.refsReady(ctx.filesDir)) return
        if (Settings.get(ctx, "web_crests", "1") != "1") return
        allStarted = true
        AppScope.scope.launch {
            try {
                val c = URL("https://flagcdn.com/en/codes.json").openConnection() as HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = 15000
                if (c.responseCode !in 200..299) { allStarted = false; return@launch }
                val j = org.json.JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val codes = j.keys().asSequence().filter { it.length == 2 || it.startsWith("gb-") }.toList()
                var ok = 0
                File(ctx.filesDir, "flags").apply { mkdirs() }.let { File(it, "_total.txt").writeText(codes.size.toString()) }
                for (iso in codes) {
                    if (file(ctx.filesDir, iso) != null) { ok++; continue }
                    if (runCatching { fetch(ctx, iso) }.getOrDefault(false)) ok++
                    delay(60)
                }
                Diag.log("Bandeiras do Flagpedia: $ok de ${codes.size} no aparelho")
                UiBus.bump()
                if (ok < 200) allStarted = false
            } catch (e: Exception) { allStarted = false; Diag.lastError = "Bandeiras da web: " + (e.message ?: "erro") }
        }
    }

    /** (baixadas, total) das bandeiras do Flagpedia; o total só é conhecido depois da 1ª lista (até lá, 254). */
    fun progress(filesDir: File): Pair<Int, Int> {
        val have = File(filesDir, "flags").listFiles()?.count { it.name.endsWith(".png") } ?: 0
        val total = runCatching { File(filesDir, "flags/_total.txt").readText().trim().toInt() }.getOrDefault(254)
        return Pair(minOf(have, total), total)
    }
}
