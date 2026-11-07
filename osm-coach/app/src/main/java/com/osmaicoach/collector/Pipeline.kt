package com.osmaicoach.collector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

object OcrEngine {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun read(bmp: Bitmap): OcrResult {
        val res = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val lines = ArrayList<OcrLine>()
        val tokens = ArrayList<OcrToken>()
        for (block in res.textBlocks) {
            for (ln in block.lines) {
                val r = ln.boundingBox ?: continue
                if (Overlay.isChip(ln.text)) continue
                if (Overlay.covers((r.left + r.right) / 2f / w, (r.top + r.bottom) / 2f / h)) continue
                // Palavra a palavra: o OCR pode juntar o botão flutuante ("Encerrar") com o nome que está atrás
                // dele ("Encerraroden"). Tira as palavras que caem sobre os botões e refaz a linha sem elas.
                val toks = ArrayList<OcrToken>()
                var dropped = false
                for (e in ln.elements) {
                    val b = e.boundingBox ?: continue
                    val t = OcrToken(e.text, b.left / w, b.top / h, b.right / w, b.bottom / h)
                    if (Overlay.isChip(e.text) || Overlay.covers(t.xc, t.yc) || Overlay.glued(e.text)) {
                        dropped = true
                        continue
                    }
                    toks.add(t)
                }
                if (dropped) {
                    if (toks.isEmpty()) continue
                    lines.add(OcrLine(toks.joinToString(" ") { it.text }, toks.minOf { it.l }, toks.minOf { it.t }, toks.maxOf { it.r }, toks.maxOf { it.b }, toks))
                } else {
                    lines.add(OcrLine(ln.text, r.left / w, r.top / h, r.right / w, r.bottom / h, toks))
                }
                tokens.addAll(toks)
            }
        }
        return OcrResult(lines, tokens)
    }
}

object ImageIo {
    fun saveJpeg(ctx: Context, bmp: Bitmap, sessionId: String, at: Long): String {
        val dir = File(ctx.filesDir, "frames/$sessionId").apply { mkdirs() }
        val f = File(dir, "$at.jpg")
        val maxW = 1280
        val scaled = if (bmp.width > maxW) {
            Bitmap.createScaledBitmap(bmp, maxW, (maxW.toFloat() * bmp.height / bmp.width).toInt(), true)
        } else bmp
        FileOutputStream(f).use { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        if (scaled !== bmp) scaled.recycle()
        return f.absolutePath
    }

    /** Apaga imagens guardadas há mais de 30 dias (sem isso a pasta só crescia). */
    fun prune(ctx: Context) {
        val limit = System.currentTimeMillis() - 30L * 86400000L
        try {
            File(ctx.filesDir, "frames").listFiles()?.forEach { dir ->
                dir.listFiles()?.forEach { if (it.lastModified() < limit) it.delete() }
                if (dir.listFiles()?.isEmpty() == true) dir.delete()
            }
        } catch (e: Exception) {
            Diag.lastError = "Limpeza de imagens: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    fun toImg(bmp: Bitmap): PixelProbe.Img {
        val pw = 640
        val ph = (pw.toFloat() * bmp.height / bmp.width).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bmp, pw, ph, true)
        val px = IntArray(pw * ph)
        small.getPixels(px, 0, pw, 0, 0, pw, ph)
        if (small !== bmp) small.recycle()
        return PixelProbe.Img(pw, ph, px)
    }
}

/**
 * Event/Window -> validação OSM -> impressão digital -> classificação -> máquina de slot ->
 * OCR local -> parser determinístico -> merge persistente -> (fallback de IA em fila).
 */
class FramePipeline private constructor(private val ctx: Context) {
    private val repo = Repo(ctx)
    private val dao = repo.dao
    private val machine = SlotStateMachine()
    private var machineSession: String? = null
    private var candidate: LongArray? = null
    private var stable = 0
    private var unstable = 0
    private var lastProcessedAt = 0L
    private var dupCounted = false
    private var lastProcessed: LongArray? = null
    private val lastOwners = HashMap<ScreenType, Triple<Int, String, Long>>()
    private val noSlot = ArrayList<Pair<Extraction, Long>>()
    private var lastType: ScreenType? = null
    private var lastCheck = ""
    private var lastCheckAt = 0L

    /**
     * Tela de Tática do OSM: guarda o que está montado (juntando as rolagens da mesma rodada) e marca o resumo para o fim da
     * leitura. O aviso (confere / difere) só sai quando você deixa a tela: antes avisava a cada rolagem, com a tática meio lida.
     */
    private suspend fun checkTactic(slot: Int, ocr: OcrResult, now: Long) {
        val f = repo.fieldMap(slot)
        runCatching {
            val seen = Parsers.tacticRead(ocr)
            if (seen.length() > 0) {
                val round = f[K.ROUND]?.value?.toIntOrNull() ?: -1
                val old = dao.plan(slot, "tacmounted")?.json?.let { JSONObject(it) }?.takeIf { it.optInt("round", -2) == round && now - it.optLong("at") < 15 * 60_000L }
                val merged = old ?: JSONObject()
                for (k in seen.keys()) merged.put(k, seen.get(k))
                dao.putPlan(PlanEntity(slot, "tacmounted", merged.put("at", now).put("round", round).toString(), now))
            }
        }
        pending = Pending(slot, ScreenType.TACTIC, null)
    }

    /** O que está montado no OSM contra a tática gerada (formação, estilo, desarme com o árbitro, marcação). */
    private suspend fun tacticSummary(slot: Int, now: Long): String? {
        val p = dao.plan(slot, "tactic")
        val j = p?.json?.let { runCatching { JSONObject(it) }.getOrNull() }
        val f = repo.fieldMap(slot)
        val farAway = (f[K.MATCH_AT]?.value?.toLongOrNull() ?: 0L).let { it > 0 && it - now > Director.TACTIC_WINDOW_MS }
        if (p == null || j == null || !Director.tacticValid(j, p.at, f, now)) {
            // jogo longe (fora das 8 h): a tática ainda nem deveria existir, não cobra
            return if (farAway) null else "S$slot: sem tática gerada para este jogo. Gere no app antes de montar."
        }
        val m = dao.plan(slot, "tacmounted")?.json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val w = Parsers.tacticDiff(m, j, f[K.REFEREE]?.value)
        if (w.isEmpty()) {
            dao.putPlan(PlanEntity(slot, "tacticdone", JSONObject().put("at", p.at).toString(), now))
            return "✅ S$slot: a tática montada no OSM confere com a gerada."
        }
        return "⚠ S$slot: " + w.joinToString(" • ")
    }
    private val autoTacticAt = HashMap<Int, Long>()
    private val lastNote = HashMap<Int, Pair<String, Long>>()

