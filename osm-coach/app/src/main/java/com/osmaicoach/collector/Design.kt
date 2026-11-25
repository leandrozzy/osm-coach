package com.osmaicoach.collector

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Design system (redesign 25/11). Só APARÊNCIA: nenhuma regra, dado ou fluxo muda. Tokens usados por `C` (cores), `osmPanel`, botões, abas, navegação e cabeçalho.
 * Paleta: fundo #0B111B, superfície #151F2D, elevada #1C2938, borda #2C3B4D, texto #F4F7FB, secundário #A8B7C8; verde-lima = ação primária; ciano = análise;
 * amarelo = atenção; vermelho = erro. As cinco cores dos árbitros (verde, azul, amarelo, laranja, vermelho) NÃO mudam (ficam em Referee/GameArt).
 */
object DS {
    val BG = Color(0xFF0B111B)
    val SURFACE = Color(0xFF151F2D)
    val ELEVATED = Color(0xFF1C2938)
    val BORDER = Color(0xFF2C3B4D)
    val TEXT = Color(0xFFF4F7FB)
    val TEXT2 = Color(0xFFA8B7C8)
    val LIME = Color(0xFFB9F363)
    val CYAN = Color(0xFF39BDF8)
    val AMBER = Color(0xFFF5B942)
    val RED = Color(0xFFEF5350)
    val GREEN = Color(0xFF4CCB7A)
    val ON_LIME = Color(0xFF0B111B)

    val RADIUS_CARD = 16.dp
    val RADIUS_CONTROL = 14.dp
    val TOUCH = 48.dp          // alvo de toque mínimo
    val GAP = 8.dp

    /** Luminância relativa (WCAG 2.x) e razão de contraste, para testar a legibilidade da paleta. */
    fun luminance(c: Color): Double {
        fun ch(v: Float): Double { val d = v.toDouble(); return if (d <= 0.03928) d / 12.92 else Math.pow((d + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }
    fun contrast(a: Color, b: Color): Double {
        val la = luminance(a); val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
