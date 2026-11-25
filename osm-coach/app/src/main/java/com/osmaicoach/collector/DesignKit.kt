package com.osmaicoach.collector

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar
import java.util.Locale

/**
 * Peças do redesign (só aparência). Uma única linguagem de forma: cantos arredondados (nada de cortes retos), cartões planos
 * com borda fina, lima = ação, ciano = análise, âmbar = atenção, vermelho = erro. Os dados vêm dos mesmos lugares de antes.
 */

/** Cabeçalho de página: faixa arredondada com brilho lima/ciano, ícone em ladrilho, título grande e subtítulo. */
@Composable
internal fun HeroHeader(icon: String, title: String, subtitle: String? = null, chip: String? = null, tint: Color = C.INFO) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 12.dp).clip(shape)
            .background(Brush.linearGradient(listOf(tint.copy(alpha = 0.20f), DS.SURFACE, DS.SURFACE)))
            .border(1.dp, tint.copy(alpha = 0.45f), shape).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(tint.copy(alpha = 0.18f)).border(1.dp, tint.copy(alpha = 0.5f), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            Text(icon, fontSize = 24.sp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = C.TEXT, maxLines = 1)
            if (subtitle != null) Text(subtitle, fontSize = 13.5.sp, color = C.MUTED, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (chip != null) Box(Modifier.clip(RoundedCornerShape(50)).background(DS.ELEVATED).border(1.dp, C.BORDER, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(chip, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = C.TEXT)
        }
    }
}

/** Chip arredondado (altura confortável, texto legível). */
@Composable
internal fun DsChip(text: String, color: Color = C.MUTED, filled: Boolean = false, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier.clip(shape).background(if (filled) color else color.copy(alpha = 0.14f)).border(1.dp, color.copy(alpha = if (filled) 0f else 0.5f), shape)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) { Text(text, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = if (filled) DS.ON_LIME else color, maxLines = 1) }
}

/** Seletor de dia em faixa (pílulas arredondadas, o dia escolhido em lima, pontos de eventos embaixo). */
internal class DayCell(val key: String, val dow: String, val day: Int, val dots: List<Color>)

@Composable
internal fun DayStrip(days: List<DayCell>, selected: String?, onPick: (String?) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (d in days) {
            val sel = d.key == selected
            val shape = RoundedCornerShape(16.dp)
            Column(
                Modifier.weight(1f).heightIn48().clip(shape).background(if (sel) C.PRIMARY else DS.SURFACE).border(1.dp, if (sel) C.PRIMARY else C.BORDER, shape)
                    .clickable { onPick(if (sel) null else d.key) }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(d.dow, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (sel) DS.ON_LIME else C.MUTED, maxLines = 1)
                Text("${d.day}", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = if (sel) DS.ON_LIME else C.TEXT)
                Row(Modifier.height(8.dp).padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (c in d.dots.take(4)) Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(if (sel) DS.ON_LIME else c))
                }
            }
        }
    }
}

private fun Modifier.heightIn48(): Modifier = this.height(76.dp)

/** Seletor de filtro (Tudo / Jogos / Treinos / Obras), 48 dp de altura de toque. */
@Composable
internal fun DsFilters(labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, l ->
            val sel = i == selected
            val shape = RoundedCornerShape(DS.RADIUS_CONTROL)
            Box(
                Modifier.weight(1f).height(44.dp).clip(shape).background(if (sel) C.PRIMARY else DS.SURFACE).border(1.dp, if (sel) C.PRIMARY else C.BORDER, shape).clickable { onPick(i) },
                contentAlignment = Alignment.Center
            ) { Text(l, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (sel) DS.ON_LIME else C.TEXT, maxLines = 1) }
        }
    }
}

/** Linha "AGORA" da linha do tempo. */
@Composable
internal fun NowLine(hour: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("AGORA", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = C.PRIMARY, letterSpacing = 1.4.sp)
        Box(Modifier.weight(1f).padding(horizontal = 10.dp).height(2.dp).clip(RoundedCornerShape(50)).background(C.PRIMARY.copy(alpha = 0.6f)))
        Text(hour, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = C.PRIMARY)
    }
}