    /** Aviso por cima do jogo; o mesmo texto não se repete em menos de 1 min por slot (avisos diferentes passam sempre). */
    private fun note(slot: Int, text: String, now: Long, ttl: Long = 60_000L) {
        val l = lastNote[slot]
        if (l != null && l.first == text && now - l.second < ttl) return
        lastNote[slot] = text to now
        CaptureService.instance?.notice(text)
    }

    /**
     * Gera a tática sozinho depois de ler o Pré-jogo ou a Análise do rival (só nas últimas horas antes do jogo e só se o
     * que importa mudou: rival, formação/estilo dele, força, árbitro, treino secreto). Avisa por cima do jogo quando
     * ficou pronta, para montar no OSM na hora.
     */
    /**
     * Compara o que o rival mostra agora (formação, estilo, marcação, fora-de-jogo, desarme, pressão, estilo, ritmo) com a
     * última leitura deste mesmo jogo e diz se ele MUDOU ou MANTEVE a tática. Vale em qualquer hora antes do jogo.
     */
    private suspend fun rivalDelta(slot: Int, f: Map<String, StoredField>, now: Long): String? {
        val keys = listOf(K.RIVAL_FORMATION, K.RIVAL_PLAN, K.RIVAL_MARKING, K.RIVAL_OFFSIDE, K.RIVAL_TACKLE, K.RIVAL_PRESSURE, K.RIVAL_MENTALITY, K.RIVAL_TEMPO)
        val labels = listOf("formação", "estilo", "marcação", "fora-de-jogo", "desarme", "pressão", "estilo (nível)", "ritmo")
        val cur = keys.map { f[it]?.value?.takeIf { v -> v.isNotBlank() && FieldMerge.known(v) }.orEmpty() }
        if (cur.count { it.isNotBlank() } < 2) return null
        val tag = (f[K.ROUND]?.value ?: "") + "|" + Txt.key(f[K.RIVAL_TEAM]?.value ?: "")
        val oldJ = repo.dao.plan(slot, "rivalsnap")?.json?.let { runCatching { JSONObject(it) }.getOrNull() }
        val sameGame = oldJ?.optString("tag") == tag
        // leitura feita há pouco, na mesma passada (fp): ainda é a primeira leitura, não dá para dizer "manteve"
        val firstAt = oldJ?.optLong("first", 0L) ?: 0L
        val inFirstPass = sameGame && oldJ?.optBoolean("fp", false) == true && now - firstAt < 10 * 60_000L
        val old = if (sameGame && !inFirstPass) (0 until keys.size).map { oldJ!!.optJSONArray("v")?.optString(it).orEmpty() } else null
        val prev = if (sameGame) (0 until keys.size).map { oldJ!!.optJSONArray("v")?.optString(it).orEmpty() } else null
        val merged = cur.mapIndexed { i, v -> if (v.isNotBlank()) v else old?.get(i).orEmpty() }
        val diffs = ArrayList<String>()
        if (old != null) for (i in keys.indices) if (cur[i].isNotBlank() && old[i].isNotBlank() && !sameRivalValue(keys[i], cur[i], old[i])) diffs.add("${labels[i]} ${old[i]} → ${cur[i]}")
        if (prev == null || merged != prev || (inFirstPass.not() && oldJ?.optBoolean("fp", false) == true)) {
            val fresh = !sameGame
            repo.dao.putPlan(PlanEntity(slot, "rivalsnap", JSONObject().put("tag", tag).put("v", org.json.JSONArray(merged)).put("at", now)
                .put("first", if (fresh) now else firstAt).put("fp", fresh || inFirstPass).toString(), now))
        }
        val head = merged.getOrNull(0).orEmpty() + (merged.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { " • $it" } ?: "")
        return when {
            diffs.isNotEmpty() -> "⚠ S$slot: o rival MUDOU a tática: " + diffs.take(3).joinToString("; ") + ". Gere a tática de novo."
            old != null -> "✅ S$slot: o rival MANTEVE a tática ($head)."
            else -> "👀 S$slot: tática do rival lida ($head). Passe de novo perto do jogo para ver se ele muda."
        }
    }

    /** Mesmo valor apesar do ruído do OCR: formação/estilo canônicos, números ±4 (pressão/ritmo), texto por similaridade. */
    private fun sameRivalValue(k: String, a: String, b: String): Boolean {
        if (a.equals(b, ignoreCase = true)) return true
        if (k == K.RIVAL_FORMATION || k == K.RIVAL_PLAN || k == K.RIVAL_MARKING || k == K.RIVAL_TACKLE) return !SigCompare.differs(k, a, b)
        val na = a.filter { it.isDigit() }.toIntOrNull(); val nb = b.filter { it.isDigit() }.toIntOrNull()
        if (na != null && nb != null && a.length <= 5 && b.length <= 5) return kotlin.math.abs(na - nb) <= 4
        return Txt.sim(Txt.key(a), Txt.key(b)) >= 0.82
    }

    /** Tela que está sendo lida (várias rolagens): o resumo só sai quando você passa para outra tela. */
    private class Pending(val slot: Int, val type: ScreenType, val round: Int?)
    private var pending: Pending? = null

    /** Marca a tela como "em leitura" (o resumo é montado em `flushPending`, com tudo o que já foi guardado dela). */
    private fun screenNote(slot: Int, type: ScreenType, ex: Extraction) {
        when (type) {
            ScreenType.RESULT -> ex.matchReport?.let { pending = Pending(slot, type, it.round) }
            ScreenType.CALENDAR -> if (ex.matches.size >= 3) pending = Pending(slot, type, null)
            ScreenType.SQUAD -> if (ex.players.size >= 3 && ex.ownerTeam != null) pending = Pending(slot, type, null)
            ScreenType.MARKET -> if (ex.listings.size >= 3) pending = Pending(slot, type, null)
            else -> {}
        }
    }

