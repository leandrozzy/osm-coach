package com.osmaicoach.collector

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import kotlin.math.abs

/**
 * Melhorias da aba Tática (só leitura do que o app já decidiu; nenhum valor da tática é alterado):
 * nota de coerência, plano B, biblioteca "contra cada formação", contexto do jogo (duelo de formações + árbitro).
 * A nota NÃO é chance de vitória: só confere se a tática combina com ela mesma, com o árbitro, com as faixas das fontes e com o seu histórico.
 */
object TacticAudit {
    data class Result(val score: Int, val label: String, val problems: List<String>, val good: List<String>, val tips: List<String>)

    fun evaluate(
        t: JSONObject, bands: JSONObject?, refereeTackle: String?, counterOk: Boolean?,
        avoided: List<String>, mountDiffs: List<String>
    ): Result {
        var score = 100
        val problems = ArrayList<String>(); val good = ArrayList<String>(); val tips = ArrayList<String>()
        val risks = Why.coherenceRisks(t)
        for (r in risks) { problems.add(r); score -= 8 }
        if (risks.isEmpty()) good.add("Pressão, estilo, ritmo e instruções combinam entre si")
        if (bands != null) {
            for ((name, key) in listOf("Pressão" to "pressure", "Estilo" to "mentality", "Ritmo" to "tempo")) {
                val a = bands.optJSONArray(key) ?: continue
                val v = t.optInt(key); val lo = a.optInt(0); val hi = a.optInt(1)
                // faixa dos SITES = só referência (o app não a usa para decidir): vira aviso informativo, nunca problema e não tira nota
                if (v in lo..hi) good.add("$name $v dentro do que os sites recomendam ($lo–$hi)")
                else {
                    val dist = minOf(abs(v - lo), abs(v - hi))
                    tips.add("Aviso: $name $v está $dist ponto(s) ${if (v > hi) "acima" else "abaixo"} do que os sites recomendam ($lo–$hi). Só referência: o app escolheu pela simulação e pelos seus jogos. Para seguir o site: ${v.coerceIn(lo, hi)}")
                }
            }
        }
        refereeTackle?.let { lim ->
            val li = Osm.TACKLES.indexOf(lim); val ti = Osm.TACKLES.indexOf(t.optString("tackle"))
            if (li >= 0 && ti > li) { problems.add("Desarme ${t.optString("tackle")} acima do que o árbitro permite ($lim): risco de cartões"); score -= 10 }
            else if (li >= 0 && ti >= 0) good.add("Desarme compatível com o árbitro")
        }
        when (counterOk) {
            true -> good.add("Combina com a contra-tática conhecida para a formação do rival")
            false -> tips.add("Fora da contra-tática conhecida para a formação do rival (não é erro: veja o seu histórico)")
            null -> {}
        }
        for (a in avoided) { problems.add("$a está na sua lista EVITE (rendeu menos do que a força explica)"); score -= 12 }
        for (m in mountDiffs.take(3)) { tips.add("No OSM está diferente: $m"); score -= 2 }
        score = score.coerceIn(30, 100)
        val label = when { score >= 90 -> "Excelente"; score >= 75 -> "Boa"; score >= 60 -> "Atenção"; else -> "Revisar" }
        return Result(score, label, problems, good, tips)
    }

    suspend fun load(repo: Repo, slot: Int, tp: PlanEntity, now: Long = System.currentTimeMillis()): Result? {
        val j = runCatching { JSONObject(tp.json) }.getOrNull() ?: return null
        val f = repo.fieldMap(slot)
        if (!Director.tacticValid(j, tp.at, f, now)) return null
        val formation = j.optString("formation")
        val style = Osm.style(j.optString("playStyle")) ?: j.optString("playStyle")
        fun v(k: String) = f[k]?.value?.takeIf { FieldMerge.known(it) }
        val bands = WebKnow.bands(repo)?.optJSONObject(Formations.canonical(formation) ?: formation)
        val ref = v(K.REFEREE)?.let { Referee.tackle(it) }
        val my = v(K.MY_STRENGTH)?.toIntOrNull(); val rv = v(K.RIVAL_STRENGTH)?.toIntOrNull()
        val rf = v(K.RIVAL_FORMATION)
        val counter = if (rf != null && CounterBook.suggestion(rf, (if (my != null && rv != null) my - rv else 0)) != null)
            CounterBook.matches(rf, if (my != null && rv != null) my - rv else 0, formation, style) else null
        val avoided = ArrayList<String>()
        runCatching { HumanPrior.build(Director.allLogs(repo), null) }.getOrNull()?.let { p ->
            if (p.avoidForm.contains(Formations.base(formation))) avoided.add("Formação ${Formations.base(formation)}")
            if (p.avoidStyle.contains(style)) avoided.add("Estilo $style")
        }
        val diffs = runCatching { Hints.mountDiffs(repo, slot, tp, j, f) }.getOrDefault(emptyList())
        return evaluate(j, bands, ref, counter, avoided, diffs)
    }
}

