package com.osmaicoach.collector

import androidx.compose.foundation.layout.Column
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
    val stAt: Long?, val clubs: Int, val radar: LeagueResults.Radar?
)

internal suspend fun loadLeagueView(repo: Repo, slot: Int): LeagueView {
    val f = repo.fieldMap(slot)
    val lg = repo.dao.plan(slot, "league")?.json?.let { runCatching { JSONObject(it) }.getOrNull() }
    val lres = repo.dao.plan(slot, "lres")?.json?.let { runCatching { JSONObject(it) }.getOrNull() }
    val st = League.table(lg, "st")
    val comp = LeagueResults.compKey(f)
    val games = if (comp != null) LeagueResults.games(lres, comp) else emptyList()
    val me = known(f, K.TEAM) ?: known(f, K.HUB_TITLE)
    val cov = comp?.let { LeagueResults.coverage(lres, it, st.size.takeIf { n -> n >= 4 }) }
    val adj = if (st.isNotEmpty()) LeagueResults.adjust(st, games) else null
    val mv = LeagueResults.movement(lg?.optJSONArray("snaps"), me, games)
    val total = known(f, K.ROUND_TOTAL)?.filter { it.isDigit() }?.toIntOrNull()
    val hasNext = known(f, K.MATCH_AT)?.toLongOrNull()?.let { it > System.currentTimeMillis() } == true && known(f, K.RIVAL_TEAM) != null
    val proj = adj?.let { LeagueResults.project(it, me, total, hasNext) }
    val radar = LeagueResults.radar(games, known(f, K.RIVAL_TEAM), st, lg?.optLong("stAt")?.takeIf { it > 0 })
    return LeagueView(cov, adj, mv, proj, lg?.optLong("stAt")?.takeIf { it > 0 }, st.size, radar)
}

