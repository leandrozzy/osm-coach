package com.osmaicoach.collector

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Artes (12/11): imagens de divulgação desenhadas pelo próprio app (sem IA de imagem, sem internet, sem custo) com os dados JÁ lidos do jogo:
 * contratação, despedida (venda confirmada), vitória/sequência, campanha e fim de temporada, uma por slot, nas cores do escudo do time.
 * Não existe foto do jogador no OSM: o destaque é a camisa com a força. Nada é inventado: só sai arte de evento confirmado.
 */
object Arts {
    const val W = 1080
    const val H = 1350

    data class Item(val id: String, val slot: Int, val kind: String, val title: String, val at: Long, val file: String)
    data class Cand(val id: String, val slot: Int, val kind: String, val title: String, val at: Long, val key: String? = null)

    private fun dir(ctx: Context): File = File(ctx.filesDir, "arts").apply { mkdirs() }

    /** Desenho novo (v2, 12/11): apaga as artes do modelo antigo (e a lista de ids vistos) uma única vez; as de eventos recentes são refeitas por `auto`. */
    suspend fun migrate(ctx: Context) {
        val sp = ctx.getSharedPreferences("arts", Context.MODE_PRIVATE)
        if (sp.getInt("ver", 1) >= 2) return
        val repo = Repo(ctx)
        runCatching { dir(ctx).listFiles()?.forEach { it.delete() } }
        repo.dao.deletePlan(0, "arts"); repo.dao.deletePlan(0, "artsseen")
        sp.edit().putInt("ver", 2).putInt("new", 0).apply()
    }