/** Anel com a nota 0–100. */
@Composable
internal fun ScoreRing(score: Int, size: androidx.compose.ui.unit.Dp = 74.dp) {
    val col = when { score >= 90 -> C.OK; score >= 75 -> C.PRIMARY; score >= 60 -> C.WARN; else -> C.BAD }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = this.size.minDimension * 0.11f
            val tl = Offset(sw / 2, sw / 2)
            val sz = Size(this.size.width - sw, this.size.height - sw)
            drawArc(Color(0xFF1B3260), -90f, 360f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
            drawArc(col, -90f, 360f * score.coerceIn(0, 100) / 100f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$score", fontSize = (size.value * 0.30f).sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Text("/100", fontSize = (size.value * 0.13f).sp, color = C.MUTED)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AuditView(r: TacticAudit.Result) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).osmPanel().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScoreRing(r.score)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text("Coerência da tática", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                Text(r.label, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = when { r.score >= 90 -> C.OK; r.score >= 75 -> C.PRIMARY; r.score >= 60 -> C.WARN; else -> C.BAD })
                Text("Não é chance de vitória: só confere se a tática combina consigo, com o árbitro, com as faixas das fontes e com o seu histórico.", fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((lab, n, col) in listOf(Triple("Problemas", r.problems.size, C.BAD), Triple("Bom", r.good.size, C.OK), Triple("Sugestões", r.tips.size, C.GOLD))) {
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Color(0xFF0A1830)).border(1.dp, Color(0x332AB4FF), RoundedCornerShape(12.dp)).padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(lab, fontSize = 11.5.sp, color = C.MUTED)
                    Text("$n", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = col)
                }
            }
        }
        if (r.problems.isEmpty()) Text("✅ Nenhum problema encontrado: a tática parece sólida.", fontSize = 13.sp, color = C.OK, modifier = Modifier.padding(top = 8.dp))
        for (p in r.problems) Text("⚠ $p", fontSize = 13.sp, color = C.BAD, modifier = Modifier.padding(top = 4.dp))
        for (t in r.tips) Text("💡 $t", fontSize = 13.sp, color = C.GOLD, modifier = Modifier.padding(top = 4.dp))
        for (g in r.good) Text("✅ $g", fontSize = 13.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
internal fun AuditCard(slot: Int, d: SlotData) {
    val ctx = LocalContext.current
    val tp = d.tactic ?: return
    val r = rememberLoaded<TacticAudit.Result?>(null, 0, tp.at) { runCatching { TacticAudit.load(Repo(ctx), slot, tp) }.getOrNull() } ?: return
    AuditView(r)
}

/** Plano B (a alternativa que a simulação deixou em segundo lugar), com as mesmas barras da tática principal. */
data class AltPlan(val formation: String, val style: String, val pressure: Int, val mentality: Int, val tempo: Int)

internal suspend fun loadAltPlan(repo: Repo, slot: Int, tp: PlanEntity): AltPlan? {
    val j = runCatching { JSONObject(tp.json) }.getOrNull() ?: return null
    val formation = j.optString("formation")
    val style = Osm.style(j.optString("playStyle")) ?: j.optString("playStyle")
    val altJ = repo.dao.plan(slot, "whyalt")?.json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
    val ok = altJ.optString("main") == "$formation|${j.optString("playStyle")}" || (altJ.optString("main").startsWith(Formations.base(formation)) && altJ.optString("main").endsWith("|$style"))
    if (!ok) return null
    val a = altJ.optJSONObject("alt") ?: return null
    return AltPlan(a.optString("formation"), a.optString("style"), a.optInt("pressure"), a.optInt("mentality"), a.optInt("tempo"))
}

@Composable
internal fun PlanBView(a: AltPlan) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).osmPanel().padding(14.dp)) {
        Text("🔁 Plano B", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        Text(a.formation, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = C.PRIMARY, modifier = Modifier.padding(top = 6.dp))
        Text(a.style, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((lab, v, sub) in listOf(Triple("Pressão", a.pressure, Osm.pressureLabel(a.pressure)), Triple("Estilo", a.mentality, Osm.mentalityLabel(a.mentality)), Triple("Ritmo", a.tempo, Osm.tempoLabel(a.tempo)))) {
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Color(0xFF0A1830)).border(1.dp, Color(0x332AB4FF), RoundedCornerShape(12.dp)).padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(lab, fontSize = 11.5.sp, color = C.MUTED)
                    Text("$v", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    Text(sub, fontSize = 11.sp, color = C.PRIMARY, maxLines = 1)
                }
            }
        }
        Text("Fica de reserva: use se o rival mudar a tática ou se a leitura dele estiver incerta. Não substitui a principal.", fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
internal fun PlanBCard(slot: Int, d: SlotData) {
    val ctx = LocalContext.current
    val tp = d.tactic ?: return
    val a = rememberLoaded<AltPlan?>(null, 0, tp.at) { runCatching { loadAltPlan(Repo(ctx), slot, tp) }.getOrNull() } ?: return
    PlanBView(a)
}

// ---------------------------------------------------------------- contra cada formação do rival

object VsLibrary {
    data class Row(val mine: String, val n: Int, val v: Int, val e: Int, val d: Int) {
        val ppg: Double get() = if (n == 0) 0.0 else (3 * v + e).toDouble() / n
    }
    data class Group(val rival: String, val games: Int, val rows: List<Row>)

    fun build(logs: List<JSONObject>, avoidForm: Collection<String> = emptyList(), avoidStyle: Collection<String> = emptyList()): List<Group> {
        val byRival = LinkedHashMap<String, ArrayList<JSONObject>>()
        for (l in logs) {
            val res = l.optString("result")
            if (res !in listOf("V", "E", "D")) continue
            val rf = l.optString("rivalFormationRead").takeIf { it.isNotBlank() && it != "?" } ?: continue
            byRival.getOrPut(Formations.base(rf)) { ArrayList() }.add(l)
        }
        return byRival.map { (rival, ls) ->
            val by = LinkedHashMap<String, ArrayList<JSONObject>>()
            for (l in ls) {
                val st = Osm.style(l.optString("playStyle")) ?: l.optString("playStyle")
                by.getOrPut(Formations.base(l.optString("formation")) + " • " + st) { ArrayList() }.add(l)
            }
            val rows = by.map { (k, xs) ->
                Row(k, xs.size, xs.count { it.optString("result") == "V" }, xs.count { it.optString("result") == "E" }, xs.count { it.optString("result") == "D" })
            }.sortedWith(compareByDescending<Row> { it.ppg }.thenByDescending { it.n })
            Group(rival, ls.size, rows)
        }.sortedByDescending { it.games }.take(6)
    }
}

@Composable
internal fun VsLibraryView(groups: List<VsLibrary.Group>, avoid: Set<String> = emptySet(), open: Boolean = false) {
    Fold("📚", "Contra cada formação do rival", "${groups.size}", startOpen = open) {
        if (groups.isEmpty()) {
            Text("Ainda sem jogos com a formação do rival lida. Depois de cada Resultado o app monta esta lista sozinho.", fontSize = 13.sp, color = C.MUTED)
            return@Fold
        }
        for (g in groups) {
            Text("vs ${g.rival} • ${g.games} jogo(s)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = C.GOLD, modifier = Modifier.padding(top = 8.dp))
            for (r in g.rows.take(4)) {
                val small = r.n < 3
                Row(Modifier.fillMaxWidth().padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.mine + if (r.mine.substringBefore(" •") in avoid) "  ⛔" else "", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("${r.v}V ${r.e}E ${r.d}D", fontSize = 13.sp, color = if (small) C.MUTED else C.OK)
                }
                if (small) Text("amostra pequena (${r.n}): pode ser sorte", fontSize = 11.5.sp, color = C.MUTED)
            }
        }
        Text("Só jogos seus já lidos. ⛔ = está na lista EVITE. Poucos jogos não provam nada.", fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
internal fun VsLibraryCard() {
    val ctx = LocalContext.current
    val data = rememberLoaded<Pair<List<VsLibrary.Group>, Set<String>>?>(null, UiBus.version, "vslib") {
        runCatching {
            val repo = Repo(ctx)
            val logs = Director.allLogs(repo)
            val p = runCatching { HumanPrior.build(logs, null) }.getOrNull()
            Pair(VsLibrary.build(logs), (p?.avoidForm?.toSet() ?: emptySet()) + (p?.avoidStyle?.toSet() ?: emptySet()))
        }.getOrNull()
    } ?: return
    VsLibraryView(data.first, data.second)
}

// ---------------------------------------------------------------- contexto do jogo: duelo de formações + árbitro

/** Os dois times no campo (você à esquerda, rival à direita, espelhado), só para ver como as linhas se encaixam. */
@Composable
internal fun DuelPitch(mine: String?, rival: String?, modifier: Modifier = Modifier) {
    val a = Formations.lines(Formations.canonical(mine) ?: mine.orEmpty())
    val b = Formations.lines(Formations.canonical(rival) ?: rival.orEmpty())
    Canvas(modifier) {
        val r = Rect(Offset(size.width * 0.01f, size.height * 0.04f), Size(size.width * 0.98f, size.height * 0.92f))
        pitch(r, vertical = false)
        fun at(x: Float, y: Float) = Offset(r.left + r.width * x, r.top + r.height * y)
        val tr = r.height * 0.075f
        fun team(lines: List<Int>, left: Boolean, col: Color) {
            if (lines.isEmpty()) return
            val side = if (left) 1f else -1f
            val base = if (left) 0f else 1f
            token(at(base + side * 0.05f, 0.5f), tr, col)
            val n = lines.size
            lines.forEachIndexed { i, k ->
                val x = base + side * (0.15f + 0.31f * (if (n == 1) 0f else i / (n - 1f)))
                for (p in 0 until k) token(at(x, (p + 1f) / (k + 1f)), tr, col)
            }
        }
        team(a, true, Ill.MINE)
        team(b, false, Ill.RIVAL)
    }
}

/** Medidor de rigor do árbitro (0 = muito brando … 4 = muito rigoroso), no estilo dos medidores do desarme. */
@Composable
internal fun GameContextView(mine: String?, rival: String?, refereeName: String?, refereeTackle: String?, home: Boolean?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).osmPanel().padding(14.dp)) {
        Text("🏟 Contexto do jogo", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        if (!mine.isNullOrBlank() && !rival.isNullOrBlank()) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("Você ${Formations.canonical(mine) ?: mine}", fontSize = 12.5.sp, color = C.PRIMARY, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("Rival ${Formations.canonical(rival) ?: rival}", fontSize = 12.5.sp, color = Color(0xFFFF7B7B), fontWeight = FontWeight.Bold)
            }
            DuelPitch(mine, rival, Modifier.fillMaxWidth().height(124.dp).padding(top = 4.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            RefereeFace(Referee.level(refereeName), Modifier.height(78.dp))
            Column(Modifier.padding(start = 12.dp)) {
                Text("Árbitro: ${refereeName ?: "não lido"}", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(if (refereeTackle != null) "Desarme permitido até $refereeTackle" else "Leia o Pré-jogo (termômetro)", fontSize = 12.5.sp, color = C.MUTED)
                home?.let { Text(if (it) "🏠 Jogo em casa" else "✈ Jogo fora de casa", fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 2.dp)) }
            }
        }
    }
}

@Composable
internal fun GameContextCard(d: SlotData) {
    val rf = known(d.fields, K.RIVAL_FORMATION)
    val ref = known(d.fields, K.REFEREE)
    if (rf == null && ref == null) return
    val mine = d.tactic?.let { runCatching { JSONObject(it.json).optString("formation") }.getOrNull() }
    GameContextView(mine, rf, ref, Referee.tackle(ref), when (known(d.fields, K.HOME)) { "Casa" -> true; "Fora" -> false; else -> null })
}