    /** Emite o resumo da tela que você acabou de deixar (ou ao encerrar a captura). */
    suspend fun flushPending() {
        val pd = pending ?: return
        pending = null
        val now = System.currentTimeMillis()
        val slot = pd.slot
        val f = repo.fieldMap(slot)
        val txt: String? = when (pd.type) {
            ScreenType.RESULT -> {
                val rd = pd.round ?: ResultCtx.round ?: f[K.ROUND_DONE]?.value?.toIntOrNull()
                val j = rd?.let { repo.dao.plan(slot, "mr_R$it")?.json?.let { s -> runCatching { JSONObject(s) }.getOrNull() } }
                if (rd == null || j == null) null
                else {
                    val st = j.optJSONObject("stats")?.length() ?: 0
                    val rt = (j.optJSONArray("rh")?.length() ?: 0) + (j.optJSONArray("ra")?.length() ?: 0)
                    val ev = j.optJSONArray("events")?.length() ?: 0
                    if (!j.has("sh")) "S$slot: Resultado da J$rd: não li o placar. Abra de novo e role a tela inteira (placar, estatísticas, notas)."
                    else {
                        val lacks = buildList { if (st < 7) add("estatísticas (role até Zonas de ação)"); if (rt == 0) add("notas dos jogadores") }
                        "✅ S$slot: Resultado da J$rd lido: ${j.optInt("sh")}-${j.optInt("sa")} • $ev eventos • $st estatísticas • $rt notas." +
                            (if (lacks.isEmpty()) " Completo: o aprendizado foi atualizado." else " Falta: " + lacks.joinToString(", ") + ".")
                    }
                }
            }
            ScreenType.CALENDAR -> {
                val all = repo.dao.matchesOf(slot)
                val lag = FormRead.lag(all, f[K.ROUND_DONE]?.value?.toIntOrNull())
                "✅ S$slot: Calendário lido: ${all.size} jogos, ${all.count { it.result != null }} com resultado." +
                    (if (lag >= 2) " Faltam as últimas $lag rodadas: role até o fim." else " Em dia.")
            }
            ScreenType.SQUAD -> {
                val mine = repo.playersOf(slot).filter { it.owner == "MY" }
                "✅ S$slot: Plantel lido: ${mine.size} jogadores guardados." + (if (mine.size < 18) " Role a lista inteira para ler todos." else "")
            }
            ScreenType.MARKET -> "✅ S$slot: Mercado lido: ${repo.dao.listingsOf(slot).size} jogadores à venda guardados."
            ScreenType.TACTIC -> tacticSummary(slot, now)
            else -> null
        }
        if (txt != null) { Diag.log("Resumo da tela → $txt"); note(slot, txt, now) }
    }

    private fun why(slot: Int, text: String) { Diag.log("Tática automática S$slot: $text") }

    private suspend fun autoTactic(slot: Int, now: Long, type: ScreenType? = null) {
        if (Settings.get(ctx, "auto_tactic", "1") != "1") { why(slot, "desligada em Ajustes"); return }
        val f = repo.fieldMap(slot)
        val st = f[K.SLOT_STATE]?.value
        if (!(st == null || st == "ativo") || f[K.TEAM]?.value == null) { why(slot, "slot sem competição ativa lida"); return }
        val matchAt = f[K.MATCH_AT]?.value?.toLongOrNull()
        if (matchAt == null) { why(slot, "horário do jogo não lido"); return }
        if (matchAt <= now + 60_000L) { why(slot, "jogo já começou"); return }
        if (Fixtures.seasonEnded(f, repo.dao.matchesOf(slot), now)) return
        // "Mudou/manteve" só logo depois de LER a tela de Análise (últimos 90 s); em qualquer outra passada vale o checklist.
        val secret = SecretTraining.on(f)
        // a Análise tem 2 telas (1ª: formação; 2ª: estilo, marcação…): só compara quando as DUAS foram lidas nesta passada (antes cada tela avisava sozinha: "mudou", "manteve", "mudou" em 6 s)
        val justRead = type == ScreenType.REPORT && listOf(K.RIVAL_FORMATION, K.RIVAL_PLAN).all { k -> f[k]?.let { FieldMerge.known(it.value) && now - it.updatedAt <= 150_000L } == true }
        val gaps = Director.tacticGaps(f, now)
        val check = Director.checklist(f, now)
        val delta = if (justRead) runCatching { rivalDelta(slot, f, now) }.getOrNull() else null
        delta?.let { why(slot, it.substringAfter(": ")) }
        if (gaps.isNotEmpty()) {
            why(slot, "faltam leituras: " + gaps.joinToString("; ") { it.first })
            // longe do jogo (fora das 8 h) não há o que fazer agora: sem mensagem ao entrar no slot
            if (matchAt - now > Director.TACTIC_WINDOW_MS && delta == null) return
            val missing = Director.groups(f, now).filter { !it.ok }
            val read = when (type) { ScreenType.PREGAME -> "Pré-jogo"; ScreenType.REPORT -> "Análise do rival"; ScreenType.SQUAD -> "Plantel do rival"; else -> null }
            note(slot, (delta ?: ("S$slot:" + (read?.let { " $it lido." } ?: ""))) + " Falta ler: " +
                missing.joinToString(" • ") { it.name.substringBefore(" (") + " (" + it.missing.joinToString(", ").take(60) + ")" } + ". Veja o caminho em Hoje → Para fazer.", now, 5 * 60_000L)
            return
        }
        if (matchAt - now > Director.TACTIC_WINDOW_MS) {
            delta?.let { note(slot, it, now) }
            return
        }
        if (GenState.running("tactic", slot) || GenState.running("tactic_ai", slot)) return
        if (now - (autoTacticAt[slot] ?: 0L) < 25_000L) return
        val sigKeys = listOf(
            K.ROUND, K.RIVAL_TEAM, K.RIVAL_NICK, K.RIVAL_STRENGTH, K.MY_STRENGTH, K.RIVAL_FORMATION, K.RIVAL_PLAN, K.RIVAL_PRESSURE,
            K.RIVAL_MENTALITY, K.RIVAL_TEMPO, K.RIVAL_MARKING, K.RIVAL_OFFSIDE, K.RIVAL_TACKLE, K.RIVAL_SECRET, K.REFEREE, K.HOME
        )
        val sig = sigKeys.joinToString("|") { f[it]?.value.orEmpty() }
        val old = repo.dao.plan(slot, "autogen")?.json?.let { runCatching { JSONObject(it) }.getOrNull() }
        val p = repo.dao.plan(slot, "tactic")
        val valid = p != null && runCatching { Director.tacticValid(JSONObject(p.json), p.at, f, now) }.getOrDefault(false)
        // o que mudou desde a última geração (força varia ±1 sozinha: não conta); sem registro anterior = tudo novo
        val oldParts = old?.optString("sig")?.takeIf { it.isNotBlank() }?.split("|")
        val changed = if (oldParts == null) sigKeys else sigKeys.filterIndexed { i, k -> SigCompare.differs(k, oldParts.getOrNull(i).orEmpty(), f[k]?.value.orEmpty()) }
        val same = oldParts != null && changed.isEmpty()
        val rivalTacticKeys = setOf(K.RIVAL_FORMATION, K.RIVAL_PLAN, K.RIVAL_PRESSURE, K.RIVAL_MENTALITY, K.RIVAL_TEMPO, K.RIVAL_MARKING, K.RIVAL_OFFSIDE, K.RIVAL_TACKLE, K.RIVAL_SECRET)
        // "o rival mudou a tática" só quando a ANÁLISE dele mudou; força, árbitro, bônus etc. são "dados do jogo"
        // "o rival mudou a tática" só com a prova da Análise: duas leituras dela no MESMO jogo e diferentes (rivalDelta, "⚠ … MUDOU")
        val rivalChanged = valid && delta?.startsWith("⚠") == true
        val labels = mapOf(K.RIVAL_STRENGTH to "força do rival", K.MY_STRENGTH to "sua força", K.REFEREE to "árbitro", K.HOME to "casa/fora", K.RIVAL_NICK to "apelido do rival", K.RIVAL_TEAM to "adversário", K.ROUND to "rodada")
        val dataChanged = changed.filter { it !in rivalTacticKeys }.mapNotNull { labels[it] }.distinct()
        // mesma leitura e tática válida: nada mudou. Mesma leitura sem tática válida (falhou antes): tenta de novo só após 5 min.
        if (same && valid) {
            // só vira "conferido" quando a Análise foi lida AGORA (justRead); entrar no slot não confere o rival
            val chk = if (justRead) now else old?.optLong("chk", 0L)?.takeIf { it > 0L }
            repo.dao.putPlan(PlanEntity(slot, "autogen", JSONObject().put("sig", sig).put("at", old?.optLong("at") ?: now).apply { chk?.let { put("chk", it) } }.toString(), now))
            // só avisa quando acabou de ler a Análise; entrar no slot não gera mensagem (o cartão já mostra o estado da tática)
            if (justRead) note(slot, (delta ?: "✅ S$slot: Análise lida.") + " A tática gerada continua valendo.", now)
            return
        }
        if (same && now - (old?.optLong("at") ?: 0L) < 300_000L) return
        repo.dao.putPlan(PlanEntity(slot, "autogen", JSONObject().put("sig", sig).put("at", now).toString(), now))
        autoTacticAt[slot] = now
        val why1 = when {
            rivalChanged -> "o rival mudou a tática; gerando de novo…"
            valid && dataChanged.isNotEmpty() -> "mudou ${dataChanged.joinToString(", ")}; gerando a tática de novo…"
            valid -> "leituras do rival atualizadas; gerando a tática de novo…"
            else -> "gerando a tática…"
        }
        CaptureService.instance?.notice("S$slot: $why1")
        startGeneration(ctx, "tactic", slot)
        AppScope.scope.launch {
            var waited = 0
            while (GenState.running("tactic", slot) && waited < 150) { delay(1000L); waited++ }
            val job = GenState.get("tactic", slot)
            val j = repo.dao.plan(slot, "tactic")?.json?.let { runCatching { JSONObject(it) }.getOrNull() }
            val msg = if (job?.ok == true && j != null) {
                "✅ S$slot: tática pronta" + (if (rivalChanged) " (rival mudou)" else if (valid && dataChanged.isNotEmpty()) " (${dataChanged.joinToString(", ")} mudou)" else "") + ": ${j.optString("formation")} • ${j.optString("playStyle")}. Monte no OSM: toque em 📱 App ou abra a tela de Tática."
            } else "⚠ S$slot: não consegui gerar a tática sozinho" + (job?.error?.let { ": $it" } ?: "") + ". Gere no app."
            CaptureService.instance?.notice(msg)
            // o rival pode ter mudado enquanto gerava (Análise lida logo depois do Pré-jogo): confere de novo
            runCatching { autoTactic(slot, System.currentTimeMillis()) }
        }
    }

