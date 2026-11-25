package com.osmaicoach.collector

import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun fmtAt(t: Long): String = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")).format(Date(t))

/** Tudo que os cartões de liga precisam, lido do banco (nada pedido ao usuário). */
internal class LeagueView(
    val cov: LeagueResults.Coverage?, val adj: LeagueResults.AdjResult?, val movement: LeagueResults.Movement?, val projection: LeagueResults.Projection?,
    val stAt: Long?, val clubs: Int, val radar: LeagueResults.Radar?, val partial: Boolean = false,
    /** De onde vem a tabela usada: "classificação lida" ou "resultados lidos" (calculada com TODAS as jornadas completas). */
    val source: String? = null, val table: List<LeagueResults.Adj> = emptyList(), val me: String? = null, val tieAmbiguous: Boolean = false, val leagueTotal: Int? = null,
    val cup: Map<Int, List<LeagueResults.Stored>> = emptyMap(), val myRank: Int? = null
)

internal suspend fun loadLeagueView(repo: Repo, slot: Int): LeagueView {
    val f = repo.fieldMap(slot)
    val lg = repo.dao.plan(slot, "league")?.json?.let { runCatching { JSONObject(it) }.getOrNull() }
    val lres = repo.dao.plan(slot, "lres2")?.json?.let { runCatching { JSONObject(it) }.getOrNull() }
    val stAll = League.dedupe(League.table(lg, "st"))
    val stAtL = lg?.optLong("stAt")?.takeIf { it > 0 }
    // linhas lidas em passadas diferentes (horas de distância) = tabela misturada: não usa
    val partial = stAtL != null && stAll.any { (it.at ?: 0L) < stAtL - 180_000L }
    val comp = LeagueResults.compKey(f)
    val games = if (comp != null) LeagueResults.games(lres, comp) else emptyList()
    val me = known(f, K.TEAM) ?: known(f, K.HUB_TITLE)
    val played = known(f, K.ROUND_DONE)?.filter { it.isDigit() }?.toIntOrNull()
    val cov = comp?.let { LeagueResults.coverage(lres, it, stAll.size.takeIf { n -> n >= 4 && !partial }, played) }
    // tabela: a Classificação lida (se inteira e de uma vez); senão, a calculada pelos resultados quando TODAS as jornadas estão completas
    val derived = LeagueResults.tableFromResults(games)
    val useStandings = stAll.size >= 6 && !partial
    val st = if (useStandings) stAll else derived?.rows ?: emptyList()
    val source = if (useStandings) "classificação lida em ${fmtAt(stAtL ?: 0L)}" else if (derived != null) "resultados lidos (J1 a J${derived.rounds}, todas completas)" else null
    val adj = if (st.isNotEmpty()) LeagueResults.adjust(st, games) else null
    val mv = if (useStandings) LeagueResults.movement(lg?.optJSONArray("snaps"), me, games) else null
    val clubs = if (st.isNotEmpty()) st.size else cov?.clubs ?: 0
    // o total do hub pode incluir a copa (43); a liga de pontos corridos tem 2×(clubes−1) rodadas
    val rawTotal = known(f, K.ROUND_TOTAL)?.filter { it.isDigit() }?.toIntOrNull()
    val total = if (clubs >= 6 && clubs % 2 == 0 && (rawTotal == null || rawTotal > 2 * (clubs - 1))) 2 * (clubs - 1) else rawTotal
    val hasNext = known(f, K.MATCH_AT)?.toLongOrNull()?.let { it > System.currentTimeMillis() } == true && known(f, K.RIVAL_TEAM) != null
    val proj = adj?.let { LeagueResults.project(it, me, total, hasNext) }
    val radar = LeagueResults.radar(games, known(f, K.RIVAL_TEAM), st, stAtL)
    val cup = comp?.let { c -> LeagueResults.games(lres, "copa|$c").groupBy { it.round }.toSortedMap() } ?: emptyMap()
    return LeagueView(cov, adj, mv, proj, stAtL, stAll.size, radar, partial, source, adj?.rows?.sortedByDescending { it.pts } ?: emptyList(), me, derived?.tieAmbiguous == true && !useStandings, total, cup,
        adj?.let { LeagueResults.rankOf(it.rows, me) })
}

private fun phaseName(n: Int) = when (n) { 1 -> "Fase preliminar"; 2 -> "Oitavos de final"; 3 -> "Quartos de final"; 4 -> "Meias finais"; else -> "Final" }

