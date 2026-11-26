package com.osmaicoach.collector

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * O que o app está fazendo AGORA em segundo plano (buscas em APIs, Supabase, imagens, IA, sincronização). Cada tarefa registra início e fim; a barra pequena no topo
 * mostra o que está ativo e, ao tocar, os últimos eventos. Só registro de exibição: nunca altera o que o app faz.
 */
object Live {
    private class Ev(val label: String, val at: Long)
    private val active = ConcurrentHashMap<String, Ev>()
    private val recent = ArrayDeque<String>()
    private val seq = AtomicLong()
    private val hour = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /** Tarefa com chave fixa (só uma por vez, ex.: "sync"): atualiza o texto enquanto avança. */
    /** Só a faixa do topo lê isto: NUNCA usar o UiBus aqui (cada requisição de rede ocupava a janela do bump e os avisos de escudo/foto salvos se perdiam). */
    var version by mutableIntStateOf(0)
    private fun changed() { version++ }

    fun on(key: String, label: String) { active.compute(key) { _, old -> Ev(label, old?.at ?: System.currentTimeMillis()) }; changed() }
    fun off(key: String, note: String? = null) {
        val e = active.remove(key) ?: return
        log(e, note); changed()
    }
    /** Tarefa curta e possivelmente paralela (uma chamada de rede): registra início/fim sozinha. */
    inline fun <T> run(label: String, block: () -> T): T {
        val k = begin(label)
        try { return block() } finally { end(k) }
    }
    fun begin(label: String): String { val k = "t" + seq.incrementAndGet(); active[k] = Ev(label, System.currentTimeMillis()); changed(); return k }
    fun end(k: String) { active.remove(k)?.let { log(it, null) }; changed() }

    private fun log(e: Ev, note: String?) = synchronized(recent) {
        val ms = System.currentTimeMillis() - e.at
        recent.addFirst("${hour.format(Date())}  ${e.label}${note?.let { " — $it" } ?: ""}  (${if (ms >= 1000) "%.1f s".format(ms / 1000.0) else "$ms ms"})")
        while (recent.size > 40) recent.removeLast()
    }
    fun labels(): List<String> = active.values.sortedBy { it.at }.map { it.label }
    fun history(n: Int = 12): List<String> = synchronized(recent) { recent.take(n) }
}

/** Faixa pequena sob o topo do app: aparece só quando há algo em andamento; tocar mostra os últimos eventos. */
@Composable
internal fun LiveStrip() {
    val tick = rememberTick(1000L); tick.let { }
    Live.version.let { }
    val now = Live.labels()
    var open by remember { mutableStateOf(false) }
    if (now.isEmpty() && !open) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(C.SURFACE).clickable { open = !open }.padding(horizontal = 10.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (now.isNotEmpty()) CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp, color = C.PRIMARY) else Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(C.OK))
            Spacer(Modifier.width(8.dp))
            Text(if (now.isEmpty()) "tudo em dia" else now.first() + if (now.size > 1) "  +${now.size - 1}" else "", fontSize = 11.sp, color = C.MUTED, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(if (open) "▴" else "▾", fontSize = 11.sp, color = C.MUTED)
        }
        if (open) {
            for (l in now.drop(1)) Text("▸ $l", fontSize = 10.5.sp, color = C.MUTED, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val h = Live.history()
            if (h.isEmpty()) Text("Nenhum evento ainda.", fontSize = 10.5.sp, color = C.MUTED)
            for (l in h) Text(l, fontSize = 10.5.sp, color = C.MUTED, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
