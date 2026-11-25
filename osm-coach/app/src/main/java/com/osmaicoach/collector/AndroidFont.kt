package com.osmaicoach.collector

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * Fontes do redesign (OFL, embutidas em res/font): Barlow no texto corrido e Barlow Semi Condensed pesada (800/900) nos títulos e números
 * grandes, como no protótipo aprovado. Peso até 700 = Barlow; ExtraBold em diante = Barlow Semi Condensed ExtraBold.
 */
fun osmFontFamily(): FontFamily = try {
    FontFamily(
        Font(R.font.barlow_regular, FontWeight.Normal),
        Font(R.font.barlow_regular, FontWeight.Light),
        Font(R.font.barlow_medium, FontWeight.Medium),
        Font(R.font.barlow_medium, FontWeight.SemiBold),
        Font(R.font.barlow_bold, FontWeight.Bold),
        Font(R.font.barlowsc_extrabold, FontWeight.ExtraBold),
        Font(R.font.barlowsc_extrabold, FontWeight.Black)
    )
} catch (e: Throwable) {
    FontFamily.SansSerif
}