/** Cabeçalho de seção: barra lima arredondada + título. */
@Composable
internal fun DsSection(title: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(5.dp).height(22.dp).clip(RoundedCornerShape(50)).background(C.PRIMARY))
        Spacer(Modifier.width(10.dp))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = C.TEXT, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (trailing != null) DsChip(trailing)
    }
}

/** Data "dd/MM[/aaaa]" do card do OSM → (dia, mês 0..11, ano ou null). */
internal fun parseDm(s: String?): Triple<Int, Int, Int?>? {
    val m = Regex("(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?").find(s ?: return null) ?: return null
    val d = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt() - 1
    if (d !in 1..31 || mo !in 0..11) return null
    val y = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < 100) 2000 + it else it }
    return Triple(d, mo, y)
}

/**
 * Calendário do mês (grade de 7 colunas, células arredondadas, hoje com contorno lima, pontos coloridos por jogo).
 * `dots`: dia do mês → cores. Só desenha; não decide nada.
 */
@Composable
internal fun MonthGrid(year: Int, month: Int, dots: Map<Int, List<Color>>, today: Int?) {
    val cal = Calendar.getInstance().apply { clear(); set(year, month, 1) }
    val lead = cal.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY
    val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val cells = ArrayList<Int?>()
    repeat(lead) { cells.add(null) }
    for (d in 1..days) cells.add(d)
    while (cells.size % 7 != 0) cells.add(null)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            for (h in listOf("D", "S", "T", "Q", "Q", "S", "S")) Text(h, Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = C.MUTED)
        }
        for (week in cells.chunked(7)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (d in week) {
                    val shape = RoundedCornerShape(12.dp)
                    if (d == null) { Spacer(Modifier.weight(1f).aspectRatio(1f)); continue }
                    val isToday = d == today
                    Column(
                        Modifier.weight(1f).aspectRatio(1f).clip(shape).background(DS.ELEVATED).border(if (isToday) 2.dp else 1.dp, if (isToday) C.PRIMARY else Color.Transparent, shape),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                    ) {
                        Text("$d", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = if (isToday) C.PRIMARY else C.TEXT)
                        Row(Modifier.height(8.dp).padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            for (c in (dots[d] ?: emptyList()).take(3)) Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(c))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DsLegend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(color))
        Text(" $label", fontSize = 12.5.sp, color = C.MUTED)
    }
}

/** Uma partida em linha: escudo + nome de cada lado, placar (ou hora) no meio e selo V/E/D. */
@Composable
internal fun MatchLine(slot: Int, myTeam: String?, left: String, right: String, center: String, badge: @Composable () -> Unit, lead: String, mineLeft: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(lead, Modifier.width(36.dp), fontSize = 12.5.sp, fontWeight = FontWeight.ExtraBold, color = C.MUTED, textAlign = TextAlign.Center, maxLines = 1)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            if (mineLeft) Crest(slot, 30.dp, myTeam) else RivalShield(left, 30.dp)
            Text(left, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
        Column(Modifier.width(74.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(center, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            badge()
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            if (!mineLeft) Crest(slot, 30.dp, myTeam) else RivalShield(right, 30.dp)
            Text(right, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

/** Mesmo formato para dois clubes quaisquer (copa): os dois com escudo da web/recorte pelo nome. */
@Composable
internal fun MatchLineNames(left: String, right: String, center: String, lead: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(lead, Modifier.width(36.dp), fontSize = 12.5.sp, fontWeight = FontWeight.ExtraBold, color = C.MUTED, textAlign = TextAlign.Center, maxLines = 1)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            RivalShield(left, 28.dp)
            Text(left, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(center, Modifier.width(64.dp), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, maxLines = 1)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            RivalShield(right, 28.dp)
            Text(right, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

internal fun monthName(m: Int): String = listOf("janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto", "setembro", "outubro", "novembro", "dezembro")[m.coerceIn(0, 11)]

internal fun ptDow(c: Calendar): String = listOf("dom", "seg", "ter", "qua", "qui", "sex", "sáb")[c.get(Calendar.DAY_OF_WEEK) - 1]

@Suppress("unused") private val keepLocale = Locale("pt", "BR")