@Composable
internal fun LeagueResultsCard(slot: Int) {
    val ctx = LocalContext.current
    val v = rememberLoaded<LeagueView?>(null, UiBus.version, "lv$slot") { runCatching { loadLeagueView(Repo(ctx), slot) }.getOrNull() } ?: return
    if (v.cov == null && v.stAt == null) return
    Fold("📊", "Resultados da liga e projeção", v.cov?.let { "${it.games} jogos" }, startOpen = false) {
        Hint("Lido sozinho das telas de resultados e da classificação do OSM enquanto a captura está ligada. Estimativa = cálculo do app; confirmado = lido da tela.")
        val c = v.cov
        if (c == null) Text("Nenhum resultado de outros jogos lido ainda: abra os resultados da jornada no OSM (e role).", fontSize = 13.sp, color = C.MUTED)
        else {
            Text("Cobertura: ${c.rounds} jornada(s), ${c.games} jogo(s)" + (c.expectedPerRound?.let { " (esperado ≈ $it por jornada)" } ?: "") + " • última leitura ${fmtAt(c.lastAt)}", fontSize = 13.sp)
            Text(c.perRound.entries.joinToString("  ") { "J${it.key}: ${it.value}" }, fontSize = 12.sp, color = C.MUTED)
        }
        v.stAt?.let { Text("Classificação lida em ${fmtAt(it)} (${v.clubs} clubes)", fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp)) }
        v.adj?.let { a ->
            val applied = a.rows.filter { it.applied.isNotEmpty() }
            if (applied.isNotEmpty()) Text("Estimativa: ${applied.size} clube(s) com resultado lido que a tabela ainda não incluía (somado uma vez só).", fontSize = 12.5.sp, color = C.WARN, modifier = Modifier.padding(top = 4.dp))
        }
        v.movement?.let { m ->
            Column(Modifier.padding(top = 8.dp)) {
                Text("Desde a leitura anterior da tabela: ${m.rankFrom}º → ${m.rankTo}º" + (if (m.ptsGain != 0) " (+${m.ptsGain} pts)" else ""), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                if (m.passed.isNotEmpty()) Text("Ultrapassei: ${m.passed.joinToString()}", fontSize = 13.sp, color = C.OK)
                if (m.closed.isNotEmpty()) Text("Encostaram (≤3 pts atrás): ${m.closed.joinToString()}", fontSize = 13.sp, color = C.WARN)
                if (m.affecting.isNotEmpty()) Text("Resultados que mexeram: ${m.affecting.take(6).joinToString(" • ")}", fontSize = 12.5.sp, color = C.MUTED)
                Text("(confirmado: duas tabelas lidas; o resultado de cada jogo é lido da tela de resultados)", fontSize = 11.5.sp, color = C.MUTED)
            }
        }
        v.projection?.let { p ->
            Column(Modifier.padding(top = 8.dp)) {
                if (p.remaining >= 0) {
                    Text("Faixa final CONFIRMADA (só aritmética, ${p.remaining} jogo(s) a disputar): ${p.confirmedBest}º a ${p.confirmedWorst}º", fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                    p.estimateRank?.let { Text("ESTIMATIVA pelo ritmo de pontos: ≈ $it º com ≈ ${p.estimatePts} pts" + (if (p.tieNote) " (empate de pontos com outro clube: desempate desconhecido)" else ""), fontSize = 13.sp) }
                }
                if (p.ifWin != null) Text("Próximo jogo (estimativa): vitória → ${p.ifWin}º • empate → ${p.ifDraw}º • derrota → ${p.ifLoss}º", fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                p.effectNote?.let { Text(it, fontSize = 11.5.sp, color = C.MUTED) }
                for (l in p.limits) Text("⚠ $l", fontSize = 12.sp, color = C.WARN)
                Text("Não é promessa de vaga, título ou permanência.", fontSize = 11.5.sp, color = C.MUTED)
            }
        }
    }
}

@Composable
internal fun RivalRadarCard(slot: Int, d: SlotData) {
    val ctx = LocalContext.current
    val v = rememberLoaded<LeagueView?>(null, UiBus.version, "radar$slot") { runCatching { loadLeagueView(Repo(ctx), slot) }.getOrNull() }
    val r = v?.radar
    Fold("🛰", "Radar do rival", r?.let { "${it.games.size} jogo(s)" }, startOpen = true) {
        if (r == null) {
            Text("Sem partidas do rival lidas. Abra os resultados da liga no OSM (jornadas anteriores) com a captura ligada: o Radar monta sozinho.", fontSize = 13.sp, color = C.MUTED)
            return@Fold
        }
        Text("${r.rival}: últimas ${r.games.size} partida(s) confirmadas nesta competição", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        for (g in r.games) Text(
            "J${g.round} • ${if (g.home) "casa" else "fora"} • ${g.opponent}${g.oppRank?.let { " (${it}º)" } ?: ""} • ${g.gf}×${g.ga} " + (if (g.gf > g.ga) "V" else if (g.gf < g.ga) "D" else "E"),
            fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp)
        )
        val gf = r.games.sumOf { it.gf }; val ga = r.games.sumOf { it.ga }
        Text("Marcou $gf • sofreu $ga em ${r.games.size} jogo(s)", fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
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
        Text("$me ${if (st.mine != null) ma else st.aggA} × ${if (st.mine != null) oa else st.aggB} $opp", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
        Text("Placar oficial acumulado${st.aggRound?.let { " (J$it)" } ?: ""} • lido ${fmtAt(st.aggAt)}. As linhas não são somadas de novo.", fontSize = 12.sp, color = C.MUTED)
        if (st.contribA != null) {
            val ca = if (mineA) st.contribA else st.contribB; val cb = if (mineA) st.contribB else st.contribA
            Text("Rodada: ${if (st.mine != null) ca else st.contribA} × ${if (st.mine != null) cb else st.contribB} pts (${st.contribSource})", fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
        st.myRow?.let { Text("Meu jogo: ${it.name} • ${it.games} jogo(s) • ${it.pts} pts", fontSize = 13.sp) }
        Text("Jogos pendentes: não lidos • sem regra de pontuação lida, não calculo \"empate basta\" nem \"precisa vencer\".", fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
    }
}
