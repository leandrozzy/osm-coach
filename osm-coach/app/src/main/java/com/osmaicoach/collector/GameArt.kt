package com.osmaicoach.collector

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource

/**
 * Desenhos do próprio jogo (recortes dos prints enviados pelo usuário, uso pessoal): estilo de jogo, desarme, marcação, fora-de-jogo e árbitro
 * (rosto + termômetro nas cores do jogo). Setores continuam desenhados pelo app (o jogo tem uma figura por combinação).
 */
private fun res(name: String): Int = when (name) {
    "estilo_passe" -> R.drawable.g_estilo_passe
    "estilo_remate" -> R.drawable.g_estilo_remate
    "estilo_bola" -> R.drawable.g_estilo_bola
    "estilo_contra" -> R.drawable.g_estilo_contra
    "estilo_alas" -> R.drawable.g_estilo_alas
    "desarme_cuidadoso" -> R.drawable.g_desarme_cuidadoso
    "desarme_normal" -> R.drawable.g_desarme_normal
    "desarme_agressivo" -> R.drawable.g_desarme_agressivo
    "desarme_extremo" -> R.drawable.g_desarme_extremo
    "marc_zona" -> R.drawable.g_marc_zona
    "marc_homem" -> R.drawable.g_marc_homem
    "fora_nao" -> R.drawable.g_fora_nao
    "fora_sim" -> R.drawable.g_fora_sim
    "arbitro_0" -> R.drawable.g_arbitro_0
    "arbitro_1" -> R.drawable.g_arbitro_1
    "arbitro_2" -> R.drawable.g_arbitro_2
    "arbitro_3" -> R.drawable.g_arbitro_3
    else -> R.drawable.g_arbitro_4
}

@Composable
private fun GameImg(name: String, modifier: Modifier) {
    Image(painterResource(res(name)), contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit, alignment = Alignment.Center)
}

@Composable
fun StyleIllustration(style: String?, modifier: Modifier = Modifier) {
    val n = when (Osm.style(style) ?: style) {
        "Jogar pelas alas" -> "estilo_alas"; "Contra-ataque" -> "estilo_contra"; "Remate à vista" -> "estilo_remate"; "Bola longa" -> "estilo_bola"; else -> "estilo_passe"
    }
    GameImg(n, modifier)
}

@Composable
fun TackleIllustration(tackle: String?, modifier: Modifier = Modifier) {
    val n = when (Osm.tackle(tackle) ?: tackle) { "Cuidadoso" -> "desarme_cuidadoso"; "Agressivo" -> "desarme_agressivo"; "Extremo" -> "desarme_extremo"; else -> "desarme_normal" }
    GameImg(n, modifier)
}

@Composable
fun MarkingIllustration(marking: String?, modifier: Modifier = Modifier) {
    GameImg(if (Osm.marking(marking) == "Homem-a-homem") "marc_homem" else "marc_zona", modifier)
}

@Composable
fun OffsideIllustration(offside: String?, modifier: Modifier = Modifier) {
    GameImg(if (offside == "Sim") "fora_sim" else "fora_nao", modifier)
}

/** Árbitro (0 muito brando … 4 muito rigoroso): rosto + termômetro do jogo (verde, azul, amarelo, laranja, vermelho). */
@Composable
fun RefereeFace(level: Int?, modifier: Modifier = Modifier) {
    GameImg("arbitro_" + (level ?: 2).coerceIn(0, 4), modifier)
}