    private var seasonSlot: Int? = null
    private var seasonSlotAt = 0L
    private var reportCandidates = 0
    private var resultSaved = 0
    private var pregameSaved = 0
    private var squadSaved = 0
    private var seasonSaved = 0
    private var tacticSaved = 0
    private var calendarSaved = 0
    private var lastOsmAcceptedAt = 0L
    private val pregameAiAt = HashMap<Int, Long>()
    private var imagesThisSession = 0
    private val mutex = Mutex()

    companion object {
        @Volatile
        private var inst: FramePipeline? = null

        fun get(ctx: Context): FramePipeline = inst ?: synchronized(this) {
            inst ?: FramePipeline(ctx.applicationContext).also { inst = it }
        }
    }

    init {
        Diag.init(ctx)
        ImageIo.prune(ctx)
    }

    fun nextDelayMs(): Long = if (System.currentTimeMillis() - lastOsmAcceptedAt < 15000L) 600L else 1800L

    private fun ensureMachine(sessionId: String) {
        if (machineSession == sessionId) return
        machineSession = sessionId
        candidate = null
        stable = 0
        unstable = 0
        lastProcessed = null
        lastOwners.clear()
        reportCandidates = 0
        tacticSaved = 0
        calendarSaved = 0
        resultSaved = 0
        pregameSaved = 0
        squadSaved = 0
        seasonSaved = 0
        imagesThisSession = 0
        val p = ctx.getSharedPreferences("machine", Context.MODE_PRIVATE)
        if (p.getString("session", null) == sessionId) {
            // processo foi morto no meio da sessão: restaura o contexto do slot
            val hub = ArrayList<SlotIdentity>()
            try {
                val arr = JSONArray(p.getString("hub", "[]") ?: "[]")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val names = HashSet<String>()
                    val na = o.getJSONArray("names")
                    for (j in 0 until na.length()) names.add(na.getString(j))
                    val r = o.optInt("round", -1)
                    hub.add(SlotIdentity(o.getInt("slot"), names, if (r >= 0) r else null))
                }
            } catch (e: Exception) {
                Diag.lastError = "Restaurar estado: " + (e.message ?: "erro")
            }
            val cur = p.getInt("current", -1)
            machine.restore(if (cur in 1..4) cur else null, p.getBoolean("awaiting", false), hub)
        } else {
            machine.restore(null, false, emptyList())
            saveMachine(sessionId)
        }
    }

    private fun saveMachine(sessionId: String) {
        val arr = JSONArray()
        for (h in machine.hubIdentities) {
            val o = JSONObject()
            o.put("slot", h.slot)
            o.put("names", JSONArray(h.names.toList()))
            o.put("round", h.roundNext ?: -1)
            arr.put(o)
        }
        ctx.getSharedPreferences("machine", Context.MODE_PRIVATE).edit()
            .putString("session", sessionId)
            .putInt("current", machine.current ?: -1)
            .putBoolean("awaiting", machine.awaitingEntry)
            .putString("hub", arr.toString())
            .apply()
    }

    private suspend fun parse(ocr: OcrResult, type: ScreenType, img: PixelProbe.Img, now: Long): Extraction = when (type) {
        ScreenType.HUB -> Parsers.hub(ocr)
        ScreenType.PREGAME -> Parsers.pregame(ocr, img, now)
        ScreenType.SQUAD -> Parsers.squad(ocr, img)
        ScreenType.CALENDAR -> Parsers.calendar(ocr, img)
        ScreenType.MARKET -> Parsers.market(ocr)
        ScreenType.REPORT -> Parsers.report(ocr)
        ScreenType.STADIUM -> Parsers.stadium(ocr, img, now)
        ScreenType.RESULT -> Parsers.matchResult(ocr, img)
        ScreenType.TRAINING -> Extraction(ScreenType.TRAINING, trainees = Parsers.training(ocr, now))
        ScreenType.PREP -> Extraction(ScreenType.PREP, extra = Screens.prep(ocr, img))
        ScreenType.DOCTOR, ScreenType.LAWYER -> Extraction(type, extra = Screens.roster(ocr))
        ScreenType.SPECIALISTS -> Extraction(ScreenType.SPECIALISTS, extra = Screens.specialists(ocr))
        ScreenType.SCOUT -> Extraction(ScreenType.SCOUT, extra = Screens.scout(ocr))
        ScreenType.DEALS -> Extraction(ScreenType.DEALS, extra = Screens.deals(ocr))
        ScreenType.STATS -> Extraction(ScreenType.STATS, extra = Screens.stats(ocr))
        ScreenType.MISSIONS -> Extraction(ScreenType.MISSIONS, extra = Screens.missions(ocr))
        ScreenType.SPONSORS -> Extraction(ScreenType.SPONSORS)
        else -> Extraction(type)
    }

