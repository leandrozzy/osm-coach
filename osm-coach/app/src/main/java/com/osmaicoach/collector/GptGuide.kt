package com.osmaicoach.collector

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Guia para o Projeto do ChatGPT: um arquivo de texto com TUDO que o app leu e serve para gerar imagens
 * (clubes com nome do jogo e nome real, ligas, elenco, rivais e jogos recentes). Atualiza sozinho 1×/semana
 * (e a cada abertura se mudou muito pouco tempo não conta); o envio ao ChatGPT é manual (compartilhar o arquivo).
 */
object GptGuide {
    private const val WEEK = 7L * 86400000L
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("arts", Context.MODE_PRIVATE)
    fun file(ctx: Context) = File(File(ctx.filesDir, "gpt").apply { mkdirs() }, "guia_osm.txt")
    fun builtAt(ctx: Context): Long = file(ctx).takeIf { it.exists() }?.lastModified() ?: 0L

    /** Instruções para colar nas "instruções do Projeto" do ChatGPT. */
    const val INSTRUCTIONS = "Você cria pôsteres e capas de jornal de futebol para um app de gerenciamento (jogo OSM). " +
        "Formato vertical 1080x1350 (jornal: horizontal 1600x1120). Estilo: foto de imprensa esportiva ultra realista, estádio à noite, luz dramática, torcida real. " +
        "Sempre futebol de campo (soccer), bola redonda preta e branca, NUNCA futebol americano, sem capacete. " +
        "Jogadores: use o nome que eu informar, com rosto, cabelo, barba e tom de pele fiéis ao jogador real; camisa oficial do clube indicado, com o escudo real. " +
        "Clubes: consulte o arquivo \"guia_osm.txt\" deste Projeto para o nome REAL do clube, o país e a liga; escreva sempre o nome real, nunca o nome longo do jogo. " +
        "Escudos: use exatamente os do clube real (ou as imagens que eu anexar), sem redesenhar. " +
        "Texto: português, letras grandes e nítidas, exatamente como eu escrever; não invente números, nomes, gols ou lances. " +
        "Terço inferior mais escuro para o texto ficar legível. " +
        "Tom: antes do jogo = confiança; vitória = festa; derrota ou empate = recuperação, \"vamos melhorar\", nunca humilhação. " +
        "EXPECTATIVA DA TEMPORADA = o que o clube QUER ALCANÇAR (a meta e a ambição): manchete sobre a meta, nunca um balanço de resultados. " +
        "Se a temporada ainda não teve jogos, NÃO escreva campanha, vitórias, gols ou aproveitamento zerados (\"0V 0E 0D\", \"0%\", \"0 × 0\"): o que não aconteceu não vira texto. " +
        "Gols: cite o autor e o minuto só quando eu informar; gol sem autor informado vira \"gol do <clube>\". " +
        "Use apenas os fatos do pedido (meta, posição, ranking do elenco, placares, nomes); não invente adversários, minutos nem declarações."

