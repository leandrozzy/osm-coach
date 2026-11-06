package com.osmaicoach.collector

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Análises de cor/ícone. Limiares calibrados em quadros reais do OSM (2448x1080):
 * barra superior do OSM, camisa laranja (treino), selo V/E/D, ícone de casa e termômetro do árbitro.
 * Todas as coordenadas são frações (0..1) da imagem.
 */
object PixelProbe {
    class Img(val w: Int, val h: Int, val px: IntArray)

    private fun hsv(c: Int, out: FloatArray) {
        val r = (c shr 16 and 0xFF) / 255f
        val g = (c shr 8 and 0xFF) / 255f
        val b = (c and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        var hue = 0f
        if (d > 0f) {
            hue = when (mx) {
                r -> ((g - b) / d) % 6f
                g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            } * 60f
            if (hue < 0f) hue += 360f
        }
        out[0] = hue
        out[1] = if (mx == 0f) 0f else d / mx
        out[2] = mx
    }

    private val ORANGE: (Float, Float, Float) -> Boolean = { h, s, v -> h in 20f..46f && s > 0.58f && v > 0.66f }
    private val LIGHT_BLUE: (Float, Float, Float) -> Boolean = { h, s, v -> h in 176f..218f && s > 0.43f && v > 0.78f }
    private val GREEN: (Float, Float, Float) -> Boolean = { h, s, v -> h in 80f..165f && s > 0.66f && v > 0.66f }
    private val RED: (Float, Float, Float) -> Boolean = { h, s, v -> (h <= 10f || h >= 344f) && s > 0.66f && v > 0.66f }
    private val HUB_BLUE: (Float, Float, Float) -> Boolean = { h, s, v -> h in 195f..235f && s > 0.40f && v > 0.50f }

    private fun frac(img: Img, x0: Float, x1: Float, y0: Float, y1: Float, pred: (Float, Float, Float) -> Boolean): Float {
        val xa = (x0.coerceIn(0f, 1f) * img.w).toInt()
        val xb = max((x1.coerceIn(0f, 1f) * img.w).toInt(), xa + 1)
        val ya = (y0.coerceIn(0f, 1f) * img.h).toInt()
        val yb = max((y1.coerceIn(0f, 1f) * img.h).toInt(), ya + 1)
        var n = 0
        var hit = 0
        val t = FloatArray(3)
        for (y in ya until min(yb, img.h)) {
            for (x in xa until min(xb, img.w)) {
                hsv(img.px[y * img.w + x], t)
                n++
                if (pred(t[0], t[1], t[2])) hit++
            }
        }
        return if (n == 0) 0f else hit.toFloat() / n.toFloat()
    }

    /** Fração de pixels verdes vivos na caixa (polegar "feito" da lista de afazeres). */
    fun greenBox(img: Img, x0: Float, x1: Float, y0: Float, y1: Float): Float = frac(img, x0, x1, y0, y1, GREEN)

    /** Fração de pixels amarelo-ouro na caixa (cartão amarelo do plantel). */
    fun goldBox(img: Img, x0: Float, x1: Float, y0: Float, y1: Float): Float = frac(img, x0, x1, y0, y1, GOLD)

    /**
     * Forma recente (até 5 bolinhas V verde / E laranja / D vermelha sob o nome do time, no Pré-jogo; frame real de 05/11:
     * bolinhas de ≈0,0167 de largura, passo ≈0,0195, centro em y≈0,51). Lê pela COR, não pelo OCR (letra solta num círculo
     * colorido o OCR perde ou erra). Devolve a corrente mais longa de círculos lado a lado (≥3), da esquerda p/ direita.
     */
    fun formDots(img: Img, left: Boolean): String? {
        // 1ª passada: cores vivas de verdade (print 06/11: bolinhas com saturação ≥0,95; o gramado ao fundo ≈0,5);
        // 2ª, mais frouxa, para quadro escurecido por janela por cima — só vale se achar mais bolinhas e sem juntar gramado.
        val a = scanDots(img, left, 0.85f, 0.45f, 0.45f)
        val b = scanDots(img, left, 0.68f, 0.40f, 0.2f)
        val ok = listOf(a, b).filter { it.length in 1..5 }
        return if (ok.size == 2 && b.length > a.length) b else ok.firstOrNull()
    }

    private fun scanDots(img: Img, left: Boolean, minSat: Float, minVal: Float, widthTol: Float): String {
        val xa = ((if (left) 0.12f else 0.54f) * img.w).toInt()
        val xb = ((if (left) 0.46f else 0.88f) * img.w).toInt().coerceAtMost(img.w)
        val t = FloatArray(3)
        val cols = CharArray(max(0, xb - xa))
        for (x in xa until xb) {
            var d = 0; var e = 0; var v = 0
            for (k in 0 until 10) {
                val y = ((0.485f + 0.05f * k / 9f) * img.h).toInt().coerceIn(0, img.h - 1)
                hsv(img.px[y * img.w + x], t)
                if (t[1] > minSat && t[2] > minVal) when {
                    t[0] <= 12f || t[0] >= 344f -> d++
                    t[0] in 20f..46f -> e++
                    t[0] in 80f..165f -> v++
                }
            }
            val best = max(d, max(e, v))
            cols[x - xa] = if (best < 2) ' ' else if (best == d) 'D' else if (best == e) 'E' else 'V'
        }
        // corridas de mesma cor → círculos (largura ≈0,0167); descarta ruído de largura/forma diferente
        class Run(val c: Char, val s: Int, val n: Int)
        val runs = ArrayList<Run>()
        var i = 0
        while (i < cols.size) {
            if (cols[i] == ' ') { i++; continue }
            var j = i
            while (j < cols.size && cols[j] == cols[i]) j++
            runs.add(Run(cols[i], i + xa, j - i))
            i = j
        }
        val minW = 0.011f * img.w
        val maxW = 0.024f * img.w
        val pitch = 0.0195f * img.w
        val maxGap = 0.008f * img.w
        var best = ""
        var cur = StringBuilder()
        var prevEnd = Int.MIN_VALUE
        fun flush() { if (cur.length > best.length) best = cur.toString(); cur = StringBuilder(); prevEnd = Int.MIN_VALUE }
        for (r in runs) {
            // bolinhas vizinhas da mesma cor se fundem numa corrida larga (V V = 2 círculos): divide pelo passo
            val k = if (r.n > maxW) Math.round((r.n + 0.0035f * img.w) / pitch) else 1
            val ok = if (r.n > maxW) k in 2..5 && abs(r.n - (k * pitch - 0.0035f * img.w)) <= widthTol * pitch else r.n >= minW
            if (!ok) { flush(); continue }
            if (prevEnd != Int.MIN_VALUE && r.s - prevEnd > maxGap) flush()
            repeat(k) { cur.append(r.c) }
            prevEnd = r.s + r.n
        }
        flush()
        return best
    }

    /**
     * Assinatura da bandeira (coluna "Nac." do Plantel/Mercado): 3×3 células, cada uma na cor dominante
     * (K preto, W branco, X cinza, R vermelho, O laranja, Y amarelo, G verde, L azul-claro, B azul, P roxo).
     * França = "BWRBWRBWR", Nigéria = "GWGGWGGWG". Null se não há bandeira (tudo escuro/cinza).
     */
    fun flagSig(img: Img, cx: Float, cy: Float, w: Float = 0.026f, h: Float = 0.040f): String? {
        val x0 = ((cx - w / 2) * img.w).toInt().coerceAtLeast(0)
        val x1 = ((cx + w / 2) * img.w).toInt().coerceAtMost(img.w)
        val y0 = ((cy - h / 2) * img.h).toInt().coerceAtLeast(0)
        val y1 = ((cy + h / 2) * img.h).toInt().coerceAtMost(img.h)
        if (x1 - x0 < 9 || y1 - y0 < 9) return null
        val t = FloatArray(3)
        val sb = StringBuilder()
        for (r in 0 until 3) for (c in 0 until 3) {
            val xa = x0 + (x1 - x0) * c / 3; val xb = x0 + (x1 - x0) * (c + 1) / 3
            val ya = y0 + (y1 - y0) * r / 3; val yb = y0 + (y1 - y0) * (r + 1) / 3
            val cnt = IntArray(10)
            for (y in ya until yb) for (x in xa until xb) {
                hsv(img.px[y * img.w + x], t)
                val k = when {
                    t[2] < 0.28f -> 0
                    t[1] < 0.22f -> if (t[2] > 0.72f) 1 else 2
                    t[0] < 15f || t[0] >= 340f -> 3
                    t[0] < 40f -> 4
                    t[0] < 70f -> 5
                    t[0] < 170f -> 6
                    t[0] < 215f -> 7
                    t[0] < 262f -> 8
                    else -> 9
                }
                cnt[k]++
            }
            var b = 0
            for (i in 1 until 10) if (cnt[i] > cnt[b]) b = i
            sb.append("KWXROYGLBP"[b])
        }
        val s = sb.toString()
        return if (s.all { it == 'K' } || s.all { it == 'X' } || s.count { it == 'K' || it == 'X' } >= 8) null else s
    }

    /**
     * Ícone de um evento da tela de Resultado (vídeo/prints de 05/11): cruz vermelha = lesão, retângulo amarelo/vermelho = cartão,
     * setas verde+vermelha = substituição, bola = gol (sem cor viva). Distingue cruz de cartão pelo preenchimento da caixa
     * que envolve o vermelho (cruz ≈ 0,56; cartão ≈ 0,8+). null = nada reconhecível.
     */
    fun eventIcon(img: Img, left: Boolean, yc: Float): String? {
        val x0 = if (left) 0.004f else 0.955f
        val x1 = x0 + 0.045f
        val y0 = yc - 0.032f
        val y1 = yc + 0.032f
        val g = frac(img, x0, x1, y0, y1, GREEN)
        val r = frac(img, x0, x1, y0, y1, RED)
        val y = frac(img, x0, x1, y0, y1, GOLD)
        if (g >= 0.03f && r >= 0.02f) return "troca"
        if (y >= 0.10f && r < 0.03f) return "amarelo"
        if (r < 0.08f) return null
        // preenchimento da caixa do vermelho
        val xa = (x0 * img.w).toInt(); val xb = (x1 * img.w).toInt().coerceAtMost(img.w)
        val ya = (y0.coerceAtLeast(0f) * img.h).toInt(); val yb = (y1 * img.h).toInt().coerceAtMost(img.h)
        var minX = Int.MAX_VALUE; var maxX = -1; var minY = Int.MAX_VALUE; var maxY = -1; var n = 0
        val t = FloatArray(3)
        for (yy in ya until yb) for (xx in xa until xb) {
            hsv(img.px[yy * img.w + xx], t)
            if (RED(t[0], t[1], t[2])) { n++; if (xx < minX) minX = xx; if (xx > maxX) maxX = xx; if (yy < minY) minY = yy; if (yy > maxY) maxY = yy }
        }
        if (n == 0) return null
        val box = (maxX - minX + 1) * (maxY - minY + 1)
        return if (n.toFloat() / box < 0.68f) "lesao" else "vermelho"
    }

    /** Fração de pixels vermelhos na caixa (cartão vermelho do plantel). */
    fun redBox(img: Img, x0: Float, x1: Float, y0: Float, y1: Float): Float = frac(img, x0, x1, y0, y1, RED)

    /** Barra superior do OSM: pílula azul-clara de moedas + pílula laranja de gemas. Anúncios não têm. */
    fun isOsmTopBar(img: Img): Boolean =
        frac(img, 0.20f, 0.28f, 0.01f, 0.08f, ORANGE) >= 0.08f &&
            frac(img, 0.12f, 0.22f, 0.01f, 0.08f, LIGHT_BLUE) >= 0.10f

    // Disco cinza-azulado translúcido do cadeado (o círculo normal da força é azul-marinho escuro).
    private val LOCK_DISC: (Float, Float, Float) -> Boolean = { h, s, v -> h in 198f..234f && s in 0.22f..0.49f && v in 0.46f..0.72f }

    /**
     * Pré-jogo: o rival fez TREINO SECRETO quando o círculo dele mostra um cadeado no lugar da força/bônus.
     * Procura o disco ao redor da posição do círculo (esquerda x≈0,34 ou direita x≈0,66, y≈0,26).
     */
    fun lockedCircle(img: Img, left: Boolean): Boolean {
        var best = 0f
        var cx = 0.29f
        while (cx <= 0.391f) {
            val x = if (left) cx else 1f - cx
            var cy = 0.22f
            while (cy <= 0.301f) {
                best = max(best, frac(img, x - 0.015f, x + 0.015f, cy - 0.035f, cy + 0.035f, LOCK_DISC))
                cy += 0.01f
            }
            cx += 0.006f
        }
        return best >= 0.30f
    }

    /** A central dos 4 slots não tem barra superior; ela é azul. Serve só para decidir se vale rodar OCR. */
    fun hubBackdrop(img: Img): Boolean = frac(img, 0f, 1f, 0f, 1f, HUB_BLUE) >= 0.25f

    private val GOLD: (Float, Float, Float) -> Boolean = { h, s, v -> h in 36f..58f && s > 0.55f && v > 0.75f }

    /** Estrelas preenchidas (douradas) de um card do estádio: 3 estrelas centradas em cx, espaçadas 0,0286. */
    fun starLevel(img: Img, cx: Float, cy: Float): Int {
        var n = 0
        for (i in -1..1) {
            val x = cx + i * 0.0286f
            if (frac(img, x - 0.008f, x + 0.008f, cy - 0.016f, cy + 0.016f, GOLD) >= 0.25f) n++
        }
        return n
    }

    /** Preenchimento (0 a 100) de uma barra colorida horizontal (condição/moral) na linha y. */
    fun barFill(img: Img, x0: Float, x1: Float, y: Float): Int {
        val xa = (x0 * img.w).toInt().coerceIn(0, img.w - 1)
        val xb = (x1 * img.w).toInt().coerceIn(xa + 1, img.w)
        val ya = (y * img.h).toInt().coerceIn(0, img.h - 1)
        var n = 0
        var hit = 0
        val t = FloatArray(3)
        for (x in xa until xb) {
            n++
            hsv(img.px[ya * img.w + x], t)
            if (t[1] > 0.45f && t[2] > 0.5f) hit++
        }
        return if (n == 0) 0 else hit * 100 / n
    }

    /** Camisa laranja na linha do jogador (treino). */
    fun orangeShirt(img: Img, rowY: Float): Boolean =
        frac(img, 0.005f, 0.055f, rowY - 0.04f, rowY + 0.04f, ORANGE) >= 0.04f

    /** Selo V/E/D do card do calendário, à direita do rótulo "Jornada N". */
    fun resultBadge(img: Img, labelX: Float, labelY: Float): Char? {
        val x0 = labelX + 0.03f
        val x1 = labelX + 0.115f
        val y0 = labelY - 0.035f
        val y1 = labelY + 0.035f
        val g = frac(img, x0, x1, y0, y1, GREEN)
        val o = frac(img, x0, x1, y0, y1, ORANGE)
        val r = frac(img, x0, x1, y0, y1, RED)
        val best = max(g, max(o, r))
        if (best < 0.03f) return null
        return when (best) {
            g -> 'V'
            o -> 'E'
            else -> 'D'
        }
    }

    /** Ícone de casinha à esquerda do rótulo: true=casa, false=fora, null=incerto. */
    fun homeIcon(img: Img, labelX: Float, labelY: Float): Boolean? {
        val x0 = max(0f, labelX - 0.095f)
        val x1 = labelX - 0.04f
        if (x1 <= x0) return null
        val white = frac(img, x0, x1, labelY - 0.035f, labelY + 0.035f) { _, s, v -> s < 0.12f && v > 0.80f }
        return when {
            white >= 0.02f -> true
            white <= 0.008f -> false
            else -> null
        }
    }

    /**
     * Termômetro do árbitro no pré-jogo (logo à esquerda do texto "Árbitro"), 5 níveis pela cor do bulbo:
     * AZUL muito brando, VERDE brando, AMARELO normal, LARANJA rigoroso, VERMELHO muito rigoroso.
     * Só responde com uma cor dominante e pixels suficientes; senão null (não chuta).
     */
    fun refereeSeverity(img: Img, labelLeft: Float? = null, labelYc: Float? = null): String? {
        val x0 = if (labelLeft != null) labelLeft - 0.02f else 0.490f
        val x1 = if (labelLeft != null) labelLeft - 0.002f else 0.508f
        val y0 = if (labelYc != null) labelYc - 0.06f else 0.430f
        val y1 = if (labelYc != null) labelYc + 0.035f else 0.505f
        val xa = (x0.coerceIn(0f, 1f) * img.w).toInt()
        val xb = min(max((x1.coerceIn(0f, 1f) * img.w).toInt(), xa + 1), img.w)
        val ya = (y0.coerceIn(0f, 1f) * img.h).toInt()
        val yb = min(max((y1.coerceIn(0f, 1f) * img.h).toInt(), ya + 1), img.h)
        val t = FloatArray(3)
        // 0 vermelho, 1 laranja, 2 amarelo, 3 verde, 4 azul-claro do bulbo; -1 = não é cor de bulbo
        fun kind(c: Int): Int {
            hsv(c, t)
            if (t[1] < 0.5f || t[2] < 0.55f) return -1
            val h = t[0]
            return when {
                h < 15f || h >= 340f -> 0
                h < 40f -> 1
                h < 72f -> 2
                h in 80f..165f -> 3
                // bulbo "muito brando": azul-claro vivo. O fundo azul da batalha (mais escuro) não conta.
                h in 180f..214f && t[1] >= 0.6f && t[2] >= 0.88f -> 4
                else -> -1
            }
        }
        // O bulbo é a coluna com mais cor de bulbo; o rosto do árbitro (pele/camisa) fica à esquerda e não entra.
        var best = -1
        var bestN = 0
        for (x in xa until xb) {
            var n = 0
            for (y in ya until yb) if (kind(img.px[y * img.w + x]) >= 0) n++
            if (n > bestN) { bestN = n; best = x }
        }
        if (best < 0 || bestN < 3) return null
        val half = max(2, (0.004f * img.w).toInt())
        val cnt = IntArray(5)
        for (x in max(xa, best - half)..min(xb - 1, best + half)) {
            for (y in ya until yb) {
                val k = kind(img.px[y * img.w + x])
                if (k >= 0) cnt[k]++
            }
        }
        val tot = cnt.sum()
        if (tot < 6) return null
        var top = 0
        for (k in 1 until 5) if (cnt[k] > cnt[top]) top = k
        if (cnt[top] < tot * 0.55f) return null
        return when (top) {
            0 -> "Muito rigoroso"
            1 -> "Rigoroso"
            2 -> "Normal"
            3 -> "Brando"
            else -> "Muito brando"
        }
    }
}

/** Hash perceptual 32x18 (576 bits) para deduplicar capturas e detectar estabilidade. */
object FrameHash {
    const val W = 65
    const val H = 36

    fun of(px: IntArray): LongArray {
        val gray = IntArray(W * H) { i ->
            val c = px[i]
            ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        }
        val out = LongArray(((W - 1) * H + 63) / 64)
        var bit = 0
        for (y in 0 until H) {
            for (x in 0 until W - 1) {
                if (gray[y * W + x] > gray[y * W + x + 1]) {
                    out[bit / 64] = out[bit / 64] or (1L shl (bit % 64))
                }
                bit++
            }
        }
        return out
    }

    fun distance(a: LongArray, b: LongArray): Int {
        var d = 0
        for (i in a.indices) d += java.lang.Long.bitCount(a[i] xor b[i])
        return d
    }

    fun toText(h: LongArray): String = h.joinToString("") { java.lang.Long.toHexString(it).padStart(16, '0') }
}
