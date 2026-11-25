package com.osmaicoach.collector

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Mapa-múndi interativo (clicável por país). Geometria: Natural Earth 1:110m (domínio público) via pacote `world-atlas` 2.0.2 (licença ISC), simplificada para ~133 KB em `assets/world_map.json`.
 * Limitação: a escala 1:110m não desenha microestados/ilhas pequenas (Andorra, Malta, Maldivas…); esses países continuam acessíveis pela lista da Cobertura.
 */
object WorldMapData {
    const val LAT_TOP = 84f; const val LAT_BOTTOM = -58f
    class Shape(val iso: String?, val name: String, val rings: List<FloatArray>)   // cada anel: x0,y0,x1,y1… (graus)
    @Volatile private var cache: List<Shape>? = null
    fun load(ctx: Context): List<Shape> = cache ?: synchronized(this) {
        cache ?: run {
            val arr = JSONArray(ctx.assets.open("world_map.json").bufferedReader().use { it.readText() })
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i); val rs = o.getJSONArray("rings")
                Shape(o.optString("iso").takeIf { it.isNotBlank() && it != "null" }, o.getString("name"), (0 until rs.length()).map { j -> val r = rs.getJSONArray(j); FloatArray(r.length() * 2) { k -> r.getJSONArray(k / 2).getDouble(k % 2).toFloat() } })
            }
        }.also { cache = it }
    }
    /** Ponto dentro do anel (ray casting). */
    fun inside(r: FloatArray, x: Float, y: Float): Boolean {
        var c = false; var j = r.size / 2 - 1
        for (i in 0 until r.size / 2) {
            val xi = r[i * 2]; val yi = r[i * 2 + 1]; val xj = r[j * 2]; val yj = r[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c
            j = i
        }
        return c
    }
    fun at(shapes: List<Shape>, lon: Float, lat: Float): Shape? = shapes.firstOrNull { s -> s.rings.any { inside(it, lon, lat) } }
}

@Composable
internal fun WorldMapView(selected: String?, weight: Map<String, Long>, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    val shapes by produceState<List<WorldMapData.Shape>>(emptyList()) { value = withContext(Dispatchers.IO) { runCatching { WorldMapData.load(ctx).filter { !it.name.contains("Antarctic", true) && it.iso != "aq" } }.getOrDefault(emptyList()) } }
    val max = (weight.values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    Canvas(
        Modifier.fillMaxWidth().aspectRatio(360f / 142f).pointerInput(shapes) {
            detectTapGestures { p ->
                val lon = p.x / size.width * 360f - 180f; val lat = WorldMapData.LAT_TOP - p.y / size.height * (WorldMapData.LAT_TOP - WorldMapData.LAT_BOTTOM)
                WorldMapData.at(shapes, lon, lat)?.iso?.let(onPick)
            }
        }
    ) {
        fun px(lon: Float) = (lon + 180f) / 360f * size.width
        fun py(lat: Float) = (WorldMapData.LAT_TOP - lat) / (WorldMapData.LAT_TOP - WorldMapData.LAT_BOTTOM) * size.height
        for (s in shapes) {
            val w = s.iso?.let { weight[it] } ?: 0L
            val a = if (w <= 0L) 0f else (0.25f + 0.75f * (Math.log10(w + 1.0) / Math.log10(max + 1.0)).toFloat())
            val fill = if (w <= 0L) DS.ELEVATED else DS.LIME.copy(alpha = a)
            for (r in s.rings) {
                // anel que cruza a linha de data (Rússia, Fiji…): leva os pontos do hemisfério oeste para +360 e desenha também deslocado -360 (sem faixa horizontal)
                var wrap = false
                for (i in 1 until r.size / 2) if (kotlin.math.abs(r[i * 2] - r[(i - 1) * 2]) > 180f) { wrap = true; break }
                for (shift in if (wrap) floatArrayOf(0f, -360f) else floatArrayOf(0f)) {
                    val path = Path()
                    for (i in 0 until r.size / 2) {
                        val lon = r[i * 2].let { if (wrap && it < 0f) it + 360f else it } + shift
                        val x = px(lon); val y = py(r[i * 2 + 1]); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close()
                    drawPath(path, fill)
                    drawPath(path, if (s.iso != null && s.iso == selected) DS.CYAN else DS.BORDER, style = Stroke(if (s.iso == selected) 2.5f else 0.6f))
                }
            }
        }
    }
}
