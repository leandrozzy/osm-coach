package com.osmaicoach.collector

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tabela vertical da aba Tática: cada campo com o valor SUGERIDO, o valor JOGADO (confirmado) e a origem/hora.
 * Campo sem leitura aparece como "não confirmado" e pode ser confirmado à mão. Sem "lados" nem "bolas paradas".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlayedTable(slot: Int, plan: JSONObject) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tick = rememberTick(4000L)
    val played = rememberLoaded<JSONObject?>(null, tick, UiBus.version) { PlayedTactic.current(Repo(ctx), slot) }
    var editing by remember { mutableStateOf<String?>(null) }
    val verdict = PlayedTactic.classify(plan, played)
    val hour = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
    val (chip, col) = when (verdict.usage) {
        PlayedTactic.SEGUIU -> "✅ Seguiu a sugestão" to C.OK
        PlayedTactic.ALTEROU -> "↔ Alterou a sugestão" to C.WARN
        else -> "❔ Jogada não confirmada" to C.MUTED
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).osmPanel().padding(12.dp)) {
        Text("Sugerida × jogada", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(chip, color = col, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
        Text(verdict.text(), fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(bottom = 6.dp))
        val sug = PlayedTactic.suggestedFields(plan)
        for (f in PlayedTactic.FIELDS) {
            val sv = sug[f]
            val cell = PlayedTactic.cell(played, f)
            val pv = cell?.optString("v")?.takeIf { it.isNotBlank() }
            val diff = sv != null && pv != null && !PlayedTactic.same(f, sv, pv)
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(PlayedTactic.LABEL[f] ?: f, fontSize = 12.5.sp, color = C.MUTED, modifier = Modifier.width(92.dp))
                Column(Modifier.weight(1f)) {
                    Text("Sugerido: " + (sv ?: "—"), fontSize = 13.sp)
                    Text(
                        "Jogado: " + (pv ?: "não confirmado"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = if (pv == null) C.MUTED else if (diff) C.WARN else C.OK
                    )
                    if (cell != null) Text("origem: ${cell.optString("src")} • ${hour.format(Date(cell.optLong("at")))}", fontSize = 11.sp, color = C.MUTED)
                }
                TextButton(onClick = { editing = f }) { Text(if (pv == null) "Confirmar" else "Corrigir", fontSize = 12.sp) }
            }
        }
        Text("Só entra o que foi lido da tela de Tática do OSM, do Resultado ou confirmado por você. O que não foi lido fica desconhecido: o resultado do jogo não é atribuído à sugerida.", fontSize = 11.5.sp, color = C.MUTED, modifier = Modifier.padding(top = 6.dp))
    }
    val f = editing
    if (f != null) {
        var text by remember(f) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { editing = null }, containerColor = C.SURFACE,
            title = { Text("Confirmar: ${PlayedTactic.LABEL[f]}", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("O que você realmente montou no OSM para este jogo?", fontSize = 13.sp)
                    if (f in PlayedTactic.SLIDERS) {
                        OutlinedTextField(value = text, onValueChange = { text = it.filter { c -> c.isDigit() }.take(3) }, singleLine = true, label = { Text("0 a 100") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                            for (o in PlayedTactic.options(f).take(40)) OutlinedButton(onClick = {
                                scope.launch { withContext(Dispatchers.IO) { PlayedTactic.record(Repo(ctx), slot, mapOf(f to o), "manual") } }
                                editing = null
                            }) { Text(o, fontSize = 12.sp) }
                        }
                    }
                }
            },
            confirmButton = {
                if (f in PlayedTactic.SLIDERS) TextButton(onClick = {
                    val v = text.toIntOrNull()
                    if (v != null && v in 0..100) scope.launch { withContext(Dispatchers.IO) { PlayedTactic.record(Repo(ctx), slot, mapOf(f to v.toString()), "manual") } }
                    editing = null
                }) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancelar") } }
        )
    }
}