/** Barras de pontos de todos os clubes (o seu em dourado): dá para ver de relance quem está perto. */
@Composable
internal fun PointsChart(rows: List<LeagueResults.Adj>, me: String?) {
    if (rows.isEmpty()) return
    val max = maxOf(1, rows.maxOf { it.pts })
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        for ((i, r) in rows.withIndex()) {
            val mine = me != null && League.key(r.club) == League.key(me)
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).then(if (mine) Modifier.clip(RoundedCornerShape(12.dp)).background(C.GOLD.copy(alpha = 0.14f)).padding(horizontal = 4.dp) else Modifier), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}º", fontSize = 12.sp, color = if (mine) C.GOLD else C.MUTED, fontWeight = if (mine) FontWeight.ExtraBold else FontWeight.Normal, modifier = Modifier.width(28.dp))
                RivalShield(r.club, 24.dp)
                Spacer(Modifier.width(6.dp))
                Text(r.club, fontSize = 12.sp, color = if (mine) C.GOLD else androidx.compose.ui.graphics.Color.White, fontWeight = if (mine) FontWeight.ExtraBold else FontWeight.Normal, maxLines = 1, modifier = Modifier.width(96.dp), overflow = TextOverflow.Ellipsis)
                Box(Modifier.weight(1f).height(9.dp).clip(RoundedCornerShape(5.dp)).background(C.SURFACE2)) {
                    Box(Modifier.fillMaxWidth(r.pts.toFloat() / max).height(9.dp).clip(RoundedCornerShape(5.dp)).background(if (mine) C.GOLD else C.PRIMARY))
                }
                Text("${r.pts}", fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
    }
}