    private fun typeLabel(t: ScreenType): String = when (t) {
        ScreenType.HUB -> "Central dos slots"
        ScreenType.PREGAME -> "Pré-jogo"
        ScreenType.SQUAD -> "Elenco"
        ScreenType.CALENDAR -> "Calendário"
        ScreenType.MARKET -> "Mercado"
        ScreenType.TRAINING -> "Treinamento"
        ScreenType.TACTIC -> "Tática"
        ScreenType.REPORT -> "Relatório"
        ScreenType.STADIUM -> "Estádio"
        ScreenType.RESULT -> "Resultado do jogo"
        ScreenType.PREP -> "Preparação para o jogo"
        ScreenType.DOCTOR -> "Médico"
        ScreenType.LAWYER -> "Advogado"
        ScreenType.SPECIALISTS -> "Especialistas"
        ScreenType.SPONSORS -> "Patrocinadores"
        ScreenType.SCOUT -> "Olheiro"
        ScreenType.DEALS -> "Negociações"
        ScreenType.STATS -> "Estatísticas da liga"
        ScreenType.MISSIONS -> "Missões"
        else -> "Outra tela do OSM"
    }

    /**
     * Recorta o escudo de cada card da central para mostrar na interface. Time novo no card (liga nova) troca o
     * escudo na hora; o mesmo time renova no máx. 1x a cada 6 h. Slot vazio/procurando não tem escudo: fica o antigo.
     */
    private fun saveCrests(bmp: Bitmap, cards: List<HubCard>) {
        val dir = File(ctx.filesDir, "crests").apply { mkdirs() }
        for (card in cards) {
            if (card.state != null && card.state != "preepoca") continue
            val slot = card.slot
            val f = File(dir, "s$slot.png")
            val tag = File(dir, "s$slot.txt")
            val team = Txt.key(card.team)
            val sameTeam = team.isNotEmpty() && tag.exists() && runCatching { tag.readText() }.getOrNull() == team
            if (f.exists() && sameTeam && System.currentTimeMillis() - f.lastModified() < 6L * 3600L * 1000L) continue
            val cx = (if (slot % 2 == 1) 0.5867f else 0.7953f) + card.dx
            val cy = (if (slot <= 2) 0.263f else 0.711f) + card.dy
            val x = ((cx - 0.04f) * bmp.width).toInt().coerceIn(0, bmp.width - 2)
            val y = ((cy - 0.09f) * bmp.height).toInt().coerceIn(0, bmp.height - 2)
            val w = (0.08f * bmp.width).toInt().coerceAtMost(bmp.width - x)
            val hh = (0.18f * bmp.height).toInt().coerceAtMost(bmp.height - y)
            if (w < 8 || hh < 8) continue
            try {
                val crop = Bitmap.createBitmap(bmp, x, y, w, hh)
                FileOutputStream(f).use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
                crop.recycle()
                if (team.isNotEmpty()) tag.writeText(team)
            } catch (e: Exception) {
                Diag.lastError = "Escudo: " + (e.message ?: "erro")
            }
        }
    }

