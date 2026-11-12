package com.osmaicoach.collector

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Artes (12/11): imagens de divulgação desenhadas pelo próprio app (sem IA de imagem, sem internet, sem custo) com os dados JÁ lidos do jogo:
 * contratação, despedida (venda confirmada), vitória/sequência, campanha e fim de temporada, uma por slot, nas cores do escudo do time.
 * Não existe foto do jogador no OSM: o destaque é a camisa com a força. Nada é inventado: só sai arte de evento confirmado.
 */
object Arts {
    const val W = 1080
    const val H = 1350

    data class Item(val id: String, val slot: Int, val kind: String, val title: String, val at: Long, val file: String)
    data class Cand(val id: String, val slot: Int, val kind: String, val title: String, val at: Long, val key: String? = null)

    private fun dir(ctx: Context): File = File(ctx.filesDir, "arts").apply { mkdirs() }

    // ------------------------------------------------------------------ guardado (plano global `arts`, ids vistos em `artsseen`)
    suspend fun items(repo: Repo): List<Item> {
        val arr = runCatching { JSONArray(repo.dao.plan(0, "arts")?.json ?: "[]") }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Item(o.optString("id"), o.optInt("slot"), o.optString("kind"), o.optString("title"), o.optLong("at"), o.optString("file"))
        }.filter { File(it.file).exists() }.sortedByDescending { it.at }
    }

    private suspend fun saveItems(repo: Repo, list: List<Item>) {
        val arr = JSONArray()
        for (i in list) arr.put(JSONObject().put("id", i.id).put("slot", i.slot).put("kind", i.kind).put("title", i.title).put("at", i.at).put("file", i.file))
        repo.dao.putPlan(PlanEntity(0, "arts", arr.toString(), System.currentTimeMillis()))
    }

    suspend fun delete(repo: Repo, item: Item) {
        runCatching { File(item.file).delete() }
        saveItems(repo, items(repo).filter { it.id != item.id })
    }

    private suspend fun seen(repo: Repo): MutableSet<String>? {
        val p = repo.dao.plan(0, "artsseen") ?: return null
        val arr = runCatching { JSONArray(p.json) }.getOrDefault(JSONArray())
        return (0 until arr.length()).map { arr.optString(it) }.toMutableSet()
    }

    private suspend fun putSeen(repo: Repo, s: Set<String>) {
        val arr = JSONArray(); s.toList().takeLast(400).forEach { arr.put(it) }
        repo.dao.putPlan(PlanEntity(0, "artsseen", arr.toString(), System.currentTimeMillis()))
    }

    // ------------------------------------------------------------------ o que pode virar arte
    private fun played(ms: List<MatchEntity>) = ms.filter { it.round != null && it.result in listOf("V", "E", "D") }.sortedBy { it.round }

    /** Eventos deste slot que dão arte. O id é estável (não repete a mesma arte). */
    suspend fun candidates(repo: Repo, slot: Int): List<Cand> {
        val f = repo.fieldMap(slot)
        if (f[K.TEAM]?.value == null) return emptyList()
        val out = ArrayList<Cand>()
        val ledger = Ledger.parse(repo.dao.plan(slot, "ledger")?.json)
        for (e in ledger.filter { it.type == "buy" || it.type == "sell" }.sortedByDescending { it.at }.take(12)) {
            val buy = e.type == "buy"
            out.add(Cand("${e.type}|$slot|${e.key}|${e.at}", slot, if (buy) "BUY" else "SELL", (if (buy) "Contratação: " else "Despedida: ") + e.name, e.at, e.key))
        }
        val ms = played(repo.dao.matchesOf(slot))
        val last = ms.lastOrNull()
        if (last != null && last.result == "V") {
            val streak = ms.reversed().takeWhile { it.result == "V" }.size
            val diff = (last.scoreMine ?: 0) - (last.scoreOpp ?: 0)
            if (streak >= 3) out.add(Cand("streak|$slot|$streak|${last.round}", slot, "STREAK", "$streak vitórias seguidas", last.updatedAt))
            else if (diff >= 3) out.add(Cand("win|$slot|${last.round}", slot, "WIN", "Goleada na J${last.round}: ${last.scoreMine}×${last.scoreOpp}", last.updatedAt))
        }
        if (ms.size >= 3) out.add(Cand("camp|$slot|${ms.size}", slot, "CAMP", "Campanha até a J${ms.last().round}", System.currentTimeMillis()))
        if (ms.isNotEmpty() && Hints.competitionOver(repo, slot, f)) {
            out.add(Cand("season|$slot|${Txt.key(f[K.COMPETITION]?.value.orEmpty())}", slot, "SEASON", "Fim da temporada", System.currentTimeMillis()))
        }
        return out
    }

    /**
     * Depois de Encerrar a leitura: cria as artes dos eventos NOVOS de cada slot (contratação, despedida, goleada/sequência, fim de temporada).
     * Na 1ª vez só vale o que aconteceu nas últimas 24 h (não despeja o histórico). Campanha só à mão. Devolve quantas criou.
     */
    suspend fun auto(ctx: Context): Int {
        val repo = Repo(ctx)
        val firstRun = seen(repo) == null
        val seenSet = seen(repo) ?: mutableSetOf()
        val now = System.currentTimeMillis()
        var made = 0
        for (slot in 1..4) {
            for (c in candidates(repo, slot)) {
                if (c.id in seenSet) continue
                seenSet.add(c.id)
                if (c.kind == "CAMP") continue
                if (firstRun && now - c.at > 24 * 3600_000L) continue
                if (made >= 6) continue
                if (runCatching { make(ctx, c) }.getOrNull() != null) made++
            }
        }
        putSeen(repo, seenSet)
        if (made > 0) {
            val sp = ctx.getSharedPreferences("arts", Context.MODE_PRIVATE)
            sp.edit().putInt("new", sp.getInt("new", 0) + made).apply()
            Diag.log("Artes: $made nova(s) criada(s)")
            UiBus.bump()
        }
        return made
    }

    fun unseenCount(ctx: Context): Int = ctx.getSharedPreferences("arts", Context.MODE_PRIVATE).getInt("new", 0)
    fun clearUnseen(ctx: Context) { ctx.getSharedPreferences("arts", Context.MODE_PRIVATE).edit().putInt("new", 0).apply() }

    // ------------------------------------------------------------------ montar e desenhar
    class Spec(
        val team: String, val comp: String?, val slot: Int, val crest: Bitmap?, val banner: String,
        val big1: String, val big2: String, val rows: List<Pair<String, String>>, val flag: Bitmap?,
        val number: String?, val numberLabel: String?, val footer: String, val c1: Int, val c2: Int, val player: Boolean
    )

    private fun bmp(f: File?): Bitmap? = f?.takeIf { it.exists() }?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }

    suspend fun make(ctx: Context, c: Cand): Item? {
        val repo = Repo(ctx)
        val spec = spec(ctx, repo, c) ?: return null
        val b = render(spec)
        val f = File(dir(ctx), "${System.currentTimeMillis()}_${c.slot}.png")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        b.recycle()
        val item = Item(c.id, c.slot, c.kind, c.title, System.currentTimeMillis(), f.absolutePath)
        val all = items(repo).filter { it.id != c.id }
        val keep = (listOf(item) + all).take(60)
        for (old in all.drop(59)) runCatching { File(old.file).delete() }
        saveItems(repo, keep)
        UiBus.bump()
        return item
    }

    private suspend fun spec(ctx: Context, repo: Repo, c: Cand): Spec? {
        val slot = c.slot
        val f = repo.fieldMap(slot)
        val team = f[K.TEAM]?.value ?: return null
        val comp = f[K.COMPETITION]?.value
        val crest = bmp(CrestWeb.find(ctx.filesDir, team) ?: CrestCut.find(ctx.filesDir, team))
        val (c1, c2) = palette(crest, slot)
        val ms = played(repo.dao.matchesOf(slot))
        when (c.kind) {
            "BUY", "SELL", "PLAYER" -> {
                val key = c.key ?: return null
                val p = repo.dao.playersOf(slot).firstOrNull { it.owner == "MY" && it.nameKey == key }
                val e = Ledger.parse(repo.dao.plan(slot, "ledger")?.json).filter { it.key == key }.maxByOrNull { it.at }
                val name = p?.name ?: e?.name ?: return null
                val parts = name.trim().split(Regex("\\s+"))
                val first = if (parts.size > 1) parts.dropLast(1).joinToString(" ") else ""
                val last = parts.last()
                val iso = FlagManual.all(repo)[key] ?: repo.dao.plan(slot, "natiso2")?.json?.let { runCatching { JSONObject(it).optString(key) }.getOrNull() }?.takeIf { it.isNotBlank() }
                val rows = ArrayList<Pair<String, String>>()
                Flags.countryName(iso)?.let { rows.add("País" to it) }
                (p?.age ?: e?.age)?.let { rows.add("Idade" to "$it anos") }
                (p?.cat ?: p?.posCode)?.let { rows.add("Posição" to posLabel(p?.cat, p?.posCode)) }
                (p?.valueText ?: e?.price?.let { fmtM(it) })?.let { rows.add("Valor" to it) }
                val str = (p?.strength ?: e?.strength)?.toString()
                val (banner, foot) = when (c.kind) {
                    "BUY" -> "CONTRATAÇÃO CONFIRMADA" to "Bem-vindo ao $team!"
                    "SELL" -> "DESPEDIDA" to "Obrigado, $last! Boa sorte."
                    else -> "DESTAQUE DO ELENCO" to "$team • orgulho do clube"
                }
                return Spec(team, comp, slot, crest, banner, first, last, rows, bmp(FlagWeb.file(ctx.filesDir, iso)), str, "FORÇA", foot, c1, c2, true)
            }
            "WIN", "STREAK" -> {
                val last = ms.lastOrNull() ?: return null
                val streak = ms.reversed().takeWhile { it.result == "V" }.size
                val rows = ArrayList<Pair<String, String>>()
                rows.add("Jogo" to (if (last.home == true) "em casa" else if (last.home == false) "fora" else "—"))
                last.opponent?.let { rows.add("Rival" to it) }
                if (streak >= 2) rows.add("Sequência" to "$streak vitórias")
                val big = if (c.kind == "STREAK") "$streak" else "${last.scoreMine ?: 0} × ${last.scoreOpp ?: 0}"
                val sub = if (c.kind == "STREAK") "VITÓRIAS SEGUIDAS" else "J${last.round} • ${last.opponent.orEmpty()}"
                return Spec(team, comp, slot, crest, if (c.kind == "STREAK") "EM CHAMAS!" else "VITÓRIA!", sub, big, rows, null, null, null, "Vamos pra cima, $team!", c1, c2, false)
            }
            else -> {   // CAMP, SEASON
                if (ms.isEmpty()) return null
                val v = ms.count { it.result == "V" }; val e = ms.count { it.result == "E" }; val d = ms.count { it.result == "D" }
                val gf = ms.sumOf { it.scoreMine ?: 0 }; val ga = ms.sumOf { it.scoreOpp ?: 0 }
                val aprov = Math.round(100f * (3 * v + e) / (3 * ms.size))
                val rows = listOf("Jogos" to "${ms.size}", "Gols" to "$gf × $ga", "Aproveitamento" to "$aprov%")
                val season = c.kind == "SEASON"
                return Spec(team, comp, slot, crest, if (season) "FIM DE TEMPORADA" else "CAMPANHA ATÉ AGORA", comp.orEmpty(), "${v}V ${e}E ${d}D", rows, null, null, null,
                    if (season) "Obrigado, torcida! Até a próxima." else "A jornada continua!", c1, c2, false)
            }
        }
    }

    private fun posLabel(cat: String?, code: String?): String {
        val base = when (cat) { "GOL" -> "Goleiro"; "DEF" -> "Defensor"; "MEI" -> "Meio-campista"; "ATA" -> "Atacante"; else -> "" }
        return if (base.isNotEmpty() && !code.isNullOrBlank()) "$base ($code)" else base.ifEmpty { code.orEmpty() }
    }

    /** Cores do time: o tom mais presente do escudo vira o fundo escuro; o segundo (ou dourado) vira o destaque. */
    fun palette(crest: Bitmap?, slot: Int): Pair<Int, Int> {
        val fallback = intArrayOf(140, 215, 355, 275)[(slot - 1).coerceIn(0, 3)].toFloat()
        var h1 = fallback; var h2: Float? = null
        if (crest != null) {
            val s = Bitmap.createScaledBitmap(crest, 32, 32, true)
            val w = FloatArray(12)
            val hsv = FloatArray(3)
            for (y in 0 until 32) for (x in 0 until 32) {
                val px = s.getPixel(x, y)
                if ((px ushr 24) < 128) continue
                Color.colorToHSV(px, hsv)
                if (hsv[1] < 0.35f || hsv[2] < 0.25f) continue
                w[(hsv[0] / 30f).toInt().coerceIn(0, 11)] += hsv[1] * hsv[2]
            }
            s.recycle()
            val order = w.indices.sortedByDescending { w[it] }
            if (w[order[0]] > 0f) {
                h1 = order[0] * 30f + 15f
                val sec = order.drop(1).firstOrNull { w[it] > w[order[0]] * 0.25f && Math.min(Math.abs(it - order[0]), 12 - Math.abs(it - order[0])) >= 2 }
                if (sec != null) h2 = sec * 30f + 15f
            }
        }
        val bg = Color.HSVToColor(floatArrayOf(h1, 0.85f, 0.30f))
        val accent = Color.HSVToColor(floatArrayOf(h2 ?: if (h1 in 35f..75f) 140f else 48f, 0.9f, 1f))
        return Pair(bg, accent)
    }

    private fun darker(c: Int, f: Float): Int = Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())

    private fun face(): Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC)

    private fun fit(p: Paint, text: String, maxW: Float, size: Float) {
        p.textSize = size
        while (p.measureText(text) > maxW && p.textSize > 16f) p.textSize -= 2f
    }

    private fun drawFit(c: Canvas, b: Bitmap, box: RectF) {
        val s = Math.min(box.width() / b.width, box.height() / b.height)
        val w = b.width * s; val h = b.height * s
        val l = box.left + (box.width() - w) / 2f; val t = box.top + (box.height() - h) / 2f
        c.drawBitmap(b, null, RectF(l, t, l + w, t + h), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }

    fun render(s: Spec): Bitmap {
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val ap = Paint(Paint.ANTI_ALIAS_FLAG)
        ap.shader = LinearGradient(0f, 0f, 0f, H.toFloat(), darker(s.c1, 1.15f), darker(s.c1, 0.35f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), ap)
        ap.shader = RadialGradient(W * 0.82f, H * 0.14f, W * 0.75f, Color.argb(95, Color.red(s.c2), Color.green(s.c2), Color.blue(s.c2)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), ap)
        ap.shader = null
        // listras diagonais do canto inferior
        for ((i, a) in listOf(150, 90, 50).withIndex()) {
            ap.color = Color.argb(a, Color.red(s.c2), Color.green(s.c2), Color.blue(s.c2))
            val x0 = -60f + i * 120f
            val path = Path().apply { moveTo(x0, H.toFloat()); lineTo(x0 + 260f, H.toFloat()); lineTo(x0 + 560f, H - 360f); lineTo(x0 + 400f, H - 360f); close() }
            c.drawPath(path, ap)
        }
        // topo: escudo + clube
        s.crest?.let { drawFit(c, it, RectF(56f, 50f, 246f, 240f)) }
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face(); color = Color.WHITE }
        fit(tp, s.team.uppercase(), W - 300f - 56f, 66f)
        c.drawText(s.team.uppercase(), 276f, 130f, tp)
        tp.typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL); tp.color = s.c2
        fit(tp, (s.comp ?: "Slot ${s.slot}"), W - 300f - 56f, 36f)
        c.drawText(s.comp ?: "Slot ${s.slot}", 276f, 186f, tp)
        // faixa (pincelada) com o título
        val bannerTop = 290f; val bannerH = 130f
        ap.color = s.c2
        c.drawPath(Path().apply { moveTo(30f, bannerTop + 14f); lineTo(W - 120f, bannerTop); lineTo(W - 90f, bannerTop + bannerH); lineTo(50f, bannerTop + bannerH - 12f); close() }, ap)
        ap.color = Color.argb(110, 255, 255, 255)
        c.drawRect(60f, bannerTop + 6f, W - 140f, bannerTop + 14f, ap)
        val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face(); color = darker(s.c1, 0.35f) }
        fit(bp, s.banner, W - 260f, 84f)
        c.drawText(s.banner, 76f, bannerTop + bannerH / 2f + bp.textSize * 0.34f, bp)

        if (s.player) {
            // camisa com a força + informações
            drawShirt(c, RectF(50f, 470f, 560f, 980f), s)
            var y = 540f
            val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL); color = Color.argb(190, 255, 255, 255); textSize = 30f }
            val vp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face(); color = Color.WHITE }
            for ((k, v) in s.rows.take(4)) {
                var tx = 610f
                if (k == "País" && s.flag != null) { drawFit(c, s.flag, RectF(610f, y - 44f, 690f, y + 8f)); tx = 706f }
                c.drawText(k.uppercase(), tx, y - 30f, lp)
                fit(vp, v, W - tx - 40f, 50f)
                c.drawText(v, tx, y + 14f, vp)
                ap.color = Color.argb(70, 255, 255, 255); c.drawRect(610f, y + 40f, W - 40f, y + 43f, ap)
                y += 112f
            }
            // nome
            val np = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face(); color = Color.WHITE }
            if (s.big1.isNotBlank()) { fit(np, s.big1.uppercase(), W - 120f, 76f); c.drawText(s.big1.uppercase(), 60f, 1050f, np) }
            np.color = s.c2
            fit(np, s.big2.uppercase(), W - 110f, 190f)
            c.drawText(s.big2.uppercase(), 56f, 1215f, np)
        } else {
            val np = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face(); color = Color.WHITE }
            if (s.big1.isNotBlank()) { fit(np, s.big1.uppercase(), W - 120f, 64f); np.textAlign = Paint.Align.CENTER; c.drawText(s.big1.uppercase(), W / 2f, 560f, np) }
            np.color = s.c2; np.textAlign = Paint.Align.CENTER
            fit(np, s.big2, W - 100f, 330f)
            c.drawText(s.big2, W / 2f, 800f, np)
            val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL); color = Color.argb(190, 255, 255, 255); textSize = 30f }
            val vp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face(); color = Color.WHITE }
            var y = 900f
            for ((k, v) in s.rows.take(3)) {
                lp.textAlign = Paint.Align.LEFT; vp.textAlign = Paint.Align.RIGHT
                c.drawText(k.uppercase(), 140f, y, lp)
                fit(vp, v, 560f, 46f); c.drawText(v, W - 140f, y, vp)
                ap.color = Color.argb(70, 255, 255, 255); c.drawRect(140f, y + 18f, W - 140f, y + 21f, ap)
                y += 92f
            }
        }
        // rodapé
        ap.color = Color.argb(150, 0, 0, 0); c.drawRect(0f, H - 100f, W.toFloat(), H.toFloat(), ap)
        val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face(); color = Color.WHITE }
        fit(fp, s.footer, W - 340f, 46f)
        c.drawText(s.footer, 40f, H - 36f, fp)
        fp.typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL); fp.textSize = 28f; fp.color = Color.argb(170, 255, 255, 255); fp.textAlign = Paint.Align.RIGHT
        c.drawText("OSM Coach • S${s.slot}", W - 36f, H - 38f, fp)
        return out
    }

    private fun drawShirt(c: Canvas, box: RectF, s: Spec) {
        fun px(x: Float) = box.left + box.width() * x
        fun py(y: Float) = box.top + box.height() * y
        val path = Path().apply {
            moveTo(px(0.30f), py(0.05f)); lineTo(px(0.40f), py(0.05f)); quadTo(px(0.50f), py(0.17f), px(0.60f), py(0.05f)); lineTo(px(0.70f), py(0.05f))
            lineTo(px(0.97f), py(0.22f)); lineTo(px(0.85f), py(0.43f)); lineTo(px(0.75f), py(0.35f)); lineTo(px(0.75f), py(0.96f))
            lineTo(px(0.25f), py(0.96f)); lineTo(px(0.25f), py(0.35f)); lineTo(px(0.15f), py(0.43f)); lineTo(px(0.03f), py(0.22f)); close()
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.argb(90, 0, 0, 0)
        c.save(); c.translate(10f, 14f); c.drawPath(path, p); c.restore()
        p.color = s.c2
        c.drawPath(path, p)
        // faixa central e mangas na cor do clube
        p.color = s.c1
        c.drawRect(px(0.42f), py(0.17f), px(0.58f), py(0.96f), p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 10f; p.color = darker(s.c1, 0.6f)
        c.drawPath(path, p)
        p.style = Paint.Style.FILL
        s.crest?.let { drawFit(c, it, RectF(px(0.28f), py(0.20f), px(0.40f), py(0.34f))) }
        s.number?.let { n ->
            val np = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face(); color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 190f }
            while (np.measureText(n) > box.width() * 0.34f && np.textSize > 40f) np.textSize -= 6f
            c.drawText(n, px(0.50f), py(0.66f), np)
            np.textSize = 36f; np.color = Color.argb(220, 255, 255, 255)
            c.drawText(s.numberLabel.orEmpty(), px(0.50f), py(0.76f), np)
        }
    }

    // ------------------------------------------------------------------ galeria e compartilhamento
    fun saveToGallery(ctx: Context, it: Item): Uri? {
        val sp = ctx.getSharedPreferences("arts_saved", Context.MODE_PRIVATE)
        sp.getString(it.id + "|" + it.at, null)?.let { u -> return Uri.parse(u) }
        val f = File(it.file)
        if (!f.exists()) return null
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "osm_s${it.slot}_${it.at}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/OSM Coach")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { i -> i.copyTo(o) } }
        sp.edit().putString(it.id + "|" + it.at, uri.toString()).apply()
        return uri
    }

    fun share(ctx: Context, it: Item) {
        val uri = saveToGallery(ctx, it) ?: return
        val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Compartilhar arte").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
