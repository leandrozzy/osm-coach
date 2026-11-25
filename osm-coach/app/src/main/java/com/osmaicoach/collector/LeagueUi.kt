package com.osmaicoach.collector

import androidx.compose.foundation.layout.Column
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
    val stAt: Long?, val clubs: Int, val radar: LeagueResults.Radar?, val partial: Boolean = false
)

internal suspend fun loadLeagueView(repo: Repo, slot: Int): LeagueView {
    val f = repo.fieldMap(slot)
    val lg = repo.dao.plan(slot, "league")?.json?.let { runCatching { JSONObject(it) }.getOrNull() }
    val lres = repo.dao.plan(slot, "lres")?.json?.let { runCatching { JSONObject(it) }.getOrNull() }
    val stAll = League.dedupe(League.table(lg, "st"))
    val stAtL = lg?.optLong("stAt")?.takeIf { it > 0 }
    // linhas lidas em passadas diferentes (horas de distância) = tabela misturada: não projeta em cima disso
    val partial = stAtL != null && stAll.any { (it.at ?: 0L) < stAtL - 180_000L }
    val st = if (partial) stAll.filter { (it.at ?: 0L) >= stAtL!! - 180_000L }.let { fresh -> if (fresh.size >= 6) fresh else stAll } else stAll
    val comp = LeagueResults.compKey(f)
    val games = if (comp != null) LeagueResults.games(lres, comp) else emptyList()
    val me = known(f, K.TEAM) ?: known(f, K.HUB_TITLE)
    val played = known(f, K.ROUND_DONE)?.filter { it.isDigit() }?.toIntOrNull()
    val cov = comp?.let { LeagueResults.coverage(lres, it, st.size.takeIf { n -> n >= 4 }, played) }
    val adj = if (st.isNotEmpty()) LeagueResults.adjust(st, games) else null
    val mv = LeagueResults.movement(lg?.optJSONArray("snaps"), me, games)
    val total = known(f, K.ROUND_TOTAL)?.filter { it.isDigit() }?.toIntOrNull()
    val hasNext = known(f, K.MATCH_AT)?.toLongOrNull()?.let { it > System.currentTimeMillis() } == true && known(f, K.RIVAL_TEAM) != null
    val proj = if (partial && st.size < stAll.size) null else adj?.let { LeagueResults.project(it, me, total, hasNext) }
    val radar = LeagueResults.radar(games, known(f, K.RIVAL_TEAM), st, lg?.optLong("stAt")?.takeIf { it > 0 })
    return LeagueView(cov, adj, mv, proj, stAtL, stAll.size, radar, partial)
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun LeagueResultsCard(slot: Int) {
    val ctx = LocalContext.current
    val v = rememberLoaded<LeagueView?>(null, UiBus.version, "lv$slot") { runCatching { loadLeagueView(Repo(ctx), slot) }.getOrNull() } ?: return
    if (v.cov == null && v.stAt == null) return
    val c = v.cov
    Fold("📊", "Sua posição na liga", c?.let { "${it.games} jogos lidos" }, startOpen = true) {
        Text("O app lê sozinho os resultados de TODOS os clubes e a classificação quando você passa por essas telas no OSM (com a captura ligada) e usa isso para dizer onde você está e o que muda no próximo jogo.", fontSize = 12.5.sp, color = C.MUTED)

        // 1) O que o app já viu
        Text("1. O que o app já leu", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = C.GOLD, modifier = Modifier.padding(top = 10.dp))
        if (c == null) Text("Nenhum resultado dos outros clubes ainda. No OSM: Competição → Resultados e role a lista de cada rodada.", fontSize = 13.sp)
        else {
            Text("${c.games} jogos de ${c.rounds} rodada(s)" + (if (c.clubs >= 4) ", ${c.clubs} clubes" else "") + ". Última leitura: ${fmtAt(c.lastAt)}.", fontSize = 13.sp)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                for ((r, n) in c.perRound) {
                    val full = c.expectedPerRound != null && n == c.expectedPerRound
                    val bad = c.expectedPerRound != null && n < c.expectedPerRound
                    Pill("J$r " + (if (full) "✓" else if (bad) "$n/${c.expectedPerRound}" else "$n"), full)
                }
                for (r in c.missing) Pill("J$r sem leitura", false)
            }
            if (c.missing.isNotEmpty() || c.partial.isNotEmpty()) Text(
                "⚠ Para completar: abra os Resultados no OSM e role até o fim da rodada " + (c.missing + c.partial).distinct().sorted().joinToString(", ") { "J$it" } + ". Cada rodada só precisa ser aberta uma vez.",
                fontSize = 12.5.sp, color = C.WARN, modifier = Modifier.padding(top = 6.dp)
            )
            if (c.over.isNotEmpty()) Text("ℹ " + c.over.joinToString(", ") { "J$it" } + " tem mais jogos que o normal: o nome de algum clube foi lido de formas diferentes. Não afeta sua posição.", fontSize = 11.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
        }

        // 2) Classificação
        Text("2. Classificação", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = C.GOLD, modifier = Modifier.padding(top = 12.dp))
        if (v.stAt == null) Text("Ainda não li a tabela. No OSM: Competição → Classificação, e role a tabela inteira de uma vez. Sem ela eu não sei seus pontos nem sua posição, então não consigo projetar.", fontSize = 13.sp, color = C.WARN)
        else {
            Text("Lida em ${fmtAt(v.stAt)} (${v.clubs} clubes).", fontSize = 13.sp)
            if (v.partial) Text("⚠ A tabela guardada mistura leituras de horas diferentes. Abra a Classificação e role tudo de uma vez para eu recalcular.", fontSize = 12.5.sp, color = C.WARN)
            v.adj?.let { a ->
                val applied = a.rows.filter { it.applied.isNotEmpty() }
                if (applied.isNotEmpty()) Text("Somei os resultados lidos que a tabela ainda não tinha (${applied.size} clubes), uma vez só.", fontSize = 12.sp, color = C.MUTED)
            }
        }

        // 3) O que mudou
        v.movement?.let { m ->
            Text("3. O que mudou desde a leitura anterior", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = C.GOLD, modifier = Modifier.padding(top = 12.dp))
            Text(if (m.rankTo < m.rankFrom) "⬆ Você subiu: ${m.rankFrom}º → ${m.rankTo}º" else if (m.rankTo > m.rankFrom) "⬇ Você desceu: ${m.rankFrom}º → ${m.rankTo}º" else "➡ Você continua em ${m.rankTo}º", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (m.rankTo < m.rankFrom) C.OK else if (m.rankTo > m.rankFrom) C.WARN else androidx.compose.ui.graphics.Color.White)
            if (m.passed.isNotEmpty()) Text("Passou: ${m.passed.joinToString()}", fontSize = 13.sp, color = C.OK)
            if (m.closed.isNotEmpty()) Text("Chegaram perto (até 3 pontos atrás): ${m.closed.joinToString()}", fontSize = 13.sp, color = C.WARN)
            if (m.affecting.isNotEmpty()) Text("Jogos que causaram isso: ${m.affecting.take(6).joinToString(" • ")}", fontSize = 12.sp, color = C.MUTED)
        }

        // 4) Projeção em linguagem simples
        v.projection?.let { p ->
            Text("4. O que pode acontecer", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = C.GOLD, modifier = Modifier.padding(top = 12.dp))
            if (p.ifWin != null) {
                Text("No próximo jogo:", fontSize = 13.sp)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((lab, pos, col) in listOf(Triple("Vitória", p.ifWin, C.OK), Triple("Empate", p.ifDraw, C.GOLD), Triple("Derrota", p.ifLoss, C.WARN))) Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(C.SURFACE2).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally
                    ) { Text("${pos}º", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = col); Text(lab, fontSize = 11.5.sp, color = C.MUTED) }
                }
                Text("Posição que você teria na tabela de hoje (os outros jogos da rodada não entram).", fontSize = 11.5.sp, color = C.MUTED)
            }
            if (p.remaining >= 0) {
                Text("No fim do campeonato (faltam ${p.remaining} jogos):", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                Text("• Certeza matemática: você termina entre ${p.confirmedBest}º e ${p.confirmedWorst}º.", fontSize = 13.sp)
                p.estimateRank?.let { Text("• Palpite pelo seu ritmo atual: por volta do ${it}º (≈ ${p.estimatePts} pontos)." + (if (p.tieNote) " Pode empatar em pontos com outro clube; o desempate eu não sei." else ""), fontSize = 13.sp) }
            }
            for (l in p.limits) Text("⚠ $l", fontSize = 12.sp, color = C.WARN, modifier = Modifier.padding(top = 2.dp))
            Text("É uma conta, não uma promessa de vaga, título ou permanência.", fontSize = 11.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
        }
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