    suspend fun onShot(sessionId: String, bmp: Bitmap) {
        mutex.withLock {
            ensureMachine(sessionId)
            Diag.lastShotAt = System.currentTimeMillis()

            val tiny = Bitmap.createScaledBitmap(bmp, FrameHash.W, FrameHash.H, true)
            val tpx = IntArray(FrameHash.W * FrameHash.H)
            tiny.getPixels(tpx, 0, FrameHash.W, 0, 0, FrameHash.W, FrameHash.H)
            if (tiny !== bmp) tiny.recycle()
            val h = FrameHash.of(tpx)

            // O screenshot é nítido mesmo durante a rolagem: processa todo quadro que mudou o bastante.
            // Perto do último processamento (< 2,5 s) exige mudança maior, para não repetir OCR em tela animada.
            val nowGate = System.currentTimeMillis()
            val lp = lastProcessed
            val dist = if (lp == null) Int.MAX_VALUE else FrameHash.distance(lp, h)
            // Pré-jogo alterna sozinho força <-> bônus nos círculos (mudança pequena na imagem): relê a cada 3 s.
            val need = if (lastType == ScreenType.PREGAME && nowGate - lastProcessedAt >= 3000L) 1
            else if (nowGate - lastProcessedAt < 2500L) 60 else 24
            if (dist < need) {
                if (!dupCounted) {
                    Diag.dedup.incrementAndGet()
                    dupCounted = true
                }
                return
            }
            dupCounted = false

            val now = System.currentTimeMillis()
            val img = ImageIo.toImg(bmp)
            val topBar = PixelProbe.isOsmTopBar(img)
            // Telas cheias do jogo sem a barra superior (ex.: análise do rival) ainda são do OSM: se o jogo estava
            // em primeiro plano há pouco, lê o texto e decide por ele (anúncios não têm o vocabulário do jogo).
            val recentlyOsm = now - lastOsmAcceptedAt < 90000L
            if (!topBar && !PixelProbe.hubBackdrop(img) && !recentlyOsm) {
                Diag.discarded.incrementAndGet()
                Diag.currentType = ScreenType.NON_OSM.name
                Diag.log("Descartada: não é tela do OSM (anúncio ou outro app)")
                lastProcessed = h
                return
            }

            runCatching { OsmThumb.set(bmp) }   // miniatura do OSM para achar o cartão nos Recentes ao fechar
            val ocr = OcrEngine.read(bmp)
            lastProcessedAt = now
            Diag.ocr.incrementAndGet()
            // Tela do próprio OSM Coach (barra "Hoje/Sessões/Slots/Diretor/Ajustes" ou textos dele): não é o jogo.
            if (OwnApp.looksLikeApp(ocr.fullText)) {
                Diag.discarded.incrementAndGet()
                Diag.log("Descartada: tela do próprio OSM Coach")
                lastProcessed = h
                return
            }
            val type = ScreenClassifier.classify(ocr, topBar)
            Diag.currentType = type.name
            lastType = type
            if (type != ScreenType.NOISE && type != ScreenType.NON_OSM) {
                // texto lido com a posição de cada linha: é o que permite conferir depois o que o app viu
                // (telas do fim do jogo, plantel inteiro, pré-jogo com os bônus)
                Diag.trace("OCR ${type.name} S${Diag.currentSlot}: " + ocr.lines.joinToString(" | ") {
                    it.text.trim() + "@" + "%.2f,%.2f".format(java.util.Locale.US, it.xc, it.yc)
                }.take(4000))
            }
            lastProcessed = h
            if (type == ScreenType.NOISE || type == ScreenType.NON_OSM) {
                Diag.discarded.incrementAndGet()
                Diag.log(if (type == ScreenType.NOISE) "Descartada: transição/tela vazia" else "Descartada: sem barra do OSM")
                return
            }
            // passou para outra tela: fecha o resumo da anterior (Resultado, Calendário, Plantel, Mercado, Tática)
            if (pending != null && pending?.type != type) runCatching { flushPending() }
            Diag.valid.incrementAndGet()
            lastOsmAcceptedAt = now

            var ex = parse(ocr, type, img, now)
            Diag.parsed.incrementAndGet()

            // Rolando a lista o cabeçalho some: reaproveita o dono lido por último (mesmo tipo e slot, até 10 min).
            if (type == ScreenType.REPORT) {
                val rn = machine.current?.let { repo.fieldMap(it)[K.RIVAL_TEAM]?.value }
                val mentions = Parsers.isAnalysis(ocr) ||
                    (rn != null && Txt.key(rn).length >= 4 && Txt.key(ocr.fullText).contains(Txt.key(rn)))
                if (!mentions) ex = Extraction(ScreenType.REPORT, needsAi = true, note = "sem nome do rival")
            }
            if (ex.ownerTeam == null && (type == ScreenType.SQUAD || type == ScreenType.CALENDAR)) {
                val lo = lastOwners[type]
                if (lo != null && now - lo.third < 600000L && lo.first == (machine.current ?: -1)) {
                    ex = ex.copy(ownerTeam = lo.second)
                }
            }
            // Trocou de tipo de tela: o dono da lista anterior deixa de valer.
            if (type == ScreenType.HUB || type == ScreenType.PREGAME || type == ScreenType.MARKET) {
                lastOwners.clear()
            } else if (type == ScreenType.SQUAD) {
                lastOwners.remove(ScreenType.CALENDAR)
            } else if (type == ScreenType.CALENDAR) {
                lastOwners.remove(ScreenType.SQUAD)
            }

            val known = repo.knownIdentities()
            var asg = if (type == ScreenType.HUB) {
                machine.onHub(ex.hubCards)
            } else {
                machine.onScreen(type, ex.teamCandidates, ex.roundRead, ex.ownerTeam, known)
            }
            // Pré-jogo do MEU time (apelido ao lado) que não bate com nenhum slot: é a competição nova do slot que está
            // em pré-época / procurando adversário / livre (batalha nova com outro clube). Antes ficava "sem identidade"
            // e nada do slot novo era lido.
            if (asg.slot == null && type == ScreenType.PREGAME && ex.fields.containsKey(K.TEAM)) {
                val team = ex.fields[K.TEAM]?.value
                val open = if (team != null) repo.openSlotFor(team, ocr.fullText) else null
                if (open != null) {
                    repo.startCompetition(open, team!!, now)
                    machine.adopt(open)
                    asg = Assignment(open, 0.8, "pré-jogo da competição nova")
                    Diag.log("Competição nova reconhecida: $team → S$open")
                }
            }
            saveMachine(sessionId)
            Diag.currentSlot = machine.current ?: 0

            val own = ex.ownerTeam
            val cur = asg.slot
            if (own != null && cur != null && (type == ScreenType.SQUAD || type == ScreenType.CALENDAR)) {
                lastOwners[type] = Triple(cur, own, now)
            }

            if (type == ScreenType.HUB) saveCrests(bmp, ex.hubCards)
            // Telas de dados lidas antes de o slot ser reconhecido (entrou no slot por uma tela sem nome do time):
            // ficam guardadas e vão para o slot assim que ele é identificado, sem passar pela central no meio.
            val asgSlot = asg.slot
            if (type == ScreenType.HUB) {
                noSlot.clear()
            } else if (asgSlot == null && (type == ScreenType.REPORT || type == ScreenType.PREGAME || type == ScreenType.SQUAD)) {
                noSlot.add(Pair(ex, now))
                while (noSlot.size > 15) noSlot.removeAt(0)
            } else if (asgSlot != null && noSlot.isNotEmpty()) {
                var late = 0
                for ((e, at) in noSlot) if (now - at < 300000L) late += repo.apply(asgSlot, e, "ocr", at)
                Diag.log("${noSlot.size} tela(s) lidas antes de reconhecer o slot → S$asgSlot (+$late campos)")
                noSlot.clear()
            }
            var changed = repo.apply(asg.slot, ex, "ocr", now)
            runCatching { Parsers.bossCoins(ocr)?.let { Coins.saveBalance(repo, it, now) } }
            runCatching { repo.smartReads(asg.slot, type, ocr, now) }
            // Fim da competição / diretoria: não é tela de adversário. Vai para o aprendizado do slot (reconhecido pela
            // navegação ou pelo nome do meu time na classificação) e "a época acabou" encerra a competição do slot.
            if (type == ScreenType.OTHER_OSM && SeasonScreens.isSeasonScreen(ocr.fullText)) {
                val full = Txt.key(ocr.fullText)
                val byName = known.filter { id -> id.names.any { it.length >= 5 && full.contains(it) } }.map { it.slot }.distinct()
                // telas sem nome de time ("A época acabou...") seguem o slot da tela de fim de época vista há pouco
                val ss = asgSlot ?: byName.singleOrNull() ?: seasonSlot?.takeIf { now - seasonSlotAt < 180000L }
                if (ss != null) {
                    seasonSlot = ss
                    seasonSlotAt = now
                    val sr = Parsers.seasonSummary(ocr, repo.fieldMap(ss)[K.TEAM]?.value)
                    changed += repo.recordSeasonScreen(ss, sr, now)
                    Diag.log("Fim de temporada/direção → S$ss" + (sr.rank?.let { " (${it}º lugar)" } ?: "") + (if (sr.over) " • competição encerrada" else ""))
                }
            }
            val rivalSquad = type == ScreenType.SQUAD && asgSlot != null && ex.ownerTeam != null &&
                Txt.key(repo.fieldMap(asgSlot)[K.RIVAL_TEAM]?.value ?: "").let { rk -> rk.length >= 3 && Txt.sim(Txt.key(ex.ownerTeam), rk) >= 0.8 }
            if ((type == ScreenType.PREGAME || type == ScreenType.REPORT || rivalSquad) && asgSlot != null) {
                try { autoTactic(asgSlot, now, type) } catch (e: Exception) { Diag.lastError = "Tática automática: " + (e.message ?: e.javaClass.simpleName) }
            }
            if (asgSlot != null && type != ScreenType.PREGAME && type != ScreenType.REPORT && !rivalSquad) {
                try { screenNote(asgSlot, type, ex) } catch (e: Exception) { Diag.lastError = "Aviso de leitura: " + (e.message ?: "") }
            }
            if (type == ScreenType.TACTIC && asgSlot != null) {
                try { checkTactic(asgSlot, ocr, now) } catch (e: Exception) { Diag.lastError = "Conferir tática: " + (e.message ?: "") }
            }
            val counts = when (type) {
                ScreenType.CALENDAR -> " [${ex.matches.size} cards" + (if (ex.ownerTeam == null) ", sem dono" else "") + "]"
                ScreenType.SQUAD -> " [${ex.players.size} jogadores" + (if (ex.ownerTeam == null) ", sem dono" else "") + "]"
                ScreenType.MARKET -> " [${ex.listings.size} jogadores à venda]"
                ScreenType.REPORT -> " [${ex.fields.size} campos do relatório]"
                ScreenType.RESULT -> " [placar ${ex.matchReport?.scoreHome ?: "?"}-${ex.matchReport?.scoreAway ?: "?"}, ${ex.matchReport?.stats?.size ?: 0} estatísticas, ${(ex.matchReport?.ratingsHome?.size ?: 0) + (ex.matchReport?.ratingsAway?.size ?: 0)} notas]"
                else -> ""
            }
            val hint = if (type == ScreenType.OTHER_OSM || type == ScreenType.TACTIC || type == ScreenType.REPORT || type == ScreenType.STADIUM) {
                " [" + ocr.lines.map { it.text.trim() }.filter { Txt.letters(it) >= 4 }.take(3).joinToString(" | ").take(70) + "]"
            } else ""
            Diag.log(
                typeLabel(type) + " → " + (asg.slot?.let { "S$it" } ?: if (type == ScreenType.HUB) "central" else "sem slot") +
                    (if (changed > 0) " (+$changed campos)" else "") + counts + hint
            )
            if (changed > 0) {
                Diag.extracted.addAndGet(changed)
                Diag.lastUpdateAt = now
                if (type == ScreenType.PREGAME || type == ScreenType.CALENDAR) {
                    try {
                        Notifier.reschedule(ctx)
                    } catch (e: Exception) {
                        Diag.lastError = "Alarmes: " + (e.message ?: e.javaClass.simpleName)
                    }
                }
            }
            val dataType = type == ScreenType.SQUAD || type == ScreenType.CALENDAR ||
                type == ScreenType.MARKET || type == ScreenType.PREGAME || type == ScreenType.REPORT || type == ScreenType.RESULT
            val unassigned = asg.slot == null && type != ScreenType.HUB
            if (unassigned) Diag.unassigned.incrementAndGet()

            // Tela desconhecida do OSM com cara de relatório do rival: guarda a imagem para a IA confirmar e ler.
            val rivalName = asg.slot?.let { repo.fieldMap(it)[K.RIVAL_TEAM]?.value }
            val mentionsRival = rivalName != null && Txt.key(rivalName).length >= 4 &&
                Txt.key(ocr.fullText).contains(Txt.key(rivalName))
            // A tela de introdução do analista (cartoon + botão) não tem dados: não vale guardar para a IA.
            val analystIntro = Txt.norm(ocr.fullText).contains("relatorio do analista")
            val reportHint = type == ScreenType.OTHER_OSM && asg.slot != null && !analystIntro &&
                reportCandidates < 12 && (Parsers.hasReportHint(ocr) || mentionsRival)
            // Diagnóstico: guarda poucas telas de tática e calendários "sem novidade" para eu ver o que o app viu.
            val diagTactic = type == ScreenType.TACTIC && asg.slot != null && tacticSaved < 6
            val diagCalendar = type == ScreenType.CALENDAR && changed == 0 && ex.matches.isNotEmpty() && calendarSaved < 4
            // Fim do jogo (análise, estatísticas, notas), pré-jogo (bônus) e plantel: imagens para o diagnóstico exportado
            val diagResult = type == ScreenType.RESULT && resultSaved < 16
            val diagPregame = type == ScreenType.PREGAME && pregameSaved < 6
            val diagSquad = type == ScreenType.SQUAD && squadSaved < 10
            // fim de época / direção: guarda para dar para reprocessar e conferir depois
            val diagSeason = type == ScreenType.OTHER_OSM && seasonSaved < 12 && SeasonScreens.isSeasonScreen(ocr.fullText)
            // pré-jogo em que o OCR não achou o rival ou as forças/bônus: a IA lê a imagem ao encerrar a captura
            // (uma por slot a cada 2 min: o pré-jogo é relido a cada 3 s e não pode gastar a cota da IA)
            val pregameGap = type == ScreenType.PREGAME && (
                !ex.fields.containsKey(K.RIVAL_TEAM) ||
                    (!ex.fields.containsKey(K.MY_STRENGTH) && !ex.fields.containsKey(K.MY_BONUS))
                ) && now - (pregameAiAt[asg.slot ?: 0] ?: 0L) > 120000L
            if (pregameGap) pregameAiAt[asg.slot ?: 0] = now
            // Só vale gastar a IA com tela que tem cara de relatório E quando ainda falta a formação/estilo do rival
            // (antes qualquer tela citando o rival ia para a fila: escritório, menus…)
            val rivalKnown = asg.slot?.let { sl ->
                val rf = repo.fieldMap(sl)
                FieldMerge.known(rf[K.RIVAL_FORMATION]?.value) && FieldMerge.known(rf[K.RIVAL_PLAN]?.value)
            } ?: true
            val reportForAi = reportHint && Parsers.hasReportHint(ocr) && !rivalKnown
            var path: String? = null
            if (((unassigned && dataType) || ex.needsAi || reportHint || pregameGap || diagTactic || diagCalendar || diagResult || diagPregame || diagSquad || diagSeason) &&
                imagesThisSession < 90
            ) {
                if (diagTactic) tacticSaved++
                if (diagCalendar) calendarSaved++
                if (diagResult) resultSaved++
                if (diagPregame) pregameSaved++
                if (diagSquad) squadSaved++
                if (diagSeason) seasonSaved++
                path = ImageIo.saveJpeg(ctx, bmp, sessionId, now)
                imagesThisSession++
                if (reportHint) reportCandidates++
            }
            dao.insertScreen(
                ScreenEntity(
                    sessionId = sessionId, at = now, type = type.name, slotId = asg.slot ?: 0,
                    slotConf = asg.confidence, hash = FrameHash.toText(h), imagePath = path,
                    ocrChars = ocr.fullText.length, extracted = changed,
                    aiState = if ((ex.needsAi || reportForAi || pregameGap) && path != null) "pending" else "none", note = asg.reason
                )
            )
        }
    }

