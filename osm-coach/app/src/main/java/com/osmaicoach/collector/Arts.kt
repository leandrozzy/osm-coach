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
    data class Cand(val id: String, val slot: Int, val kind: String, val title: String, val at: Long, val key: String? = null, val auto: Boolean = true)

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

    // ------------------------------------------------------------------ escolhas do usuário: slots ligados e eventos dispensados
    fun slotOn(ctx: Context, slot: Int): Boolean = !ctx.getSharedPreferences("arts", Context.MODE_PRIVATE).getBoolean("slot_off_$slot", false)
    fun setSlotOn(ctx: Context, slot: Int, on: Boolean) { ctx.getSharedPreferences("arts", Context.MODE_PRIVATE).edit().putBoolean("slot_off_$slot", !on).apply() }

    private suspend fun dismissed(repo: Repo): MutableSet<String> {
        val arr = runCatching { JSONArray(repo.dao.plan(0, "artsdismiss")?.json ?: "[]") }.getOrDefault(JSONArray())
        return (0 until arr.length()).map { arr.optString(it) }.toMutableSet()
    }

    /** Tira eventos da lista "Criar uma arte" (não somem as artes já feitas). */
    suspend fun dismiss(repo: Repo, ids: Collection<String>) {
        val d = dismissed(repo); d.addAll(ids)
        val arr = JSONArray(); d.toList().takeLast(600).forEach { arr.put(it) }
        repo.dao.putPlan(PlanEntity(0, "artsdismiss", arr.toString(), System.currentTimeMillis()))
        UiBus.bump()
    }

    /** Esvazia a lista de eventos do slot. */
    suspend fun clearList(repo: Repo, slot: Int) { dismiss(repo, candidates(repo, slot, includeDismissed = true).map { it.id }) }

    /** Apaga as artes JÁ FEITAS deste slot (arquivos e lista). Devolve quantas. */
    suspend fun deleteSlot(repo: Repo, slot: Int): Int {
        val all = items(repo)
        val mine = all.filter { it.slot == slot }
        for (i in mine) runCatching { File(i.file).delete() }
        saveItems(repo, all.filter { it.slot != slot })
        UiBus.bump()
        return mine.size
    }

    // ------------------------------------------------------------------ o que pode virar arte
    /** Jogador visto em 3+ passadas de leitura do Plantel: nome lido uma vez só pode ser erro de OCR (fantasma) e não vira arte. */
    private suspend fun confirmed(repo: Repo, slot: Int): Set<String> {
        val j = repo.dao.plan(slot, "squadreads")?.json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptySet()
        return j.keys().asSequence().filter { j.optInt(it) >= 3 }.toSet()
    }

    private fun played(ms: List<MatchEntity>) = ms.filter { it.round != null && it.result in listOf("V", "E", "D") }.sortedBy { it.round }
    private fun num(s: String?): Int? = s?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }

    /**
     * Eventos deste slot que dão arte (o id é estável: não repete). Só slot COM competição: livre/procurando/pré-época não tem nada.
     * Contratação só de quem ESTÁ no elenco e não foi lançado como vendido logo depois (o OCR já fez compra/venda falsa do mesmo nome).
     * Competição em andamento: contratação, goleada (3+ gols de diferença), 3+ vitórias seguidas, liderança. Encerrada: campeão, objetivo alcançado.
     * Campanha e resumo da temporada só à mão. Batalhas/crews o app não lê ainda.
     */
    suspend fun candidates(repo: Repo, slot: Int, includeDismissed: Boolean = false): List<Cand> {
        val f = repo.fieldMap(slot)
        if (f[K.TEAM]?.value == null) return emptyList()
        if (f[K.SLOT_STATE]?.value in listOf("vazio", "procurando", "preepoca")) return emptyList()
        val over = Hints.competitionOver(repo, slot, f)
        runCatching { repo.cleanLedger(slot) }
        val out = ArrayList<Cand>()
        val sure = confirmed(repo, slot)
        val keys = repo.dao.playersOf(slot).filter { it.owner == "MY" && it.nameKey in sure }.map { it.nameKey }.toSet()
        val ms = played(repo.dao.matchesOf(slot))
        val last = ms.lastOrNull()
        val pos = num(f[K.LEAGUE_POS]?.value)
        val obj = num(f[K.MY_OBJECTIVE]?.value)
        if (!over) {
            val ledger = Ledger.parse(repo.dao.plan(slot, "ledger")?.json)
            for (e in ledger.filter { it.type == "buy" && it.key in keys }.sortedByDescending { it.at }.take(8)) {
                out.add(Cand("buy|$slot|${e.key}|${e.at}", slot, "BUY", "Contratação: " + e.name, e.at, e.key))
            }
            val recent = ledger.filter { it.type == "buy" && it.key in keys }.sortedByDescending { it.at }.distinctBy { it.key }.take(3)
            if (recent.isNotEmpty()) out.add(Cand("news|$slot|${recent.first().at}", slot, "NEWS", "Capa de jornal: " + recent.joinToString(", ") { it.name }, recent.first().at, auto = false))
            if (last != null && last.round != null) out.add(Cand("post|$slot|${last.round}", slot, "POST", "Jornal do jogo: J${last.round} ${last.scoreMine ?: "?"}×${last.scoreOpp ?: "?"} vs ${last.opponent.orEmpty()}", last.updatedAt))
            if (last != null && last.result == "V") {
                val streak = ms.reversed().takeWhile { it.result == "V" }.size
                val diff = (last.scoreMine ?: 0) - (last.scoreOpp ?: 0)
                if (streak >= 3) out.add(Cand("streak|$slot|$streak|${last.round}", slot, "STREAK", "$streak vitórias seguidas", last.updatedAt))
                if (diff >= 3) out.add(Cand("win|$slot|${last.round}", slot, "GOLEADA", "Goleada na J${last.round}: ${last.scoreMine}×${last.scoreOpp}", last.updatedAt))
            }
            if (pos == 1 && ms.size >= 3) out.add(Cand("leader|$slot|${ms.last().round}", slot, "LEADER", "Líder da liga na J${ms.last().round}", System.currentTimeMillis()))
            if (ms.size >= 3) out.add(Cand("camp|$slot|${ms.size}", slot, "CAMP", "Campanha até a J${ms.last().round}", System.currentTimeMillis(), auto = false))
            // começo e meio de campeonato: expectativa, craques, momento, dia de jogo e lado a lado
            val comp = Txt.key(f[K.COMPETITION]?.value.orEmpty())
            val now = System.currentTimeMillis()
            if (ms.size <= 1) out.add(Cand("expect|$slot|$comp", slot, "EXPECT", "Expectativa da temporada", now))
            if (repo.dao.playersOf(slot).count { it.owner == "MY" && it.strength != null } >= 3)
                out.add(Cand("stars|$slot|${ms.size / 5}", slot, "STARS", "Craques do time", now, auto = ms.size <= 1 || ms.size % 5 == 0))
            if (ms.size >= 5) out.add(Cand("moment|$slot|${ms.size}", slot, "MOMENT", "Momento do time (últimos 5)", now, auto = ms.size % 5 == 0))
            val round = num(f[K.ROUND]?.value)
            val matchAt = f[K.MATCH_AT]?.value?.toLongOrNull()
            val rival = f[K.RIVAL_TEAM]?.value
            if (round != null && rival != null && f[K.RIVAL_STRENGTH]?.value?.toIntOrNull() != null && f[K.MY_STRENGTH]?.value?.toIntOrNull() != null) {
                val soon = matchAt != null && matchAt > now && matchAt - now <= 24 * 3600_000L
                out.add(Cand("match|$slot|$round", slot, "MATCHDAY", "Dia de jogo: J$round vs $rival", now, auto = soon))
                val sectors = listOf(K.MY_ATK to K.RIVAL_ATK, K.MY_MID to K.RIVAL_MID, K.MY_DEF to K.RIVAL_DEF, K.MY_GOL to K.RIVAL_GOL)
                if (sectors.count { (a, b) -> f[a]?.value?.toIntOrNull() != null && f[b]?.value?.toIntOrNull() != null } >= 2)
                    out.add(Cand("cmp|$slot|$round", slot, "COMPARE", "Lado a lado: J$round vs $rival", now, auto = soon))
            }
        } else if (ms.isNotEmpty()) {
            val comp = Txt.key(f[K.COMPETITION]?.value.orEmpty())
            if (pos == 1) out.add(Cand("title|$slot|$comp", slot, "TITLE", "Campeão da liga", System.currentTimeMillis()))
            else if (pos != null && obj != null && pos <= obj) out.add(Cand("obj|$slot|$comp", slot, "OBJECTIVE", "Objetivo alcançado: ${pos}º lugar", System.currentTimeMillis()))
            out.add(Cand("season|$slot|$comp", slot, "SEASON", "Resumo da temporada", System.currentTimeMillis(), auto = false))
        }
        if (includeDismissed) return out
        val gone = dismissed(repo)
        return out.filter { it.id !in gone }
    }

    /**
     * Depois de Encerrar a leitura: cria as artes dos eventos NOVOS de cada slot (contratação, goleada, sequência, liderança, título, objetivo), uma de cada vez
     * (a IA gratuita atende uma por vez), no máximo 3 por rodada. Na 1ª vez só vale o que aconteceu nas últimas 24 h. Campanha/resumo só à mão.
     * Se o serviço de imagem falhar, para e deixa o evento para a próxima vez (não marca como visto). Devolve quantas criou.
     */
    suspend fun auto(ctx: Context): Int {
        migrate(ctx)
        if (ctx.getSharedPreferences("arts", Context.MODE_PRIVATE).getBoolean("auto_off", false)) return 0
        val repo = Repo(ctx)
        val firstRun = seen(repo) == null
        val seenSet = seen(repo) ?: mutableSetOf()
        val now = System.currentTimeMillis()
        var made = 0
        var failed = false
        loop@ for (slot in 1..4) {
            if (!slotOn(ctx, slot)) continue
            for (c in candidates(repo, slot)) {
                if (c.id in seenSet) continue
                if (!c.auto) { if (c.kind == "CAMP" || c.kind == "SEASON") seenSet.add(c.id); continue }
                if (firstRun && now - c.at > 24 * 3600_000L) { seenSet.add(c.id); continue }
                if (made >= 3) break@loop
                if (runCatching { make(ctx, c) }.getOrNull() != null) { made++; seenSet.add(c.id) } else { failed = true; break@loop }
            }
        }
        putSeen(repo, seenSet)
        if (made > 0) {
            val sp = ctx.getSharedPreferences("arts", Context.MODE_PRIVATE)
            sp.edit().putInt("new", sp.getInt("new", 0) + made).apply()
            Diag.log("Artes: $made nova(s) criada(s)")
            UiBus.bump()
        }
        if (failed) Diag.log("Artes: " + (ArtsAi.lastError ?: "falhou"))
        return made
    }

    fun unseenCount(ctx: Context): Int = ctx.getSharedPreferences("arts", Context.MODE_PRIVATE).getInt("new", 0)
    fun clearUnseen(ctx: Context) { ctx.getSharedPreferences("arts", Context.MODE_PRIVATE).edit().putInt("new", 0).apply() }
    fun autoOn(ctx: Context): Boolean = !ctx.getSharedPreferences("arts", Context.MODE_PRIVATE).getBoolean("auto_off", false)
    fun setAuto(ctx: Context, on: Boolean) { ctx.getSharedPreferences("arts", Context.MODE_PRIVATE).edit().putBoolean("auto_off", !on).apply() }

    /** Refaz a arte de um item com outra cena da IA ("Gerar outra"). */
    suspend fun regenerate(ctx: Context, item: Item): Item? = make(ctx, candOf(item), reroll = (item.at % 1000).toInt() + 1)

    private fun candOf(item: Item): Cand {
        val p = item.id.split("|")
        val key = if (item.kind == "BUY" || item.kind == "PLAYER") p.getOrNull(2) else null
        return Cand(item.id, item.slot, item.kind, item.title, item.at, key)
    }

    // ------------------------------------------------------------------ montar e desenhar
    private fun bmp(f: File?): Bitmap? = f?.takeIf { it.exists() }?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }

    /** Cria a arte (cena da IA + acabamento do app) e guarda. null = falhou (motivo em `ArtsAi.lastError`). */
    suspend fun make(ctx: Context, c: Cand, reroll: Int = 0): Item? {
        ArtsAi.lastError = null
        val repo = Repo(ctx)
        val b = draw(ctx, repo, c, reroll) ?: return null
        val f = File(dir(ctx), "${System.currentTimeMillis()}_${c.slot}.png")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        b.recycle()
        val item = Item(c.id, c.slot, c.kind, c.title, System.currentTimeMillis(), f.absolutePath)
        val all = items(repo).filter { it.id != c.id }
        val keep = (listOf(item) + all).take(60)
        for (old in all.drop(59)) runCatching { File(old.file).delete() }
        for (old in items(repo).filter { it.id == c.id }) runCatching { File(old.file).delete() }
        saveItems(repo, keep)
        UiBus.bump()
        return item
    }

    private fun posLabel(cat: String?, code: String?): String {
        val base = when (cat) { "GOL" -> "Goleiro"; "DEF" -> "Defensor"; "MEI" -> "Meio-campista"; "ATA" -> "Atacante"; else -> "" }
        return if (base.isNotEmpty() && !code.isNullOrBlank()) "$base ($code)" else base.ifEmpty { code.orEmpty() }
    }


    /** Capa de jornal: contratados recentes (até 3, ainda no elenco) com retrato fictício; fala do treinador é a única parte inventada. */
    private suspend fun news(ctx: Context, repo: Repo, c: Cand, f: Map<String, StoredField>, team: String, comp: String?, crest: Bitmap?, th: ArtsDraw.Theme, colors: String, ms: List<MatchEntity>, seed: Int): Bitmap? {
        val slot = c.slot
        val sure = confirmed(repo, slot)
        val mine = repo.dao.playersOf(slot).filter { it.owner == "MY" }
        val keys = mine.map { it.nameKey }.filter { it in sure }.toSet()
        val ledger = Ledger.parse(repo.dao.plan(slot, "ledger")?.json)
        val buys = ledger.filter { it.type == "buy" && it.key in keys }.sortedByDescending { it.at }.distinctBy { it.key }.take(3)
        if (buys.isEmpty()) return null
        val kit = ArtsAi.kitFor(ctx, repo, team, comp)
        val list = ArrayList<ArtsDraw.NewsPlayer>()
        val shortNames = ArrayList<String>()
        for (b in buys) {
            val p = mine.firstOrNull { it.nameKey == b.key } ?: continue
            val iso = FlagManual.all(repo)[b.key] ?: repo.dao.plan(slot, "natiso2")?.json?.let { runCatching { JSONObject(it).optString(b.key) }.getOrNull() }?.takeIf { it.isNotBlank() }
            val country = Flags.countryName(iso)
            val look = ArtsAi.lookFor(ctx, repo, p.name, p.age, country, posLabel(p.cat, p.posCode).ifBlank { null })
            val sc = ArtsAi.scene(ArtsAi.portraitPrompt(colors, kit, look), seed + list.size * 31)
            if (sc == null && list.isEmpty()) return null   // 1º retrato falhou: serviço fora do ar, tenta depois
            val face = sc?.let { Bitmap.createBitmap(it, (it.width * 0.12f).toInt(), (it.height * 0.04f).toInt(), (it.width * 0.76f).toInt(), (it.width * 0.76f).toInt().coerceAtMost((it.height * 0.9f).toInt())) }
            val line = listOfNotNull(p.age?.let { "$it ANOS" }, p.posCode, country?.uppercase()).joinToString(" • ")
            val stats = listOfNotNull(p.strength?.let { "FORÇA $it" }, p.valueText?.takeIf { it.isNotBlank() }?.let { "VALOR: $it" }).joinToString(" • ").ifBlank { "NOVO NO ELENCO" }
            list.add(ArtsDraw.NewsPlayer(p.name, line, stats, face))
            shortNames.add(p.name)
        }
        if (list.isEmpty()) return null
        val loc = java.util.Locale("pt", "BR")
        val now = System.currentTimeMillis()
        val date = java.text.SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy", loc).format(java.util.Date(now)).uppercase(loc)
        val my = f[K.MY_STRENGTH]?.value?.toIntOrNull(); val rv = f[K.RIVAL_STRENGTH]?.value?.toIntOrNull()
        val pos = num(f[K.LEAGUE_POS]?.value)
        val v = ms.count { it.result == "V" }; val e = ms.count { it.result == "E" }; val d = ms.count { it.result == "D" }
        val side = listOfNotNull(
            my?.let { "Força do elenco: $it" }, pos?.let { "Posição na liga: ${it}º" },
            if (ms.isNotEmpty()) "Campanha: ${v}V ${e}E ${d}D" else f[K.MY_VALUE]?.value?.let { "Valor do elenco: $it" }
        )
        val rival = f[K.RIVAL_TEAM]?.value
        val round = num(f[K.ROUND]?.value)
        val matchAt = f[K.MATCH_AT]?.value?.toLongOrNull()?.takeIf { it > 0 }
        val homeTxt = f[K.HOME]?.value?.lowercase()
        val whenTxt = matchAt?.let { java.text.SimpleDateFormat("dd/MM HH:mm", loc).format(java.util.Date(it)) }
        val names = if (shortNames.size == 1) shortNames[0] else shortNames.dropLast(1).joinToString(", ") + " e " + shortNames.last()
        val sub = "$team oficializa a chegada de $names"
        val subLine = if (rival != null) "Reforços já à disposição para o jogo contra $rival" + (round?.let { ", pela rodada $it" } ?: "") else "Reforços já à disposição para a sequência da temporada"
        val footer = if (rival != null) listOfNotNull("PRÓXIMO JOGO: ${team.uppercase()} x ${rival.uppercase()}", round?.let { "RODADA $it" }, whenTxt, homeTxt?.uppercase()).joinToString("  •  ")
        else "${team.uppercase()}  •  ${comp.orEmpty().uppercase()}".trim(' ', '•')
        val facts = listOfNotNull(
            my?.let { "força do time $it" }, rv?.let { "força do rival $it" }, f[K.MY_ATK]?.value?.let { "ataque $it" }, f[K.MY_MID]?.value?.let { "meio-campo $it" },
            f[K.MY_DEF]?.value?.let { "defesa $it" }, f[K.MY_GOL]?.value?.let { "goleiro $it" }, pos?.let { "${it}º lugar na liga" },
            if (ms.isNotEmpty()) "campanha ${v}V ${e}E ${d}D" else null, rival?.let { "próximo rival: $it" }, "reforços: $names"
        ).joinToString("; ")
        val fallback = "Chegamos fortes" + (my?.let { ", com força $it" } ?: "") + (rv?.let { " contra $it do rival" } ?: "") + ". Os reforços ($names) elevam o nível do grupo e vamos buscar os três pontos."
        val quote = ArtsAi.quote(ctx, facts, fallback)
        val paper = "JORNAL DO " + team.uppercase()
        val headB = comp?.takeIf { it.isNotBlank() }?.uppercase().orEmpty()
        val headA = if (headB.isNotEmpty()) "ESQUADRÃO REFORÇADO PARA A LIGA:" else "ESQUADRÃO REFORÇADO"
        val out = ArtsDraw.news(th, crest, paper, headA, headB, sub, subLine, side, "EDIÇÃO ESPECIAL • $date", list, "TREINADOR CONFIANTE", quote, footer)
        list.forEach { it.portrait?.recycle() }
        return out
    }


    /** Jornal pós-jogo: IA escreve a notícia com os fatos lidos (placar, rival, gols, forças, posição); a imagem e os números são do app. */
    private suspend fun postNews(ctx: Context, repo: Repo, c: Cand, f: Map<String, StoredField>, team: String, comp: String?, crest: Bitmap?, th: ArtsDraw.Theme, colors: String, ms: List<MatchEntity>, seed: Int): Bitmap? {
        val slot = c.slot
        val round = c.id.split("|").getOrNull(2)?.toIntOrNull()
        val m = ms.firstOrNull { it.round == round } ?: return null
        val opp = m.opponent?.takeIf { it.isNotBlank() } ?: return null
        val mine = m.scoreMine ?: return null; val theirs = m.scoreOpp ?: return null
        val res = m.result ?: return null
        val oppCrest = bmp(CrestWeb.find(ctx.filesDir, opp) ?: CrestCut.find(ctx.filesDir, opp))
        val j = repo.dao.plan(slot, "mr_R$round")?.json?.let { runCatching { JSONObject(it) }.getOrNull() }
        val mineHome = j?.takeIf { it.has("mineHome") }?.optBoolean("mineHome") ?: m.home
        // gols lidos do relatório: "23' (casa) gol de X (assistência: Y)"
        val goalLines = ArrayList<String>()
        val goalFacts = ArrayList<String>()
        val evs = j?.optJSONArray("events")
        if (evs != null) for (i in 0 until evs.length()) {
            val e = evs.optString(i)
            if (!e.contains(" gol de ")) continue
            val who = e.substringAfter(" gol de ").substringBefore(" (").trim()
            if (who.isBlank()) continue
            val min = Regex("^\\s*(\\d+(?:\\+\\d+)?)").find(e)?.groupValues?.get(1)
            val homeSide = e.contains("(casa)")
            val ours = if (mineHome == null) null else (homeSide == mineHome)
            val tag = when (ours) { true -> team; false -> opp; else -> "" }
            goalLines.add((min?.let { "$it' " } ?: "") + who + (if (tag.isNotEmpty()) " ($tag)" else ""))
            goalFacts.add((min?.let { "$it' " } ?: "") + "gol de $who" + (if (tag.isNotEmpty()) " ($tag)" else ""))
        }
        val my = f[K.MY_STRENGTH]?.value?.toIntOrNull(); val rv = f[K.RIVAL_STRENGTH]?.value?.toIntOrNull()
        val pos = num(f[K.LEAGUE_POS]?.value)
        val v = ms.count { it.result == "V" }; val e = ms.count { it.result == "E" }; val d = ms.count { it.result == "D" }
        val resWord = when (res) { "V" -> "vitória"; "E" -> "empate"; else -> "derrota" }
        val where = when (mineHome) { true -> "em casa"; false -> "fora de casa"; else -> null }
        val facts = listOfNotNull(
            "$team ${mine} x ${theirs} $opp ($resWord do $team${where?.let { " $it" } ?: ""})", "rodada $round", comp?.takeIf { it.isNotBlank() }?.let { "competição: $it" },
            pos?.let { "$team ocupa o ${it}º lugar na liga" }, "campanha do $team: ${v}V ${e}E ${d}D",
            if (my != null && rv != null) "força do $team $my contra $rv do $opp" else null,
            if (goalFacts.isNotEmpty()) "gols: " + goalFacts.joinToString("; ") else null
        ).joinToString(". ")
        val story = ArtsAi.matchStory(ctx, facts)
        val headline = story?.first ?: when (res) { "V" -> "$team vence o $opp por $mine a $theirs"; "D" -> "$team perde para o $opp por $theirs a $mine"; else -> "$team e $opp empatam em $mine a $theirs" }
        val sub = story?.second ?: "Pela rodada $round" + (comp?.let { " do $it" } ?: "") + (pos?.let { ", o time ocupa o ${it}º lugar" } ?: "") + "."
        val body = story?.third ?: listOf(
            "O $team ${when (res) { "V" -> "venceu"; "D" -> "perdeu"; else -> "empatou" }} com o $opp por $mine a $theirs${where?.let { " $it" } ?: ""}, pela rodada $round." +
                (if (goalFacts.isNotEmpty()) " Gols: " + goalFacts.joinToString(", ") + "." else ""),
            "A campanha do time na competição é de ${v} vitória(s), ${e} empate(s) e ${d} derrota(s)" + (if (my != null && rv != null) ", com força $my contra $rv do adversário no confronto." else ".")
        )
        val photo = ArtsAi.scene(ArtsAi.matchScenePrompt(res, colors), seed)
        val loc = java.util.Locale("pt", "BR")
        val date = java.text.SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy", loc).format(java.util.Date(m.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis())).uppercase(loc)
        val awayM = mineHome == false
        val out = ArtsDraw.matchNews(
            th, crest, "JORNAL DO " + team.uppercase(), date, headline, sub, body, photo,
            if (awayM) oppCrest else crest, if (awayM) crest else oppCrest, if (awayM) opp else team, if (awayM) team else opp,
            if (awayM) "$theirs × $mine" else "$mine × $theirs", goalLines,
            listOfNotNull("RODADA $round", comp?.uppercase(), pos?.let { "${it}º LUGAR" }, "CAMPANHA ${v}V ${e}E ${d}D").joinToString("  •  ")
        )
        photo?.recycle()
        return out
    }

    private suspend fun draw(ctx: Context, repo: Repo, c: Cand, reroll: Int): Bitmap? {
        val slot = c.slot
        val f = repo.fieldMap(slot)
        val team = f[K.TEAM]?.value ?: return null
        val comp = f[K.COMPETITION]?.value
        val crest = bmp(CrestWeb.find(ctx.filesDir, team) ?: CrestCut.find(ctx.filesDir, team))
        val th = theme(crest, slot)
        val colors = ArtsAi.colorWords(crest, slot)
        val ms = played(repo.dao.matchesOf(slot))
        val v = ms.count { it.result == "V" }; val e = ms.count { it.result == "E" }; val d = ms.count { it.result == "D" }
        val gf = ms.sumOf { it.scoreMine ?: 0 }; val ga = ms.sumOf { it.scoreOpp ?: 0 }
        val aprov = if (ms.isEmpty()) 0 else Math.round(100f * (3 * v + e) / (3 * ms.size))
        val last = ms.lastOrNull()
        val rec = "${v}V ${e}E ${d}D"
        val seed = (c.id.hashCode() and 0xFFFFF) + reroll * 104729
        var kit = ""; var look = ""
        var oppCrest: Bitmap? = null
        val banner: String; val big: String; val info: String; val plate: Pair<String, String>?; val foot: String
        when (c.kind) {
            "BUY", "PLAYER" -> {
                val key = c.key ?: return null
                val p = repo.dao.playersOf(slot).firstOrNull { it.owner == "MY" && it.nameKey == key } ?: return null
                val iso = FlagManual.all(repo)[key] ?: repo.dao.plan(slot, "natiso2")?.json?.let { runCatching { JSONObject(it).optString(key) }.getOrNull() }?.takeIf { it.isNotBlank() }
                val country = Flags.countryName(iso)
                kit = ArtsAi.kitFor(ctx, repo, team, comp)
                look = ArtsAi.lookFor(ctx, repo, p.name, p.age, country, posLabel(p.cat, p.posCode).ifBlank { null })
                banner = if (c.kind == "BUY") "CONTRATAÇÃO CONFIRMADA" else "DESTAQUE DO ELENCO"
                big = p.name.trim().split(Regex("\\s+")).last()
                info = listOfNotNull(country, p.age?.let { "$it anos" }, posLabel(p.cat, p.posCode).takeIf { it.isNotBlank() }).joinToString("  •  ")
                plate = p.strength?.let { "FORÇA" to it.toString() }
                foot = if (c.kind == "BUY") "Bem-vindo ao $team!" else "$team • orgulho do clube"
            }
            "POST" -> return postNews(ctx, repo, c, f, team, comp, crest, th, colors, ms, seed)
            "NEWS" -> return news(ctx, repo, c, f, team, comp, crest, th, colors, ms, seed)
            "GOLEADA" -> {
                val m = last ?: return null
                oppCrest = bmp(CrestWeb.find(ctx.filesDir, m.opponent) ?: CrestCut.find(ctx.filesDir, m.opponent))
                kit = ArtsAi.kitFor(ctx, repo, team, comp)
                val streak = ms.reversed().takeWhile { it.result == "V" }.size
                // quem joga em casa fica à ESQUERDA (placar e escudos)
                banner = "GOLEADA!"; big = if (m.home == false) "${m.scoreOpp ?: 0} × ${m.scoreMine ?: 0}" else "${m.scoreMine ?: 0} × ${m.scoreOpp ?: 0}"
                info = "J${m.round} • ${m.opponent.orEmpty()}"
                plate = if (streak >= 2) "SEQUÊNCIA" to "$streak vitórias" else "JOGO" to (if (m.home == true) "em casa" else if (m.home == false) "fora" else "—")
                foot = "Vamos pra cima, $team!"
            }
            "STREAK" -> {
                val streak = ms.reversed().takeWhile { it.result == "V" }.size
                banner = "EM CHAMAS!"; big = "$streak"; info = "VITÓRIAS SEGUIDAS"; plate = "APROVEITAMENTO" to "$aprov%"; foot = "Vamos pra cima, $team!"
            }
            "EXPECT" -> {
                val str = f[K.MY_STRENGTH]?.value
                val obj2 = num(f[K.MY_OBJECTIVE]?.value)
                val rows = League.table(repo.dao.plan(slot, "league")?.json?.let { runCatching { JSONObject(it) }.getOrNull() }, "va").filter { it.value != null }
                val rank = rows.sortedByDescending { it.value }.indexOfFirst { League.key(it.club) == League.key(team) }.takeIf { it >= 0 }?.plus(1)
                val tier = if (rank == null) null else if (rank <= maxOf(1, rows.size / 4)) "FAVORITO" else if (rank <= rows.size * 6 / 10) "NA BRIGA" else "AZARÃO"
                banner = "EXPECTATIVA DA TEMPORADA"
                big = tier ?: (obj2?.let { "META: TOP $it" } ?: "VAI COMEÇAR!")
                info = listOfNotNull(rank?.let { "${it}º elenco mais valioso de ${rows.size}" }, obj2?.let { "objetivo: ${it}º lugar" }, comp).joinToString("  •  ")
                plate = str?.let { "FORÇA DO TIME" to it }
                foot = "Que comece a temporada!"
            }
            "STARS" -> {
                val top = repo.dao.playersOf(slot).filter { it.owner == "MY" && it.strength != null }.sortedByDescending { it.strength }.take(4)
                val rowsS = top.map { ArtsDraw.Row(it.name + (it.posCode?.let { p -> "  •  $p" } ?: ""), it.strength.toString(), it.strength!! / 100f) }
                val scene = ArtsAi.scene(ArtsAi.prompt(c.kind, colors, ArtsAi.kitFor(ctx, repo, team, comp), ""), seed) ?: return null
                val outS = ArtsDraw.list(th, scene, crest, null, "CRAQUES DO TIME", rowsS, "Quem vai decidir a temporada!")
                scene.recycle(); return outS
            }
            "MOMENT" -> {
                val l5 = ms.takeLast(5)
                val a5 = if (l5.isEmpty()) 0 else Math.round(100f * (3 * l5.count { it.result == "V" } + l5.count { it.result == "E" }) / (3 * l5.size))
                val rowsM = listOf(ArtsDraw.Row("Aproveitamento", "$a5%", a5 / 100f), ArtsDraw.Row("Gols marcados × sofridos", "${l5.sumOf { it.scoreMine ?: 0 }} × ${l5.sumOf { it.scoreOpp ?: 0 }}"))
                val scene = ArtsAi.scene(ArtsAi.prompt(c.kind, colors, "", ""), seed) ?: return null
                val outM = ArtsDraw.list(th, scene, crest, null, "MOMENTO DO TIME", rowsM, "Cinco jogos, uma história!", dots = l5.joinToString("") { it.result.orEmpty() })
                scene.recycle(); return outM
            }
            "MATCHDAY", "COMPARE" -> {
                val rival = f[K.RIVAL_TEAM]?.value ?: return null
                oppCrest = bmp(CrestWeb.find(ctx.filesDir, rival) ?: CrestCut.find(ctx.filesDir, rival))
                val my = f[K.MY_STRENGTH]?.value?.toIntOrNull(); val rv = f[K.RIVAL_STRENGTH]?.value?.toIntOrNull()
                val round = num(f[K.ROUND]?.value)
                if (c.kind == "COMPARE") {
                    val away = f[K.HOME]?.value?.lowercase() == "fora"   // em casa fica à esquerda: fora, o rival vai para a esquerda
                    fun side(a: String, b: String, label: String): ArtsDraw.Row? {
                        val x = f[a]?.value?.toIntOrNull() ?: return null; val y = f[b]?.value?.toIntOrNull() ?: return null
                        val (l, r) = if (away) y to x else x to y
                        return ArtsDraw.Row(label, "$l × $r", l / (l + r).toFloat())
                    }
                    val rowsC = listOfNotNull(side(K.MY_ATK, K.RIVAL_ATK, "Ataque"), side(K.MY_MID, K.RIVAL_MID, "Meio-campo"), side(K.MY_DEF, K.RIVAL_DEF, "Defesa"), side(K.MY_GOL, K.RIVAL_GOL, "Goleiro")) +
                        listOfNotNull(if (my != null && rv != null) { val (l, r) = if (away) rv to my else my to rv; ArtsDraw.Row("Força geral", "$l × $r", l / (l + r).toFloat()) } else null)
                    val scene = ArtsAi.scene(ArtsAi.prompt(c.kind, colors, "", ""), seed) ?: return null
                    val outC = ArtsDraw.list(th, scene, if (away) oppCrest else crest, if (away) crest else oppCrest, "LADO A LADO", rowsC, "Quem leva a melhor?")
                    scene.recycle(); return outC
                }
                val prob = runCatching { Director.winProb(repo, slot) }.getOrNull()
                val home = f[K.HOME]?.value
                val awayM = f[K.HOME]?.value?.lowercase() == "fora"   // em casa à esquerda
                banner = "DIA DE JOGO"; big = if (my != null && rv != null) (if (awayM) "$rv × $my" else "$my × $rv") else "J${round ?: ""}"
                info = listOfNotNull(round?.let { "Rodada $it" }, home?.lowercase(), if (f[K.RIVAL_HUMAN]?.value == "Sim") "rival humano" else "rival CPU").joinToString("  •  ")
                plate = prob?.let { "CHANCE DE VITÓRIA" to "${it.win}%" }
                foot = "Hoje é dia de decidir!"
                val sceneV = ArtsAi.scene(ArtsAi.prompt(c.kind, colors, "", ""), seed) ?: return null
                val outV = ArtsDraw.poster(th, sceneV, if (awayM) oppCrest else crest, if (awayM) crest else oppCrest, banner, big, info, plate, foot, versus = true)
                sceneV.recycle(); return outV
            }
            "LEADER" -> { banner = "LÍDER DA LIGA!"; big = "1º"; info = "NA RODADA ${last?.round ?: ""}" + (comp?.let { " • $it" } ?: ""); plate = "CAMPANHA" to rec; foot = "No topo da tabela!" }
            "TITLE" -> { banner = "CAMPEÃO DA LIGA!"; big = "CAMPEÃO"; info = comp.orEmpty(); plate = "CAMPANHA" to rec; foot = "Obrigado, torcida!" }
            "OBJECTIVE" -> {
                val pos = num(f[K.LEAGUE_POS]?.value)
                banner = "OBJETIVO ALCANÇADO!"; big = "${pos ?: ""}º LUGAR"; info = comp.orEmpty(); plate = "CAMPANHA" to rec; foot = "Obrigado, torcida!"
            }
            else -> {   // CAMP, SEASON (à mão)
                if (ms.isEmpty()) return null
                banner = if (c.kind == "SEASON") "RESUMO DA TEMPORADA" else "CAMPANHA ATÉ AGORA"; big = rec
                info = (comp?.let { "$it  •  " } ?: "") + "gols $gf × $ga"; plate = "APROVEITAMENTO" to "$aprov%"
                foot = if (c.kind == "SEASON") "Obrigado, torcida!" else "A jornada continua!"
            }
        }
        val scene = ArtsAi.scene(ArtsAi.prompt(c.kind, colors, kit, look), seed) ?: return null
        val hero = c.kind in listOf("EXPECT", "STREAK", "LEADER", "CAMP", "SEASON")
        val awayG = c.kind == "GOLEADA" && last?.home == false
        val out = ArtsDraw.poster(th, scene, if (awayG) oppCrest else crest, if (awayG) crest else oppCrest, banner, big, info, plate, foot, heroCrest = hero)
        scene.recycle()
        return out
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