    // ------------------------------------------------------------------ guardado (plano global `arts`, ids vistos em `artsseen`)
    suspend fun items(repo: Repo): List<Item> {
        val arr = runCatching { JSONArray(repo.dao.plan(0, "arts")?.json ?: "[]") }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Item(o.optString("id"), o.optInt("slot"), o.optString("kind"), o.optString("title"), o.optLong("at"), o.optString("file"))
        }.filter { File(it.file).exists() }.sortedByDescending { it.at }
    }

    private suspend fun saveItems(repo: Repo, list: List<Item>) {
        val arr = JSONArray()
        for (i in list) arr.put(JSONObject().put("id", i.id).put("slot", i.slot).put("kind", i.kind).put("title", i.title).put("at", i.at).put("file", i.file))
        repo.dao.putPlan(PlanEntity(0, "arts", arr.toString(), System.currentTimeMillis()))
    }

    suspend fun delete(repo: Repo, item: Item) {
        runCatching { File(item.file).delete() }
        saveItems(repo, items(repo).filter { it.id != item.id })
    }

    private suspend fun seen(repo: Repo): MutableSet<String>? {
        val p = repo.dao.plan(0, "artsseen") ?: return null
        val arr = runCatching { JSONArray(p.json) }.getOrDefault(JSONArray())
        return (0 until arr.length()).map { arr.optString(it) }.toMutableSet()
    }

    private suspend fun putSeen(repo: Repo, s: Set<String>) {
        val arr = JSONArray(); s.toList().takeLast(400).forEach { arr.put(it) }
        repo.dao.putPlan(PlanEntity(0, "artsseen", arr.toString(), System.currentTimeMillis()))
    }

    // ------------------------------------------------------------------ o que pode virar arte
    private fun played(ms: List<MatchEntity>) = ms.filter { it.round != null && it.result in listOf("V", "E", "D") }.sortedBy { it.round }
    private fun num(s: String?): Int? = s?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }

    /**
     * Eventos deste slot que dão arte (o id é estável: não repete). Só slot COM competição: livre/procurando/pré-época não tem nada.
     * Contratação só de quem ESTÁ no elenco e não foi lançado como vendido logo depois (o OCR já fez compra/venda falsa do mesmo nome).
     * Competição em andamento: contratação, goleada (3+ gols de diferença), 3+ vitórias seguidas, liderança. Encerrada: campeão, objetivo alcançado.
     * Campanha e resumo da temporada só à mão. Batalhas/crews o app não lê ainda.
     */
    suspend fun candidates(repo: Repo, slot: Int): List<Cand> {
        val f = repo.fieldMap(slot)
        if (f[K.TEAM]?.value == null) return emptyList()
        if (f[K.SLOT_STATE]?.value in listOf("vazio", "procurando", "preepoca")) return emptyList()
        val over = Hints.competitionOver(repo, slot, f)
        runCatching { repo.cleanLedger(slot) }
        val out = ArrayList<Cand>()
        val keys = repo.dao.playersOf(slot).filter { it.owner == "MY" }.map { it.nameKey }.toSet()
        val ms = played(repo.dao.matchesOf(slot))
        val last = ms.lastOrNull()
        val pos = num(f[K.LEAGUE_POS]?.value)
        val obj = num(f[K.MY_OBJECTIVE]?.value)
        if (!over) {
            val ledger = Ledger.parse(repo.dao.plan(slot, "ledger")?.json)
            for (e in ledger.filter { it.type == "buy" && it.key in keys }.sortedByDescending { it.at }.take(8)) {
                out.add(Cand("buy|$slot|${e.key}|${e.at}", slot, "BUY", "Contratação: " + e.name, e.at, e.key))
            }
            if (last != null && last.result == "V") {
                val streak = ms.reversed().takeWhile { it.result == "V" }.size
                val diff = (last.scoreMine ?: 0) - (last.scoreOpp ?: 0)
                if (streak >= 3) out.add(Cand("streak|$slot|$streak|${last.round}", slot, "STREAK", "$streak vitórias seguidas", last.updatedAt))
                if (diff >= 3) out.add(Cand("win|$slot|${last.round}", slot, "GOLEADA", "Goleada na J${last.round}: ${last.scoreMine}×${last.scoreOpp}", last.updatedAt))
            }
            if (pos == 1 && ms.size >= 3) out.add(Cand("leader|$slot|${ms.last().round}", slot, "LEADER", "Líder da liga na J${ms.last().round}", System.currentTimeMillis()))
            if (ms.size >= 3) out.add(Cand("camp|$slot|${ms.size}", slot, "CAMP", "Campanha até a J${ms.last().round}", System.currentTimeMillis()))
        } else if (ms.isNotEmpty()) {
            val comp = Txt.key(f[K.COMPETITION]?.value.orEmpty())
            if (pos == 1) out.add(Cand("title|$slot|$comp", slot, "TITLE", "Campeão da liga", System.currentTimeMillis()))
            else if (pos != null && obj != null && pos <= obj) out.add(Cand("obj|$slot|$comp", slot, "OBJECTIVE", "Objetivo alcançado: ${pos}º lugar", System.currentTimeMillis()))
            out.add(Cand("season|$slot|$comp", slot, "SEASON", "Resumo da temporada", System.currentTimeMillis()))
        }
        return out
    }

    /**
     * Depois de Encerrar a leitura: cria as artes dos eventos NOVOS de cada slot (contratação, goleada, sequência, liderança, título, objetivo).
     * Na 1ª vez só vale o que aconteceu nas últimas 24 h (não despeja o histórico). Campanha/resumo só à mão. Devolve quantas criou.
     */
    suspend fun auto(ctx: Context): Int {
        migrate(ctx)
        val repo = Repo(ctx)
        val firstRun = seen(repo) == null
        val seenSet = seen(repo) ?: mutableSetOf()
        val now = System.currentTimeMillis()
        var made = 0
        for (slot in 1..4) {
            for (c in candidates(repo, slot)) {
                if (c.id in seenSet) continue
                seenSet.add(c.id)
                if (c.kind == "CAMP" || c.kind == "SEASON") continue
                if (firstRun && now - c.at > 24 * 3600_000L) continue
                if (made >= 6) continue
                if (runCatching { make(ctx, c) }.getOrNull() != null) made++
            }
        }
        putSeen(repo, seenSet)
        if (made > 0) {
            val sp = ctx.getSharedPreferences("arts", Context.MODE_PRIVATE)
            sp.edit().putInt("new", sp.getInt("new", 0) + made).apply()
            Diag.log("Artes: $made nova(s) criada(s)")
            UiBus.bump()
        }
        return made
    }

    fun unseenCount(ctx: Context): Int = ctx.getSharedPreferences("arts", Context.MODE_PRIVATE).getInt("new", 0)
    fun clearUnseen(ctx: Context) { ctx.getSharedPreferences("arts", Context.MODE_PRIVATE).edit().putInt("new", 0).apply() }

    // ------------------------------------------------------------------ montar e desenhar
    private fun bmp(f: File?): Bitmap? = f?.takeIf { it.exists() }?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }

    suspend fun make(ctx: Context, c: Cand): Item? {
        val repo = Repo(ctx)
        val b = draw(ctx, repo, c) ?: return null
        val f = File(dir(ctx), "${System.currentTimeMillis()}_${c.slot}.png")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        b.recycle()
        val item = Item(c.id, c.slot, c.kind, c.title, System.currentTimeMillis(), f.absolutePath)
        val all = items(repo).filter { it.id != c.id }
        val keep = (listOf(item) + all).take(60)
        for (old in all.drop(59)) runCatching { File(old.file).delete() }
        saveItems(repo, keep)
        UiBus.bump()
        return item
    }

    private fun posLabel(cat: String?, code: String?): String {
        val base = when (cat) { "GOL" -> "Goleiro"; "DEF" -> "Defensor"; "MEI" -> "Meio-campista"; "ATA" -> "Atacante"; else -> "" }
        return if (base.isNotEmpty() && !code.isNullOrBlank()) "$base ($code)" else base.ifEmpty { code.orEmpty() }
    }

    private suspend fun draw(ctx: Context, repo: Repo, c: Cand): Bitmap? {
        val slot = c.slot
        val f = repo.fieldMap(slot)
        val team = f[K.TEAM]?.value ?: return null
        val comp = f[K.COMPETITION]?.value
        val crest = bmp(CrestWeb.find(ctx.filesDir, team) ?: CrestCut.find(ctx.filesDir, team))
        val th = theme(crest, slot)
        val ms = played(repo.dao.matchesOf(slot))
        val v = ms.count { it.result == "V" }; val e = ms.count { it.result == "E" }; val d = ms.count { it.result == "D" }
        val gf = ms.sumOf { it.scoreMine ?: 0 }; val ga = ms.sumOf { it.scoreOpp ?: 0 }
        val aprov = if (ms.isEmpty()) 0 else Math.round(100f * (3 * v + e) / (3 * ms.size))
        val last = ms.lastOrNull()
        return when (c.kind) {
            "BUY", "PLAYER" -> {
                val key = c.key ?: return null
                val p = repo.dao.playersOf(slot).firstOrNull { it.owner == "MY" && it.nameKey == key } ?: return null
                val parts = p.name.trim().split(Regex("\\s+"))
                val first = if (parts.size > 1) parts.dropLast(1).joinToString(" ") else ""
                val iso = FlagManual.all(repo)[key] ?: repo.dao.plan(slot, "natiso2")?.json?.let { runCatching { JSONObject(it).optString(key) }.getOrNull() }?.takeIf { it.isNotBlank() }
                val rows = ArrayList<Triple<String, String, Bitmap?>>()
                Flags.countryName(iso)?.let { rows.add(Triple("País", it, bmp(FlagWeb.file(ctx.filesDir, iso)))) }
                p.age?.let { rows.add(Triple("Idade", "$it anos", null)) }
                posLabel(p.cat, p.posCode).takeIf { it.isNotBlank() }?.let { rows.add(Triple("Posição", it, null)) }
                p.valueText?.let { rows.add(Triple("Valor", it, null)) }
                val buy = c.kind == "BUY"
                ArtsDraw.player(th, team, comp, if (buy) "CONTRATAÇÃO CONFIRMADA" else "DESTAQUE DO ELENCO", first, parts.last(), rows, p.strength?.toString(),
                    if (buy) "Bem-vindo ao $team!" else "$team • orgulho do clube", slot, crest)
            }
            "GOLEADA" -> {
                val m = last ?: return null
                val opp = bmp(CrestWeb.find(ctx.filesDir, m.opponent) ?: CrestCut.find(ctx.filesDir, m.opponent))
                val rows = ArrayList<Pair<String, String>>()
                rows.add("Jogo" to (if (m.home == true) "em casa" else if (m.home == false) "fora" else "—"))
                val streak = ms.reversed().takeWhile { it.result == "V" }.size
                if (streak >= 2) rows.add("Sequência" to "$streak vitórias")
                ArtsDraw.match(th, team, comp, "GOLEADA!", crest, opp, m.scoreMine ?: 0, m.scoreOpp ?: 0, "J${m.round} • ${m.opponent.orEmpty()}", rows, "Vamos pra cima, $team!", slot)
            }
            "STREAK" -> {
                val streak = ms.reversed().takeWhile { it.result == "V" }.size
                ArtsDraw.stats(th, team, comp, "EM CHAMAS!", "$streak", "VITÓRIAS SEGUIDAS", listOf("Gols marcados" to "$gf", "Gols sofridos" to "$ga", "Aproveitamento" to "$aprov%"), "Vamos pra cima, $team!", slot, crest)
            }
            "LEADER" -> ArtsDraw.stats(th, team, comp, "LÍDER DA LIGA!", "1º", "NA RODADA ${last?.round ?: ""}", listOf("Jogos" to "${ms.size}", "Campanha" to "${v}V ${e}E ${d}D", "Aproveitamento" to "$aprov%"), "No topo da tabela!", slot, crest)
            "TITLE", "OBJECTIVE" -> {
                val pos = num(f[K.LEAGUE_POS]?.value)
                val title = c.kind == "TITLE"
                ArtsDraw.trophyCard(th, team, comp, if (title) "CAMPEÃO DA LIGA!" else "OBJETIVO ALCANÇADO!", if (title) "CAMPEÃO" else "${pos ?: ""}º LUGAR",
                    (comp ?: "") + " • ${v}V ${e}E ${d}D", "Obrigado, torcida!", slot, crest)
            }
            else -> {   // CAMP, SEASON (à mão)
                if (ms.isEmpty()) return null
                ArtsDraw.stats(th, team, comp, if (c.kind == "SEASON") "RESUMO DA TEMPORADA" else "CAMPANHA ATÉ AGORA", "${v}V ${e}E ${d}D", comp.orEmpty(),
                    listOf("Jogos" to "${ms.size}", "Gols" to "$gf × $ga", "Aproveitamento" to "$aprov%"), if (c.kind == "SEASON") "Obrigado, torcida!" else "A jornada continua!", slot, crest)
            }
        }
    }

    /** Cores do time: o tom mais presente do escudo vira o fundo escuro e a camisa; o segundo (ou dourado/verde) vira o destaque. */
    internal fun theme(crest: Bitmap?, slot: Int): ArtsDraw.Theme {
        var h1 = intArrayOf(140, 215, 355, 275)[(slot - 1).coerceIn(0, 3)].toFloat()
        var h2: Float? = null
        if (crest != null) {
            val s = Bitmap.createScaledBitmap(crest, 32, 32, true)
            val w = FloatArray(12)
            val hsv = FloatArray(3)
            for (y in 0 until 32) for (x in 0 until 32) {
                val px = s.getPixel(x, y)
                if ((px ushr 24) < 128) continue
                Color.colorToHSV(px, hsv)
                if (hsv[1] < 0.35f || hsv[2] < 0.25f) continue
                w[(hsv[0] / 30f).toInt().coerceIn(0, 11)] += hsv[1] * hsv[2]
            }
            s.recycle()
            val order = w.indices.sortedByDescending { w[it] }
            if (w[order[0]] > 0f) {
                h1 = order[0] * 30f + 15f
                val sec = order.drop(1).firstOrNull { w[it] > w[order[0]] * 0.25f && Math.min(Math.abs(it - order[0]), 12 - Math.abs(it - order[0])) >= 2 }
                if (sec != null) h2 = sec * 30f + 15f
            }
        }
        val bg = Color.HSVToColor(floatArrayOf(h1, 0.85f, 0.34f))
        val kit = Color.HSVToColor(floatArrayOf(h1, 0.88f, 0.80f))
        val accent = Color.HSVToColor(floatArrayOf(h2 ?: if (h1 in 35f..75f) 140f else 48f, 0.95f, 1f))
        return ArtsDraw.Theme(bg, accent, kit)
    }

    // ------------------------------------------------------------------ galeria e compartilhamento
    fun saveToGallery(ctx: Context, it: Item): Uri? {
        val sp = ctx.getSharedPreferences("arts_saved", Context.MODE_PRIVATE)
        sp.getString(it.id + "|" + it.at, null)?.let { u -> return Uri.parse(u) }
        val f = File(it.file)
        if (!f.exists()) return null
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "osm_s${it.slot}_${it.at}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/OSM Coach")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { i -> i.copyTo(o) } }
        sp.edit().putString(it.id + "|" + it.at, uri.toString()).apply()
        return uri
    }

    fun share(ctx: Context, it: Item) {
        val uri = saveToGallery(ctx, it) ?: return
        val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Compartilhar arte").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