    /** Reprocessa quadros sem slot (idempotente): tenta identificar pelo dono do cabeçalho/pré-jogo. */
    suspend fun reprocessUnassigned(limit: Int = 40, onStep: () -> Unit = {}): Int {
        var fixed = 0
        val now = System.currentTimeMillis()
        for (s in dao.unassignedWithImage(limit)) {
            val path = s.imagePath ?: continue
            val bmp = BitmapFactory.decodeFile(path) ?: continue
            try {
                val img = ImageIo.toImg(bmp)
                val topBar = PixelProbe.isOsmTopBar(img)
                val ocr = OcrEngine.read(bmp)
                val type = ScreenClassifier.classify(ocr, topBar)
                val ex = parse(ocr, type, img, s.at)
                val known = repo.knownIdentities()
                val m = when (type) {
                    ScreenType.PREGAME -> SlotMatcher.match(ex.teamCandidates, ex.roundRead, known)
                    ScreenType.SQUAD, ScreenType.CALENDAR ->
                        ex.ownerTeam?.let { SlotMatcher.match(listOf(it), null, known) }?.takeIf { it.conf >= 0.85 }
                            ?: if (type == ScreenType.SQUAD) ex.ownerTeam?.let { SlotMatcher.matchRival(listOf(it), known) } else null
                    ScreenType.RESULT ->
                        SlotMatcher.match(ex.ownerTeam?.let { listOf(it) } ?: ex.teamCandidates, null, known)?.takeIf { it.conf >= 0.85 }
                    else -> null
                }
                if (m != null) {
                    val changed = repo.apply(m.slot, ex, "reprocess", now)
                    dao.updateScreenSlot(s.id, m.slot, m.conf, changed, "reprocessado")
                    fixed++
                }
            } finally {
                bmp.recycle()
                onStep()
            }
        }
        return fixed
    }