/** Faixa de posições possíveis: cada quadradinho é uma posição; dourado = onde você pode terminar (certeza), marcador = palpite. */
@Composable
internal fun FinishBand(clubs: Int, best: Int, worst: Int, estimate: Int?, now: Int?) {
    if (clubs < 2) return
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (pos in 1..clubs) {
                val inBand = pos in best..worst
                val col = when { estimate == pos -> C.OK; now == pos -> androidx.compose.ui.graphics.Color.White; inBand -> C.GOLD.copy(alpha = 0.75f); else -> C.SURFACE2 }
                Box(Modifier.weight(1f).height(16.dp).clip(RoundedCornerShape(3.dp)).background(col))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            Text("1º", fontSize = 10.sp, color = C.MUTED, modifier = Modifier.weight(1f))
            Text("${clubs}º", fontSize = 10.sp, color = C.MUTED)
        }
        Text("■ dourado = onde pode terminar (certeza matemática)   ■ branco = hoje   ■ verde = palpite pelo ritmo", fontSize = 10.5.sp, color = C.MUTED)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun LeagueResultsCard(slot: Int) {
    val ctx = LocalContext.current
    val v = rememberLoaded<LeagueView?>(null, UiBus.version, "lv$slot") { runCatching { loadLeagueView(Repo(ctx), slot) }.getOrNull() } ?: return
    // O card "Sua posição na liga" (jornadas lidas, tabela cruzada, cenários) foi RETIRADO em 25/11: com o print real do usuário ele inventava jornada (J22),
    // contava rodada de copa como "não lida" (J6, J11), estimava errado quantos jogos cada jornada tem (10/13) e afirmava leitura incompleta de telas já lidas.
    // A posição e os pontos vêm do Hub (Visão geral); a copa abaixo foi conferida com o print real.
    if (v.cup.isEmpty()) return
    if (v.cup.isNotEmpty()) Fold("🏆", "Copa: resultados lidos", "${v.cup.values.sumOf { it.size }} jogos", startOpen = false) {
        for ((phase, gs) in v.cup) {
            Text(phaseName(phase), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = C.GOLD, modifier = Modifier.padding(top = 6.dp))
            for (g in gs) MatchLineNames(g.home, g.away, "${g.gh} – ${g.ga}", "")
        }
        Text("A copa é guardada à parte: não entra na tabela da liga.", fontSize = 11.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
internal fun RivalRadarCard(slot: Int, d: SlotData) {
    val ctx = LocalContext.current
    val v = rememberLoaded<LeagueView?>(null, UiBus.version, "radar$slot") { runCatching { loadLeagueView(Repo(ctx), slot) }.getOrNull() }
    val r = v?.radar
    Fold("🛰", "Radar do rival", r?.let { if (it.games.isEmpty()) "campanha" else "${it.games.size} jogo(s)" }, startOpen = true) {
        if (r == null) {
            Text("Sem partidas do rival lidas. Abra os resultados da liga no OSM (jornadas anteriores) com a captura ligada: o Radar monta sozinho.", fontSize = 13.sp, color = C.MUTED)
            return@Fold
        }
        r.campaign?.let { c -> Text("Campanha na liga (classificação lida): ${c.j} jogos • ${c.v}V ${c.e}E ${c.d}D • gols ${c.gf}–${c.ga} • ${c.pts} pts", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        if (r.games.isEmpty()) Text("Nenhum jogo individual dele lido ainda: abra os resultados de cada jornada (uma vez por jornada, o app guarda).", fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 2.dp))
        else Text("${r.rival}: últimas ${r.games.size} partida(s) confirmadas nesta competição", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        if (r.games.isNotEmpty() && r.games.size < 5) Text("Só aparecem jogos que passaram pela tela de resultados com a captura ligada. Abra os resultados das jornadas anteriores para completar (cada jornada uma vez).", fontSize = 11.5.sp, color = C.MUTED)
        for (g in r.games) Text(
            "J${g.round} • ${if (g.home) "casa" else "fora"} • ${g.opponent}${g.oppRank?.let { " (${it}º)" } ?: ""} • ${g.gf}×${g.ga} " + (if (g.gf > g.ga) "V" else if (g.gf < g.ga) "D" else "E"),
            fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp)
        )
        if (r.games.isNotEmpty()) { val gf = r.games.sumOf { it.gf }; val ga = r.games.sumOf { it.ga }
        Text("Marcou $gf • sofreu $ga em ${r.games.size} jogo(s)", fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
        r.homeAvg?.let { Text("Em casa: ${"%.1f".format(it.first)} marcados / ${"%.1f".format(it.second)} sofridos por jogo", fontSize = 12.5.sp, color = C.MUTED) }
        r.awayAvg?.let { Text("Fora: ${"%.1f".format(it.first)} marcados / ${"%.1f".format(it.second)} sofridos por jogo", fontSize = 12.5.sp, color = C.MUTED) }
        r.reference?.let { Text("Nível dos adversários: $it", fontSize = 11.5.sp, color = C.MUTED) }
        Text("Só consulta: não deduz formação nem estilo pelo placar e não altera a tática.", fontSize = 11.5.sp, color = C.MUTED)
    }
}

@Composable
internal fun BattleCard(slot: Int, d: SlotData) {
    val ctx = LocalContext.current
    if (known(d.fields, K.COMP_TYPE) != "Batalha") return
    val st = rememberLoaded<Battle.State?>(null, UiBus.version, "battle$slot") {
        runCatching {
            val repo = Repo(ctx)
            val j = repo.dao.plan(slot, "battle")?.json?.let { JSONObject(it) }
            Battle.state(j, null, repo.setting(Battle.SETTING, Battle.DEFAULT_TEAM))
        }.getOrNull()
    }
    Fold("⚔", "Central da batalha", st?.let { if (it.aggA != null) "${it.aggA}×${it.aggB}" else null }, startOpen = true) {
        if (st == null || st.aggA == null) { Text("Placar oficial ainda não lido: abra os resultados da batalha no OSM com a captura ligada.", fontSize = 13.sp, color = C.MUTED); return@Fold }
        val mineA = st.mine == "A"
        val me = if (mineA) st.teamA else st.teamB; val opp = if (mineA) st.teamB else st.teamA
        val ma = if (mineA) st.aggA else st.aggB; val oa = if (mineA) st.aggB else st.aggA
        if (st.mine == null) Text("Não achei sua equipe pelo nome (Ajustes → Minha equipe de batalha). Mostrando o placar real.", fontSize = 12.5.sp, color = C.WARN)
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).dsHero(C.INFO).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(me, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Box(Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(16.dp)).background(C.INFO.copy(alpha = 0.2f)).padding(horizontal = 14.dp, vertical = 6.dp)) {
                Text("${if (st.mine != null) ma else st.aggA} × ${if (st.mine != null) oa else st.aggB}", fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, color = C.INFO)
            }
            Text(opp, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        Text("Placar oficial acumulado${st.aggRound?.let { " (J$it)" } ?: ""} • lido ${fmtAt(st.aggAt)}. As linhas não são somadas de novo.", fontSize = 12.sp, color = C.MUTED)
        if (st.contribA != null) {
            val ca = if (mineA) st.contribA else st.contribB; val cb = if (mineA) st.contribB else st.contribA
            Text("Rodada: ${if (st.mine != null) ca else st.contribA} × ${if (st.mine != null) cb else st.contribB} pts (${st.contribSource})", fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
        st.myRow?.let { Text("Meu jogo: ${it.name} • ${it.games} jogo(s) • ${it.pts} pts", fontSize = 13.sp) }
        Text("Jogos pendentes: não lidos • sem regra de pontuação lida, não calculo \"empate basta\" nem \"precisa vencer\".", fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
    }
}
