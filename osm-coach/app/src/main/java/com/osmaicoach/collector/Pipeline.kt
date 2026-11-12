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
    /** Campo com os cartões do 11 ("7 KELMAN", "10 MOFFI"…): junta os números de camisa vistos (os cartões ficam atrás da bolha em alguns quadros). 11 distintos = onze completo. */
    private suspend fun scanLineup(slot: Int, ocr: OcrResult, now: Long) {
        val rx = Regex("^(\\d{1,2})\\s+[\\p{L}][\\p{L}.' -]{2,}$")
        val nums = ocr.lines.filter { it.yc in 0.15f..0.95f && it.xc in 0.08f..0.92f }.mapNotNull { rx.find(it.text.trim())?.groupValues?.get(1)?.toIntOrNull() }.filter { it in 1..40 }
        if (nums.size < 3) return
        val old = dao.plan(slot, "lineup")?.json?.let { runCatching { JSONObject(it) }.getOrNull() }?.takeIf { now - it.optLong("at") < 10 * 60_000L }
        val set = HashSet<Int>()
        old?.optJSONArray("n")?.let { a -> for (i in 0 until a.length()) set.add(a.optInt(i)) }
        set.addAll(nums)
        dao.putPlan(PlanEntity(slot, "lineup", JSONObject().put("n", org.json.JSONArray(set.toList())).put("at", now).put("full", set.size >= 11).toString(), now))
    }

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
            dao.putPlan(PlanEntity(slot, "tacticdone", JSONObject().put("at", p.at).put("sig", Director.mountSig(j)).toString(), now))
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
        val msg = when {
            diffs.isNotEmpty() -> "⚠ S$slot: o rival MUDOU a tática: " + diffs.take(3).joinToString("; ") + ". Gere a tática de novo."
            old != null -> "✅ S$slot: o rival MANTEVE a tática ($head)."
            else -> "👀 S$slot: tática do rival lida ($head). Passe de novo perto do jogo para ver se ele muda."
        }
        // reanálise dos 20 min finais: guarda o veredito (a Véspera mostra feito/pendente) e avisa que é a conferência final
        val mAt = f[K.MATCH_AT]?.value?.toLongOrNull()
        if (mAt != null && mAt - now in 0..Reanalysis.WINDOW_MS) {
            val verdict = if (diffs.isNotEmpty()) "mudou" else if (old != null) "manteve" else "lida"
            repo.dao.putPlan(PlanEntity(slot, Reanalysis.PLAN, JSONObject().put("matchAt", mAt).put("at", now).put("verdict", verdict).put("detail", diffs.take(3).joinToString("; ")).toString(), now))
            return "🔁 Reanálise (faltam ${(mAt - now) / 60000} min) — " + msg.removePrefix("⚠ ").removePrefix("✅ ").removePrefix("👀 ")
        }
        return msg
    }

    /** Mesmo valor apesar do ruído do OCR: formação/estilo canônicos, números ±4 (pressão/ritmo), texto por similaridade. */
    private fun sameRivalValue(k: String, a: String, b: String): Boolean {
        if (a.equals(b, ignoreCase = true)) return true
        if (k == K.RIVAL_FORMATION || k == K.RIVAL_PLAN || k == K.RIVAL_MARKING || k == K.RIVAL_TACKLE) return !SigCompare.differs(k, a, b)
        val na = a.filter { it.isDigit() }.toIntOrNull(); val nb = b.filter { it.isDigit() }.toIntOrNull()
        if (na != null && nb != null && a.length <= 5 && b.length <= 5) return kotlin.math.abs(na - nb) <= 4
        return Txt.sim(Txt.key(a), Txt.key(b)) >= 0.82
    }

    /**
     * Tela em leitura (várias rolagens). A leitura só é "completa" quando o CONTEÚDO prova: a lista chegou ao fim (sobra espaço
     * em branco embaixo da última linha) e as seções esperadas foram vistas. Nunca por tempo: você pode rolar rápido ou devagar.
     */
    private class Pending(val slot: Int, val type: ScreenType, val round: Int?, val seen: MutableSet<String> = HashSet()) {
        var offFrames = 0
        var endSeen = false
        var done = false
        val cats = HashSet<String>()
        var ratings = false
        var maxRound = 0
        var lastSet: Set<String> = emptySet()
        var moved = false
        var still = 0
        /** Último quadro mostrava goleiros (última seção do Plantel). */
        var lastHadGk = false
        /** Dono da lista (plantel): quadros rolados sem o título continuam a mesma leitura. */
        var owner: String? = null
    }
    private var pending: Pending? = null
    private var reportPassAt = 0L
    private var reportPrev: ScreenType? = null

    /** Chegou ao fim da lista: a última linha de conteúdo termina bem acima do rodapé da tela (há espaço em branco abaixo). */
    private fun bottomReached(o: OcrResult): Boolean {
        val ys = o.lines.filter { it.yc > 0.12f }.map { it.yc }
        return ys.isNotEmpty() && ys.max() < 0.86f
    }

    private fun lowestRow(o: OcrResult): Float = o.lines.filter { it.yc > 0.12f }.maxOfOrNull { it.yc } ?: 1f

    private suspend fun screenNote(slot: Int, type: ScreenType, ex: Extraction, ocr: OcrResult) {
        val cur = pending?.takeIf { it.slot == slot && it.type == type }
        val gap = bottomReached(ocr)
        val pd: Pending = when (type) {
            ScreenType.RESULT -> {
                val r = ex.matchReport ?: return
                (cur?.takeIf { it.round == r.round } ?: Pending(slot, type, r.round)).also { pending = it; if (r.ratingsHome.isNotEmpty() || r.ratingsAway.isNotEmpty()) { it.ratings = true; if (gap) it.endSeen = true } }
            }
            ScreenType.CALENDAR -> {
                // o fim do calendário é sempre "Última rodada" + "Resumo da época": o quadro anterior é a última rodada
                val endText = Txt.norm(ocr.fullText).contains("resumo da epoca")
                if (ex.matches.size < 3 && !(cur != null && endText)) return
                (cur ?: Pending(slot, type, null)).also { pd -> pending = pd; ex.matches.forEach { m -> pd.seen.add("${m.round}"); m.round?.let { r -> if (r > pd.maxRound) pd.maxRound = r } }; if (endText || (gap && pd.maxRound >= (repo.fieldMap(slot)[K.ROUND_TOTAL]?.value?.toIntOrNull() ?: Int.MAX_VALUE))) pd.endSeen = true }
            }
            ScreenType.SQUAD -> {
                // rolada a lista, o título do time sai da tela: quadro sem dono continua a leitura em andamento (antes era descartado e o plantel nunca "chegava ao fim")
                val own = ex.ownerTeam
                val sameOwner = cur != null && (own == null || cur.owner == null || Txt.sim(Txt.key(own), Txt.key(cur.owner!!)) >= 0.8)
                if (ex.players.size < 3 || (own == null && !sameOwner)) return
                (if (sameOwner) cur!! else Pending(slot, type, null)).also { pd -> pending = pd; if (own != null) pd.owner = own; ex.players.forEach { p -> pd.seen.add(Txt.key(p.name)); p.cat?.let { c -> pd.cats.add(c) } }; if (gap && ex.players.any { it.cat == "GOL" }) pd.endSeen = true }
            }
            ScreenType.MARKET -> {
                if (ex.listings.size < 3) return
                (cur ?: Pending(slot, type, null)).also { pd -> pending = pd; ex.listings.forEach { l -> pd.seen.add(Txt.key(l.name)) }; if (gap) pd.endSeen = true }
            }
            else -> return
        }
        pd.offFrames = 0
        // a lista parou de andar (quadro igual ao anterior depois de já ter rolado) com folga embaixo = chegou ao fim
        // quadro "igual" = as MESMAS linhas nas MESMAS alturas (texto + altura em faixas de 0,02): rolar uma linha desloca todas as alturas,
        // então um quadro que só ganhou 1 jogador novo embaixo NÃO é igual (antes valia ≥85% dos nomes e o plantel "terminava" no 1º goleiro)
        val names = ocr.lines.filter { it.yc > 0.12f }.map { Txt.key(it.text).take(10) + "@" + Math.round(it.yc * 50f) }.toSet()
        val same = pd.lastSet.isNotEmpty() && names.intersect(pd.lastSet).size.toFloat() / names.union(pd.lastSet).size.coerceAtLeast(1) >= 0.6f   // o OCR oscila entre quadros parados
        if (pd.lastSet.isNotEmpty() && !same) pd.moved = true
        pd.still = if (same) pd.still + 1 else 0
        // fim da lista = a lista PAROU (quadro igual ao anterior, depois de já ter rolado) mostrando a última seção (goleiros).
        // Plantel e Mercado vão até os goleiros e a última linha cola no rodapé: não dá para exigir folga embaixo.
        val hasGk = ex.players.any { it.cat == "GOL" } || ex.listings.any { it.cat == "GOL" }
        val stalled = pd.moved && same && pd.still >= 1   // 2 quadros iguais seguidos (mesmas linhas, mesmas alturas): a lista parou mesmo
        pd.lastSet = names
        pd.lastHadGk = hasGk
        // Mercado: lista parada (depois de rolar) já é o fim, qualquer que seja a ordem; Plantel: parada mostrando os goleiros (última seção)
        if (stalled && (type == ScreenType.MARKET || (type == ScreenType.SQUAD && hasGk) || (type == ScreenType.CALENDAR && lowestRow(ocr) < 0.93f))) pd.endSeen = true
        // Plantel: já li ao menos o elenco guardado (ou o mínimo de 18) com as 4 seções e o quadro atual mostra goleiros no fim = completo
        if (type == ScreenType.SQUAD && hasGk && pd.moved && pd.cats.containsAll(listOf("GOL", "DEF", "MEI", "ATA"))) {
            val known = runCatching { repo.dao.playersOf(slot).size }.getOrDefault(0)
            if (pd.seen.size >= maxOf(18, known) && ocr.lines.any { it.yc > 0.12f && it.yc >= 0.80f }) pd.endSeen = true
        }
        if (hasGk) pd.cats.add("GOL")
        ex.listings.forEach { l -> l.cat?.let { c -> pd.cats.add(c) } }
        if (!pd.done && complete(pd)) {
            pd.done = true
            summary(pd, true)?.let { Diag.log("Resumo da tela → $it"); note(slot, it, System.currentTimeMillis()) }
        }
    }

    private suspend fun complete(pd: Pending): Boolean {
        val f = repo.fieldMap(pd.slot)
        return when (pd.type) {
            ScreenType.SQUAD -> pd.endSeen && pd.cats.containsAll(listOf("GOL", "DEF", "MEI", "ATA"))
            ScreenType.MARKET -> pd.endSeen
            ScreenType.CALENDAR -> {
                // o total do OSM ("43") inclui jogos de copa ainda sem data/rival que o calendário nem lista: vale chegar à última rodada OU ao fim da lista
                // só o fim visto (texto "Resumo da época" ou lista parada embaixo): ter lido a rodada 43 antes não prova que a tela chegou ao fim (print 12/11)
                pd.endSeen
            }
            ScreenType.RESULT -> pd.endSeen && pd.ratings
            else -> false
        }
    }

    /** Resumo da tela. `complete`: a leitura chegou ao fim; senão diz que ficou incompleta (sem supor nada). */
    private suspend fun summary(pd: Pending, complete: Boolean): String? {
        val slot = pd.slot
        val f = repo.fieldMap(slot)
        val tail = if (complete) "" else " Leitura INCOMPLETA: role até o fim para concluir."
        return when (pd.type) {
            ScreenType.RESULT -> {
                val rd = pd.round ?: ResultCtx.round ?: f[K.ROUND_DONE]?.value?.toIntOrNull()
                val j = rd?.let { repo.dao.plan(slot, "mr_R$it")?.json?.let { s -> runCatching { JSONObject(s) }.getOrNull() } }
                if (rd == null || j == null) null
                else if (!j.has("sh")) "S$slot: Resultado da J$rd: não li o placar. Abra de novo e role a tela inteira (placar, estatísticas, notas)."
                else {
                    val st = j.optJSONObject("stats")?.length() ?: 0
                    val rt = (j.optJSONArray("rh")?.length() ?: 0) + (j.optJSONArray("ra")?.length() ?: 0)
                    val ev = j.optJSONArray("events")?.length() ?: 0
                    val lacks = buildList { if (st < 7) add("estatísticas (até Zonas de ação)"); if (rt == 0) add("notas dos jogadores") }
                    (if (complete) "✅" else "⚠") + " S$slot: Resultado da J$rd: ${j.optInt("sh")}-${j.optInt("sa")} • $ev eventos • $st estatísticas • $rt notas." +
                        (if (lacks.isEmpty()) (if (complete) " Completo: o aprendizado foi atualizado." else tail) else " Falta: " + lacks.joinToString(", ") + ".")
                }
            }
            ScreenType.CALENDAR -> {
                // as rodadas ficam guardadas: o total vem do banco (a passada só vê as que a tela mostrou), nunca "24" se há 43
                val got = repo.dao.matchesOf(slot).mapNotNull { it.round }.toSet().size
                val total = maxOf(f[K.ROUND_TOTAL]?.value?.toIntOrNull() ?: 0, pd.maxRound, got)
                if (complete) "✅ S$slot: Calendário completo: $got de $total rodadas (até a última e o Resumo da época)."
                else "⚠ S$slot: Calendário: $got de $total rodadas lidas (nesta passada vi ${pd.seen.size}).$tail"
            }
            ScreenType.SQUAD -> (if (complete) "✅ " else "⚠ ") + "S$slot: Plantel: li ${pd.seen.size} jogadores" + (if (complete) " (do goleiro ao ataque, até o fim da lista)." else ".$tail")
            ScreenType.MARKET -> (if (complete) "✅ " else "⚠ ") + "S$slot: Mercado: li ${pd.seen.size} jogadores à venda" + (if (complete) " (até o fim da lista)." else ".$tail")
            ScreenType.TACTIC -> tacticSummary(slot, System.currentTimeMillis())
            else -> null
        }
    }

    /** Você deixou a tela (ou encerrou): se a leitura não chegou ao fim, avisa que ficou incompleta; se já foi concluída, não repete. */
    suspend fun flushPending() {
        val pd = pending ?: return
        pending = null
        if (pd.done) return
        // saiu da tela logo depois de chegar aos goleiros (última seção) com as 4 seções e ≥18 jogadores vistos: a lista foi até o fim
        val atEnd = pd.type == ScreenType.SQUAD && pd.lastHadGk && pd.moved && pd.seen.size >= 18 && pd.cats.containsAll(listOf("GOL", "DEF", "MEI", "ATA"))
        val txt = summary(pd, atEnd)
        if (txt != null) { Diag.log("Resumo da tela → $txt"); note(pd.slot, txt, System.currentTimeMillis()) }
    }

    private fun why(slot: Int, text: String) { Diag.log("Tática automática S$slot: $text") }

    /** Pré-jogo/Análise lidos com o jogo longe (fora das 8 h): diz o que foi lido e que a tática só sai nas 8 h antes (cada tela fala; o mesmo texto não repete em 1 min). */
    private fun farNote(slot: Int, f: Map<String, StoredField>, type: ScreenType?, delta: String?, matchAt: Long, now: Long) {
        fun v(k: String) = f[k]?.value?.takeIf { FieldMerge.known(it) }
        val read = when (type) {
            ScreenType.PREGAME -> {
                val a = v(K.MY_STRENGTH); val b = v(K.RIVAL_STRENGTH)
                if (a != null && b != null) "✅ S$slot: Pré-jogo lido — forças $a × $b." else "S$slot: Pré-jogo lido, mas não consegui ler as duas forças."
            }
            ScreenType.REPORT -> delta ?: run {
                val fm = v(K.RIVAL_FORMATION); val pl = v(K.RIVAL_PLAN)
                when {
                    fm != null && pl != null -> "✅ S$slot: Análise do rival lida (formação $fm e estilo)."
                    fm != null -> "S$slot: Análise do rival, tela 1 lida (formação $fm). Falta a 2ª tela (estilo e marcação)."
                    pl != null -> "S$slot: Análise do rival, tela 2 lida. Falta a 1ª tela (formação)."
                    else -> "S$slot: Análise do rival: não consegui ler formação nem estilo."
                }
            }
            ScreenType.SQUAD -> "S$slot: Plantel do rival lido."
            else -> return
        }
        val h = ((matchAt - now) / 3_600_000L).coerceAtLeast(0)
        note(slot, "$read A tática só é gerada nas 8 h antes do jogo (faltam ${h} h).", now)
    }

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
        val justRead = type == ScreenType.REPORT && listOf(K.RIVAL_FORMATION, K.RIVAL_PLAN).all { k -> f[k]?.let { FieldMerge.known(it.value) && it.updatedAt >= reportPassAt } == true }
        val gaps = Director.tacticGaps(f, now)
        val check = Director.checklist(f, now)
        val delta = if (justRead) runCatching { rivalDelta(slot, f, now) }.getOrNull() else null
        delta?.let { why(slot, it.substringAfter(": ")) }
        if (gaps.isNotEmpty()) {
            why(slot, "faltam leituras: " + gaps.joinToString("; ") { it.first })
            // longe do jogo (fora das 8 h) não há o que fazer agora: sem mensagem ao entrar no slot
            if (matchAt - now > Director.TACTIC_WINDOW_MS) { farNote(slot, f, type, delta, matchAt, now); return }
            val missing = Director.groups(f, now).filter { !it.ok }
            val read = when (type) { ScreenType.PREGAME -> "Pré-jogo"; ScreenType.REPORT -> "Análise do rival"; ScreenType.SQUAD -> "Plantel do rival"; else -> null }
            note(slot, (delta ?: ("S$slot:" + (read?.let { " $it lido." } ?: ""))) + " Falta ler: " +
                missing.joinToString(" • ") { it.name.substringBefore(" (") + " (" + it.missing.joinToString(", ").take(60) + ")" } + ". Veja o caminho em Hoje → Para fazer.", now, 5 * 60_000L)
            return
        }
        if (matchAt - now > Director.TACTIC_WINDOW_MS) {
            farNote(slot, f, type, delta, matchAt, now)
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
        // só a força (dos dois times) mudou e a tática ainda vale: a força oscila e não muda a decisão; não regera nem avisa (só atualiza a assinatura)
        val onlyStrength = valid && oldParts != null && changed.isNotEmpty() && changed.all { it == K.RIVAL_STRENGTH || it == K.MY_STRENGTH }
        if (onlyStrength) {
            repo.dao.putPlan(PlanEntity(slot, "autogen", JSONObject().put("sig", sig).put("at", old?.optLong("at") ?: now).apply { old?.optLong("chk", 0L)?.takeIf { it > 0L }?.let { put("chk", it) } }.toString(), now))
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
        val before = repo.dao.plan(slot, "tactic")?.json?.let { runCatching { JSONObject(it) }.getOrNull() }
        startGeneration(ctx, "tactic", slot)
        AppScope.scope.launch {
            var waited = 0
            while (GenState.running("tactic", slot) && waited < 150) { delay(1000L); waited++ }
            val job = GenState.get("tactic", slot)
            val j = repo.dao.plan(slot, "tactic")?.json?.let { runCatching { JSONObject(it) }.getOrNull() }
            val unchanged = valid && job?.ok == true && j != null && before != null && j.optString("formation") == before.optString("formation") &&
                j.optString("playStyle") == before.optString("playStyle") && j.optString("tackle") == before.optString("tackle")
            val msg = if (unchanged) "✅ S$slot: refeita e a tática continua a mesma: ${j!!.optString("formation")} • ${j.optString("playStyle")}."
            else if (job?.ok == true && j != null) {
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
        for (card in cards) {
            if (card.state != null && card.state != "preepoca") continue
            if (card.team.isBlank()) continue
            val cx = (if (card.slot % 2 == 1) 0.5867f else 0.7953f) + card.dx
            val cy = (if (card.slot <= 2) 0.263f else 0.711f) + card.dy
            // escudo do clube do slot no arquivo global por nome (só grava o que ainda não existe e só se for mesmo um escudo)
            if (CrestCut.find(ctx.filesDir, card.team) != null) continue
            try { if (CrestCut.save(ctx.filesDir, bmp, card.team, cx, cy, 0.10f, 0.20f, expW = 0.065f, expH = 0.155f)) UiBus.bump() }
            catch (e: Exception) { Diag.lastError = "Escudo: " + (e.message ?: "erro") }
        }
    }

    /**
     * O OCR da tela inteira às vezes perde ou corta o número da força de uma linha ("74" vira "7" ou some) e o jogador ficava com "?".
     * Para cada jogador sem força (e com setor conhecido) lê de novo SÓ a faixa das colunas Ata/Def/Med daquela linha, ampliada 3×:
     * o OCR de um recorte pequeno acerta onde o da tela falhou. A força é a coluna do setor (ATA→Ata, MEI→Med, DEF/GOL→Def).
     */
    private suspend fun repairStrength(bmp: Bitmap, ex: Extraction): Extraction {
        val need = ex.players.filter { it.strength == null && it.cat != null && it.natY != null }.take(8)
        if (need.isEmpty()) return ex
        val fixed = HashMap<String, Int>()
        for (p in need) {
            val y = p.natY!!
            val x0 = (0.640f * bmp.width).toInt(); val x1 = (0.775f * bmp.width).toInt()
            val y0 = ((y - 0.030f) * bmp.height).toInt().coerceAtLeast(0); val y1 = ((y + 0.030f) * bmp.height).toInt().coerceAtMost(bmp.height)
            if (x1 - x0 < 20 || y1 - y0 < 12) continue
            val crop = Bitmap.createBitmap(bmp, x0, y0, x1 - x0, y1 - y0)
            val big = Bitmap.createScaledBitmap(crop, crop.width * 3, crop.height * 3, true)
            val r = OcrEngine.read(big)
            crop.recycle(); big.recycle()
            val col = when (p.cat) { "ATA" -> 0.663f; "MEI" -> 0.737f; else -> 0.70f }
            val v = r.tokens.mapNotNull { t ->
                val n = t.text.trim().toIntOrNull() ?: return@mapNotNull null
                val fx = 0.640f + t.xc * 0.135f
                if (n in 30..120 && Math.abs(fx - col) <= 0.02f) Pair(Math.abs(fx - col), n) else null
            }.minByOrNull { it.first }?.second
            if (v != null) fixed[Txt.key(p.name)] = v
        }
        if (fixed.isEmpty()) return ex
        Diag.log("Força relida por recorte: " + fixed.size + " jogador(es)")
        return ex.copy(players = ex.players.map { p -> fixed[Txt.key(p.name)]?.let { p.copy(strength = it) } ?: p })
    }

    /**
     * Linha do Plantel cujo NOME o OCR leu mas cuja idade/força/valor se perdeu (a linha inteira some do elenco): relê só a faixa da linha,
     * ampliada 2×, e completa o jogador (12/11: Ba e N'Guessan sumiam do S2). Até 4 linhas por quadro; só entra se a releitura achou força E valor.
     */
    private suspend fun repairNames(bmp: Bitmap, ex: Extraction): Extraction {
        if (ex.nameless.isEmpty() || ex.players.size < 3) return ex
        val have = ex.players.map { Txt.key(it.name) }.toSet()
        val add = ArrayList<PlayerRead>()
        for (p in ex.nameless.take(3)) {
            val y = p.natY ?: continue
            val x0 = (0.04f * bmp.width).toInt(); val x1 = (0.34f * bmp.width).toInt()
            val y0 = ((y - 0.035f) * bmp.height).toInt().coerceAtLeast(0); val y1 = ((y + 0.035f) * bmp.height).toInt().coerceAtMost(bmp.height)
            if (x1 - x0 < 20 || y1 - y0 < 12) continue
            val crop = Bitmap.createBitmap(bmp, x0, y0, x1 - x0, y1 - y0)
            val big = Bitmap.createScaledBitmap(crop, crop.width * 4, crop.height * 4, true)
            val r = OcrEngine.read(big)
            crop.recycle(); big.recycle()
            val nm = PlayerName.clean(r.tokens.filter { Txt.letters(it.text) >= 1 && it.text.trim().toIntOrNull() == null }.sortedBy { it.l }.joinToString(" ") { it.text.trim() }, 2)
            if (nm != null && Txt.key(nm) !in have) add.add(p.copy(name = nm))
        }
        if (add.isEmpty()) return ex
        Diag.log("Plantel: ${add.size} nome(s) refeito(s) por recorte: " + add.joinToString { it.name })
        return ex.copy(players = ex.players + add)
    }

    private suspend fun repairRows(bmp: Bitmap, ocr: OcrResult, ex: Extraction): Extraction {
        if (ex.players.size < 3) return ex
        val rowYs = ex.players.mapNotNull { it.natY }
        val have = ex.players.map { Txt.key(it.name) }.toSet()
        val sections = listOf("avancad", "medio", "defes", "guarda", "goleir", "ataq")
        val cands = ocr.tokens.filter { t ->
            t.xc in 0.04f..0.34f && t.yc in 0.14f..0.96f && Txt.letters(t.text) >= 2 && t.text.firstOrNull()?.isUpperCase() == true &&
                sections.none { Txt.norm(t.text).startsWith(it) } &&
                rowYs.none { Math.abs(it - t.yc) <= 0.035f } && PlayerName.clean(t.text, 2) != null
        }.distinctBy { Math.round(it.yc * 40f) }.take(4)
        if (cands.isEmpty()) return ex
        val add = ArrayList<PlayerRead>()
        for (c in cands) {
            val y0 = ((c.yc - 0.045f) * bmp.height).toInt().coerceAtLeast(0)
            val y1 = ((c.yc + 0.045f) * bmp.height).toInt().coerceAtMost(bmp.height)
            if (y1 - y0 < 16) continue
            val crop = Bitmap.createBitmap(bmp, 0, y0, bmp.width, y1 - y0)
            val big = Bitmap.createScaledBitmap(crop, crop.width * 2, crop.height * 2, true)
            val r = OcrEngine.read(big)
            crop.recycle(); big.recycle()
            val fh = (y1 - y0).toFloat() / bmp.height
            val y0n = y0.toFloat() / bmp.height
            val toks = r.tokens.map { OcrToken(it.text, it.l, y0n + it.t * fh, it.r, y0n + it.b * fh) }
            val re = Parsers.squad(OcrResult(emptyList(), toks), null)
            re.players.filter { Txt.key(it.name) !in have && it.strength != null && it.valueText != null && Math.abs((it.natY ?: 0f) - c.yc) <= 0.04f }
                .forEach { add.add(it) }
        }
        if (add.isEmpty()) return ex
        Diag.log("Plantel: ${add.size} linha(s) refeita(s) por recorte: " + add.joinToString { it.name })
        return ex.copy(players = ex.players + add.distinctBy { Txt.key(it.name) })
    }

    /** Miniatura NÍTIDA (WebP, ~44×30) de uma região da captura inteira, em base64; null se quase tudo escuro. */
    private fun thumb(bmp: Bitmap, cx: Float, cy: Float, w: Float, h: Float, outW: Int, outH: Int, refine: Boolean = false): Pair<Float, String>? = try {
        val x = ((cx - w / 2) * bmp.width).toInt().coerceIn(0, bmp.width - 2)
        val y = ((cy - h / 2) * bmp.height).toInt().coerceIn(0, bmp.height - 2)
        val ww = (w * bmp.width).toInt().coerceAtMost(bmp.width - x)
        val hh = (h * bmp.height).toInt().coerceAtMost(bmp.height - y)
        if (ww < 8 || hh < 8) null else {
            var crop = Bitmap.createBitmap(bmp, x, y, ww, hh)
            if (refine) {
                // o recorte vem maior que a bandeira (~35×24 px em 1280×564); acha a caixa real e recorta nela (sem sobra à direita/esquerda)
                val cpx = IntArray(ww * hh); crop.getPixels(cpx, 0, ww, 0, 0, ww, hh)
                val box = Flags.flagBox(cpx, ww, hh, (0.0273f * bmp.width).toInt(), (0.0426f * bmp.height).toInt())
                if (box != null) {
                    val bx = (box[0] - 1).coerceAtLeast(0); val by = (box[1] - 1).coerceAtLeast(0)
                    val bw2 = (box[2] + 2).coerceAtMost(ww) - bx; val bh2 = (box[3] + 2).coerceAtMost(hh) - by
                    if (bw2 >= 8 && bh2 >= 8) { val c2 = Bitmap.createBitmap(crop, bx, by, bw2, bh2); crop.recycle(); crop = c2 }
                }
            }
            val small = Bitmap.createScaledBitmap(crop, outW, outH, true)
            val px = IntArray(outW * outH); small.getPixels(px, 0, outW, 0, 0, outW, outH)
            val dark = px.count { ((it shr 16 and 0xFF) + (it shr 8 and 0xFF) + (it and 0xFF)) < 120 }
            val os = java.io.ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.WEBP_LOSSY, 92, os)
            if (crop !== small) crop.recycle(); small.recycle()
            // nitidez: quadro em movimento (rolagem) borra a bandeira; o gradiente médio é menor. Guarda o MAIS nítido de cada jogador.
            fun g(c: Int) = ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3
            var grad = 0L; var n = 0
            for (yy in 0 until outH - 1) for (xx in 0 until outW - 1) {
                val c0 = g(px[yy * outW + xx]); grad += Math.abs(c0 - g(px[yy * outW + xx + 1])) + Math.abs(c0 - g(px[(yy + 1) * outW + xx])); n++
            }
            if (dark > px.size * 0.8) null else Pair(grad.toFloat() / n.coerceAtLeast(1), android.util.Base64.encodeToString(os.toByteArray(), android.util.Base64.NO_WRAP))
        }
    } catch (e: Exception) { null }

    /** Bandeira de cada jogador do Plantel, recortada da captura em resolução cheia (plano `natimg`: nameKey -> WebP base64). */
    private val lastRowY = HashMap<Int, Map<String, Float>>()
    private suspend fun saveFlagThumbs(bmp: Bitmap, slot: Int, players: List<PlayerRead>) {
        // lista em movimento (rolagem) = bandeira borrada: compara a altura das linhas com o quadro anterior e só recorta com a lista parada
        val ys = players.mapNotNull { p -> p.natY?.let { Txt.key(p.name) to it } }.toMap()
        val prev = lastRowY.put(slot, ys)
        var moving = false
        if (prev != null) {
            val d = ys.mapNotNull { (k, y) -> prev[k]?.let { Math.abs(it - y) } }
            if (d.size >= 2 && d.sorted()[d.size / 2] > 0.003f) moving = true
        }
        FlagWeb.ensureAll(ctx)   // bandeiras de referência (Flagpedia) para descobrir o país
        val mine = repo.dao.playersOf(slot).filter { it.owner == "MY" }
        // chave do jogador = a MESMA do banco (o OCR varia "Fabinho II"/"Fabinho I|": chaves soltas deixavam bandeiras borradas sem par)
        fun canon(p: PlayerRead): String {
            val k = Txt.key(p.name)
            return mine.firstOrNull { it.nameKey == k }?.nameKey
                ?: mine.firstOrNull { SquadDedupe.samePlayer(it.nameKey, it.age, it.strength, k, p.age, p.strength) }?.nameKey ?: k
        }
        val cur = repo.dao.plan(slot, "natimg3")?.json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        // referência de nitidez = a MEDIANA das guardadas (com o máximo, uma bandeira de alto contraste como a dos EUA, q≈78, fazia recusar todas as
        // novas, q≈20–31: o Fabinho II ficou sem recorte nenhum e só tinha a miniatura de 8×5 células)
        val qs = cur.keys().asSequence().mapNotNull { Flags.imgQuality(cur.optString(it)) }.sorted().toList()
        val refQ = if (qs.isEmpty()) 0f else qs[qs.size / 2]
        var n = 0
        for (p in players) {
            val x = p.natX ?: continue; val y = p.natY ?: continue
            val key = canon(p)
            if (key.isBlank()) continue
            val old = Flags.imgQuality(cur.optString(key))
            // lista rolando: só tenta quem ainda NÃO tem miniatura (jogador novo, ex. N'Guessan), e só se nítida o bastante
            if (moving && old != null) continue
            val t = thumb(bmp, x, y, 0.040f, 0.062f, 48, 32, refine = true) ?: continue   // a bandeira do plantel mede 0,028×0,043 (36×24 px em 1280×564): a caixa tem ~1,5 px de folga
            // sem imagem boa ainda: só entra se não for bem mais borrada que as já guardadas; depois só troca por uma mais nítida
            val ok = if (old == null) (refQ <= 0f || t.first >= refQ * 0.6f) else t.first > old * 1.08f + 1f
            if (ok) { cur.put(key, "q:${(t.first * 10).toInt()}|${t.second}"); n++ }
        }
        if (n > 0) {
            repo.dao.putPlan(PlanEntity(slot, "natimg3", cur.toString(), System.currentTimeMillis())); UiBus.bump()
            runCatching { updateFlagBank(cur) }
        }
    }

    /** Banco global de bandeiras (plano `natbank`, país → miniatura mais nítida): vale para os 4 slots e para jogadores lidos borrados. */
    private suspend fun updateFlagBank(imgs: JSONObject) {
        val bank = repo.dao.plan(0, "natbank")?.json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        val atlas = Atlas.load(repo, Atlas.Kind.NAT)
        var ch = 0
        for (k in imgs.keys()) {
            val v = imgs.optString(k)
            val c = FlagIdent.identify(ctx.filesDir, v, atlas) ?: continue   // banco por código ISO do país
            val old = Flags.imgQuality(bank.optString(c))
            val q = Flags.imgQuality(v) ?: 0f
            if (old == null || q > old * 1.08f + 1f) { bank.put(c, v); ch++ }
        }
        if (ch > 0) repo.dao.putPlan(PlanEntity(0, "natbank", bank.toString(), System.currentTimeMillis()))
    }

    /** Faixa de clubes do topo do Calendário: escudo + nome de cada clube da liga (um por clube; só grava o que ainda não existe). */
    private fun saveStripCrests(bmp: Bitmap, spots: List<CrestSpot>) {
        for (s in spots) CrestWeb.request(ctx, s.team)
        for (s in spots) {
            if (CrestCut.find(ctx.filesDir, s.team) != null) continue
            try { if (CrestCut.save(ctx.filesDir, bmp, s.team, s.x, s.y, 0.075f, 0.125f, expW = 0.043f, expH = 0.10f)) UiBus.bump() } catch (e: Exception) { Diag.lastError = "Escudo da liga: " + (e.message ?: "erro") }
        }
    }

    private fun saveCalendarCrests(bmp: Bitmap, matches: List<MatchRead>) {
        for (m in matches) CrestWeb.request(ctx, m.opponent)
        for (m in matches) {
            // card de jogo JÁ JOGADO mostra o placar onde ficaria o escudo: só cards sem placar têm escudo
            if (m.scoreMine != null || m.scoreOpp != null || m.result != null) continue
            val cx = m.crestX ?: continue; val cy = m.crestY ?: continue
            val team = m.opponent ?: continue
            if (CrestCut.find(ctx.filesDir, team) != null) continue
            try { if (CrestCut.save(ctx.filesDir, bmp, team, cx, cy, 0.08f, 0.15f, expW = 0.0525f, expH = 0.125f)) UiBus.bump() } catch (e: Exception) { Diag.lastError = "Escudo do calendário: " + (e.message ?: "erro") }
        }
    }

    /** Os dois escudos do Pré-jogo (o rival e o meu) entram no arquivo global por nome do time (o escudo de um clube não muda). */
    private fun savePregameCrest(bmp: Bitmap, ex: Extraction) {
        val rival = ex.fields[K.RIVAL_TEAM]?.value?.takeIf { it.isNotBlank() } ?: return
        val home = ex.fields[K.HOME]?.value ?: return
        val mineLeft = home == "Casa"
        CrestWeb.request(ctx, rival); CrestWeb.request(ctx, ex.fields[K.TEAM]?.value)
        try {
            if (CrestCut.find(ctx.filesDir, rival) == null &&
                CrestCut.save(ctx.filesDir, bmp, rival, if (mineLeft) 0.767f else 0.233f, 0.258f, 0.10f, 0.17f, expW = 0.065f, expH = 0.158f)) UiBus.bump()
            ex.fields[K.TEAM]?.value?.takeIf { CrestCut.find(ctx.filesDir, it) == null }?.let { mine ->
                if (CrestCut.save(ctx.filesDir, bmp, mine, if (mineLeft) 0.233f else 0.767f, 0.258f, 0.10f, 0.17f, expW = 0.065f, expH = 0.158f)) UiBus.bump()
            }
        } catch (e: Exception) { Diag.lastError = "Escudo do Pré-jogo: " + (e.message ?: "erro") }
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
            // telas de filtro Nacionalidade/Liga: aprende bandeira + nome (Atlas, global)
            Atlas.detect(ocr)?.let { k -> runCatching { Atlas.learn(repo, ocr, img, k, now) } }
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
            // campo com os 11 (Equipa inicial) pode cair em NOISE/OTHER: conta os cartões antes de descartar
            if (type != ScreenType.NON_OSM) machine.current?.let { sl -> runCatching { scanLineup(sl, ocr, now) } }
            if (type == ScreenType.NOISE || type == ScreenType.NON_OSM) {
                Diag.discarded.incrementAndGet()
                Diag.log(if (type == ScreenType.NOISE) "Descartada: transição/tela vazia" else "Descartada: sem barra do OSM")
                return
            }
            // passou para outra tela: fecha o resumo da anterior (Resultado, Calendário, Plantel, Mercado, Tática)
            // saiu da tela em leitura: 2 quadros seguidos de outra tela (não por tempo; telas intermediárias da rolagem, OTHER_OSM, não contam)
            pending?.let { pd ->
                if (pd.type == type) pd.offFrames = 0
                else if (type != ScreenType.OTHER_OSM) { pd.offFrames++; if (pd.offFrames >= 2) runCatching { flushPending() } }
            }
            // início da passada pela Análise do rival: as 2 telas dela precisam ser lidas DEPOIS daqui (conteúdo, não relógio)
            if (type != ScreenType.OTHER_OSM) { if (type == ScreenType.REPORT && reportPrev != ScreenType.REPORT) reportPassAt = now; reportPrev = type }
            Diag.valid.incrementAndGet()
            lastOsmAcceptedAt = now

            var ex = parse(ocr, type, img, now)
            if (type == ScreenType.SQUAD) ex = runCatching { repairStrength(bmp, ex) }.getOrDefault(ex)
            if (type == ScreenType.SQUAD) ex = runCatching { repairRows(bmp, ocr, ex) }.getOrDefault(ex)
            if (type == ScreenType.SQUAD) ex = runCatching { repairNames(bmp, ex) }.getOrDefault(ex)
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
            if (changed > 0) UiBus.bump()   // tudo o que a captura gravou aparece no app sozinho
            if (asgSlot != null && type == ScreenType.SQUAD && (ex.ownerTeam != null || pending?.let { it.slot == asgSlot && it.type == ScreenType.SQUAD } == true) && ex.players.isNotEmpty()) runCatching { saveFlagThumbs(bmp, asgSlot, ex.players) }
            if (asgSlot != null && type == ScreenType.PREGAME) savePregameCrest(bmp, ex)
            // o Calendário mostra o escudo de cada rival: guarda uma vez por clube (só grava o que ainda não tem)
            if (type == ScreenType.CALENDAR) { saveCalendarCrests(bmp, ex.matches); saveStripCrests(bmp, ex.crests) }
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
                try { screenNote(asgSlot, type, ex, ocr) } catch (e: Exception) { Diag.lastError = "Aviso de leitura: " + (e.message ?: "") }
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