    data class ReplayResult(val screens: Int, val read: Int, val fields: Int, val bySlot: Map<Int, Int>)

    /**
     * Reprocessa a captura inteira (todas as telas guardadas da sessão, em ordem), com os leitores atuais: o que
     * uma versão nova do app aprendeu a ler entra nos slots sem precisar navegar de novo. Leitura antiga nunca
     * sobrescreve dado mais novo (o Repo compara a hora da tela com a do campo).
     */
    suspend fun replaySession(sessionId: String, onStep: (Int, Int) -> Unit = { _, _ -> }): ReplayResult {
        val list = dao.screensOfSession(sessionId).filter { it.imagePath != null && File(it.imagePath).exists() }
        var read = 0
        var fields = 0
        val bySlot = HashMap<Int, Int>()
        val owners = HashMap<ScreenType, Triple<Int, String, Long>>()
        var lastSeasonSlot: Int? = null
        var lastSeasonAt = 0L
        for ((i, s) in list.withIndex()) {
            onStep(i, list.size)
            val bmp = BitmapFactory.decodeFile(s.imagePath ?: continue) ?: continue
            try {
                val img = ImageIo.toImg(bmp)
                val ocr = OcrEngine.read(bmp)
                val type = ScreenClassifier.classify(ocr, PixelProbe.isOsmTopBar(img))
                var ex = parse(ocr, type, img, s.at)
                read++
                val known = repo.knownIdentities()
                // o slot gravado na hora da captura veio da navegação: vale; sem ele, tenta pelos nomes da tela
                val slot = s.slotId.takeIf { it in 1..4 } ?: when (type) {
                    ScreenType.PREGAME -> SlotMatcher.match(ex.teamCandidates, ex.roundRead, known)?.slot
                    ScreenType.SQUAD, ScreenType.CALENDAR, ScreenType.RESULT ->
                        SlotMatcher.match(ex.ownerTeam?.let { listOf(it) } ?: ex.teamCandidates, null, known)?.takeIf { it.conf >= 0.85 }?.slot
                    else -> null
                }
                // lista rolada sem cabeçalho: o dono é o da última tela do mesmo tipo e slot (como na captura ao vivo)
                if (slot != null && ex.ownerTeam == null && (type == ScreenType.SQUAD || type == ScreenType.CALENDAR)) {
                    owners[type]?.takeIf { it.first == slot && s.at - it.third < 600000L }?.let { ex = ex.copy(ownerTeam = it.second) }
                }
                if (slot != null && ex.ownerTeam != null && (type == ScreenType.SQUAD || type == ScreenType.CALENDAR)) {
                    owners[type] = Triple(slot, ex.ownerTeam!!, s.at)
                }
                var changed = if (type == ScreenType.HUB || slot != null) repo.apply(slot, ex, "reprocess", s.at) else 0
                if (type == ScreenType.OTHER_OSM && SeasonScreens.isSeasonScreen(ocr.fullText)) {
                    val full = Txt.key(ocr.fullText)
                    val ss = slot ?: known.filter { id -> id.names.any { it.length >= 5 && full.contains(it) } }.map { it.slot }.distinct().singleOrNull()
                        ?: lastSeasonSlot?.takeIf { s.at - lastSeasonAt < 180000L }
                    if (ss != null) {
                        lastSeasonSlot = ss
                        lastSeasonAt = s.at
                        changed += repo.recordSeasonScreen(ss, Parsers.seasonSummary(ocr, repo.fieldMap(ss)[K.TEAM]?.value), s.at)
                    }
                }
                if (changed > 0 && slot != null) bySlot[slot] = (bySlot[slot] ?: 0) + changed
                fields += changed
            } catch (e: Exception) {
                Diag.lastError = "Reprocessar tela ${s.id}: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                bmp.recycle()
            }
        }
        onStep(list.size, list.size)
        for (slot in 1..4) runCatching { repo.syncRound(slot) }
        return ReplayResult(list.size, read, fields, bySlot)
    }
}

/** Compara o valor guardado com o lido agora de cada campo da assinatura da tática (tolerante ao ruído do OCR). */
object SigCompare {
    fun differs(k: String, a: String, b: String): Boolean = when {
        a == b -> false
        // campo que sumiu ou ainda não foi lido não é "mudança" (o OCR do pré-jogo pisca entre leituras)
        a.isBlank() || b.isBlank() -> false
        (k == K.RIVAL_STRENGTH || k == K.MY_STRENGTH) && a.toIntOrNull() != null && b.toIntOrNull() != null -> kotlin.math.abs(a.toInt() - b.toInt()) > 1
        // nome do time/apelido: o OCR troca letras ("VfB Stuttgart II" × "Vfb Stuttgart ll"); só é outro adversário se for bem diferente
        k == K.RIVAL_TEAM || k == K.RIVAL_NICK -> Txt.sim(Txt.key(a), Txt.key(b)) < 0.8
        k == K.RIVAL_FORMATION -> (Formations.canonical(a) ?: a) != (Formations.canonical(b) ?: b)
        k == K.RIVAL_PLAN -> (Osm.style(a) ?: Txt.key(a)) != (Osm.style(b) ?: Txt.key(b))
        else -> Txt.key(a) != Txt.key(b)
    }
}
