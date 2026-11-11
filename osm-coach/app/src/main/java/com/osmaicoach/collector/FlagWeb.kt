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
}