    suspend fun build(ctx: Context): String {
        val repo = Repo(ctx)
        val sb = StringBuilder()
        val loc = Locale("pt", "BR")
        sb.append("# GUIA OSM AI COACH — dados reais lidos do jogo\n")
        sb.append("Atualizado em ${SimpleDateFormat("dd/MM/yyyy HH:mm", loc).format(Date())}. ")
        sb.append("Os nomes do jogo (OSM) são nomes longos; o NOME REAL é o que a web usa. Use sempre o nome real nas imagens.\n\n")
        sb.append("## Regras de estilo\n").append(INSTRUCTIONS).append("\n\n")
        val clubsSeen = LinkedHashMap<String, String>()   // nome do jogo -> nome real
        fun club(n: String?) { if (!n.isNullOrBlank() && !Fixtures.notAClub(n)) clubsSeen.getOrPut(n.trim()) { repo.realClub(n.trim()) ?: n.trim() } }
        var players = 0
        for (slot in 1..4) {
            val f = repo.fieldMap(slot)
            val team = f[K.TEAM]?.value ?: continue
            if (f[K.SLOT_STATE]?.value in listOf("vazio", "procurando", "preepoca")) continue
            val comp = f[K.COMPETITION]?.value
            val country = Flags.countryName(Flags.isoFromCompetition(comp))
            sb.append("## Slot $slot — ${repo.realClub(team) ?: team}\n")
            sb.append("- Clube: ${repo.realClub(team) ?: team} (no jogo: \"$team\")\n")
            if (!comp.isNullOrBlank()) sb.append("- Competição: $comp" + (country?.let { " (país da liga: $it)" } ?: "") + "\n")
            f[K.LEAGUE_POS]?.value?.let { sb.append("- Posição na liga: $it\n") }
            f[K.MY_STRENGTH]?.value?.let { sb.append("- Força do time: $it\n") }
            club(team)
            val rival = f[K.RIVAL_TEAM]?.value
            if (rival != null) {
                club(rival)
                sb.append("- Próximo rival: ${repo.realClub(rival) ?: rival} (no jogo: \"$rival\")" + (f[K.ROUND]?.value?.let { ", rodada $it" } ?: "") + (f[K.HOME]?.value?.let { ", $it" } ?: "") + "\n")
            }
            val all = repo.dao.playersOf(slot)
            val mine = all.filter { it.owner == "MY" }.sortedByDescending { it.strength ?: 0 }
            if (mine.isNotEmpty()) {
                sb.append("\n### Meu elenco (${mine.size})\n")
                for (p in mine) {
                    val iso = FlagManual.all(repo)[p.nameKey] ?: repo.dao.plan(slot, "natiso4")?.json?.let { runCatching { JSONObject(it).optString(p.nameKey) }.getOrNull() }?.takeIf { it.isNotBlank() }
                    val real = runCatching { WorldOcr.realPlayer(ctx, slot, p.name) }.getOrNull()
                    sb.append("- ${p.name}" + (real?.let { " (real: ${it.fullName ?: it.name}${it.dob?.let { d -> ", nasc. ${d.take(10)}" } ?: ""})" } ?: "") + (p.posCode?.let { " | $it" } ?: "") + (p.age?.let { " | $it anos" } ?: "") + (p.strength?.let { " | força $it" } ?: "") + (Flags.countryName(iso)?.let { " | $it" } ?: "") + "\n")
                    players++
                }
            }
            val riv = all.filter { it.owner == "RIVAL" && it.strength != null }.sortedByDescending { it.strength }.take(8)
            if (riv.isNotEmpty() && rival != null) {
                sb.append("\n### Melhores do rival (${repo.realClub(rival) ?: rival})\n")
                for (p in riv) { sb.append("- ${p.name}" + (p.posCode?.let { " | $it" } ?: "") + (p.age?.let { " | $it anos" } ?: "") + " | força ${p.strength}\n"); players++ }
            }
            val played = repo.dao.matchesOf(slot).filter { it.round != null && it.result in listOf("V", "E", "D") }.sortedBy { it.round }
            for (m in repo.dao.matchesOf(slot)) club(m.opponent)
            if (played.isNotEmpty()) {
                sb.append("\n### Últimos jogos\n")
                for (m in played.takeLast(6)) sb.append("- Rodada ${m.round}: ${m.result} ${m.scoreMine}×${m.scoreOpp} contra ${repo.realClub(m.opponent) ?: m.opponent}\n")
            }
            runCatching {
                val lg = repo.dao.plan(slot, "league")?.json?.let { JSONObject(it) }
                for (r in League.table(lg, "va")) club(r.club)
                for (r in League.table(lg, "st")) club(r.club)
            }
            sb.append("\n")
        }
        sb.append("## Clubes citados (nome no jogo → nome real)\n")
        for ((g, r) in clubsSeen.toSortedMap(String.CASE_INSENSITIVE_ORDER)) sb.append("- $g → $r\n")
        sb.append("\n(${clubsSeen.size} clubes, $players jogadores listados)\n")
        return sb.toString()
    }

    data class Status(val at: Long, val clubs: Int, val changedSinceSent: Boolean)

    fun status(ctx: Context): Status {
        val f = file(ctx)
        if (!f.exists()) return Status(0, 0, false)
        val txt = runCatching { f.readText() }.getOrDefault("")
        val clubs = Regex("\\((\\d+) clubes").find(txt)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return Status(f.lastModified(), clubs, prefs(ctx).getInt("gpt_sent_hash", 0) != body(txt).hashCode())
    }

    /** Hash ignora a linha de data (muda toda vez). */
    private fun body(t: String) = t.lines().filterNot { it.startsWith("Atualizado em") }.joinToString("\n")

    suspend fun refresh(ctx: Context): Boolean {
        val text = runCatching { build(ctx) }.getOrNull() ?: return false
        if (!text.contains("## Slot")) return false   // nada lido ainda
        file(ctx).writeText(text)
        runCatching { exportDownloads(ctx, text) }
        UiBus.bump()
        return true
    }

    /** 1×/semana, sozinho (KeepAliveService e ao abrir o app). */
    fun refreshIfStale(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - builtAt(ctx) < WEEK || now - prefs(ctx).getLong("gpt_try", 0L) < 3_600_000L) return
        prefs(ctx).edit().putLong("gpt_try", now).apply()
        AppScope.scope.launch(Dispatchers.IO) { runCatching { refresh(ctx) } }
    }

    private const val NAME = "osm-guia-chatgpt.txt"

    /** Cópia em Download/OSM Coach (substitui a anterior) para subir no Projeto. */
    private fun exportDownloads(ctx: Context, text: String): Uri? {
        val r = ctx.contentResolver
        runCatching { r.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?", arrayOf(NAME, "Download/OSM Coach%")) }
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, NAME)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/OSM Coach")
        }
        val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return null
        r.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        return uri
    }

    /** Abre o menu de compartilhar com o arquivo (escolha o ChatGPT) e marca como enviado. */
    suspend fun share(ctx: Context): String {
        if (!refresh(ctx)) return "Ainda não há dados lidos dos slots para montar o guia."
        val text = file(ctx).readText()
        val uri = exportDownloads(ctx, text) ?: return "Não consegui salvar o arquivo."
        prefs(ctx).edit().putInt("gpt_sent_hash", body(text).hashCode()).apply()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Enviar guia ao ChatGPT").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Escolha o ChatGPT e envie ao Projeto (Arquivos do projeto). Cópia salva em Download/OSM Coach."
    }

    fun copyInstructions(ctx: Context) {
        runCatching { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("instruções", INSTRUCTIONS)) }
    }
}
