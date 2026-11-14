package com.osmaicoach.collector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Cena da arte gerada por IA gratuita (Pollinations, sem chave): atleta FICTÍCIO, estádio, taça ou torcida, nas cores do time.
 * O texto, o escudo, a bandeira e os números NUNCA vêm da IA (o app desenha por cima: `ArtsDraw.poster`), então saem sempre certos.
 * Do jogador só vão ao gerador traços genéricos (cabelo e barba) descobertos pela IA de texto do app; o nome dele não é enviado ao gerador.
 */
object ArtsAi {
    @Volatile var lastError: String? = null

    // ------------------------------------------------------------------ cores e traços (funções puras, testadas)
    fun hueName(h: Float): String = when {
        h < 15f || h >= 345f -> "red"
        h < 40f -> "orange"
        h < 70f -> "yellow"
        h < 160f -> "green"
        h < 200f -> "cyan"
        h < 260f -> "blue"
        h < 300f -> "purple"
        else -> "pink"
    }

    /** "red and white", "green and yellow"…: do escudo (tom mais presente, o segundo e se há muito branco/preto). */
    fun colorWords(crest: Bitmap?, slot: Int): String {
        val fallback = listOf("green", "blue", "red", "purple")[(slot - 1).coerceIn(0, 3)]
        if (crest == null) return fallback
        val s = Bitmap.createScaledBitmap(crest, 32, 32, true)
        val w = FloatArray(12)
        var white = 0; var black = 0; var total = 0
        val hsv = FloatArray(3)
        for (y in 0 until 32) for (x in 0 until 32) {
            val px = s.getPixel(x, y)
            if ((px ushr 24) < 128) continue
            total++
            Color.colorToHSV(px, hsv)
            when {
                hsv[1] < 0.18f && hsv[2] > 0.85f -> white++
                hsv[2] < 0.22f -> black++
                hsv[1] >= 0.35f && hsv[2] >= 0.25f -> w[(hsv[0] / 30f).toInt().coerceIn(0, 11)] += hsv[1] * hsv[2]
            }
        }
        s.recycle()
        val order = w.indices.sortedByDescending { w[it] }
        val words = ArrayList<String>()
        if (w[order[0]] > 0f) {
            words.add(hueName(order[0] * 30f + 15f))
            val sec = order.drop(1).firstOrNull { w[it] > w[order[0]] * 0.25f && Math.min(Math.abs(it - order[0]), 12 - Math.abs(it - order[0])) >= 2 }
            if (sec != null) words.add(hueName(sec * 30f + 15f))
        }
        if (total > 0 && white.toFloat() / total > 0.12f && "white" !in words) words.add("white")
        else if (total > 0 && black.toFloat() / total > 0.18f && "black" !in words) words.add("black")
        return if (words.isEmpty()) fallback else words.distinct().take(3).joinToString(" and ")
    }

    private val HAIR = mapOf(
        "bald" to "completely bald, shaved head", "buzz" to "very short buzz cut hair", "short" to "short neat dark hair", "medium" to "medium length hair",
        "long" to "long hair tied back", "curly" to "short curly hair", "afro" to "short afro hair", "braids" to "braided hair", "bun" to "hair in a man bun",
        "mohawk" to "mohawk haircut", "blond" to "short blond hair", "red" to "short red ginger hair"
    )
    private val BEARD = mapOf("none" to "clean shaven", "stubble" to "light stubble", "short" to "short trimmed beard", "full" to "full thick beard")

    /** Traços genéricos (cabelo/barba) em inglês para o gerador; texto fora do esperado vira o padrão. */
    fun lookWords(json: JSONObject?): String {
        val hair = HAIR[json?.optString("hair", "")?.lowercase().orEmpty()] ?: HAIR.getValue("short")
        val beard = BEARD[json?.optString("beard", "")?.lowercase().orEmpty()] ?: BEARD.getValue("none")
        return "$hair, $beard"
    }

    private const val STYLE = "Cinematic sports press photograph for a football club announcement, vertical poster, ultra realistic, sharp focus, dramatic lighting, " +
        "shallow depth of field, rich contrast, no text, no letters, no logos, no watermark"

    /** Pedido da cena por tipo de arte. [colors] vem do escudo; [kit] é a camisa do clube descrita pela IA de texto (pode ser vazia); [look] são cabelo/barba. */
    fun prompt(kind: String, colors: String, kit: String, look: String): String {
        val shirt = if (kit.isNotBlank()) kit else "$colors football jersey"
        return when (kind) {
            "BUY", "PLAYER" -> "$STYLE. Waist-up shot of a fictional male footballer (not a real person), $look, standing with arms crossed, wearing $shirt, " +
                "strong rim light from behind, huge packed stadium at night in the background with bright floodlights, lens flare, $colors smoke, flying sparks, " +
                "dramatic dark $colors color grading. The player fills the upper two thirds of the frame and the bottom third is dark smoke"
            "GOLEADA" -> "$STYLE. Wide shot of fictional football players wearing $shirt celebrating a goal with arms raised, passionate fans in the stands, " +
                "night stadium, floodlights, $colors flares and smoke, confetti, dark $colors color grading. The bottom third of the frame is dark smoke"
            "STREAK" -> "$STYLE. Passionate football fans celebrating in a packed night stadium, arms raised, holding bright burning $colors flares, thick $colors smoke " +
                "lit by the flares, sparks flying, floodlights. The bottom third of the frame is dark smoke"
            "LEADER" -> "$STYLE. Empty football pitch seen from the touchline at night, a dramatic spotlight beam from the floodlights hitting the centre circle, " +
                "packed stands behind, $colors smoke, triumphant atmosphere, dark $colors color grading. The bottom third of the frame is dark smoke"
            "TITLE", "OBJECTIVE" -> "Product photograph of a large shiny golden football championship trophy cup with two handles standing on a dark pedestal, " +
                "dramatic spotlight from above, floating golden particles and confetti, dark $colors smoky background, stadium floodlights bokeh, luxury advertising style, " +
                "ultra realistic, sharp, no text, no letters, no logos, no watermark. The trophy fills the upper two thirds of the frame and the bottom third is dark"
            "EXPECT" -> "$STYLE. Inside a large football stadium seen from the touchline at dusk: green grass pitch with white lines in the foreground, tall stands all around " +
                "and bright floodlight towers, golden hour light beams cutting through mist over the pitch, hopeful and epic atmosphere, $colors tones. " +
                "Stadium interior only, no mountains, no landscape, no hills. The bottom third of the frame is dark"
            "MATCHDAY" -> "$STYLE. Football stadium tunnel entrance at night, two lines of fictional players in silhouette walking out toward bright floodlights, " +
                "smoke and lens flare, $colors glow. The bottom third of the frame is dark smoke"
            "COMPARE" -> "$STYLE. Two fictional football players facing each other tense in a night stadium, close shot, different colored jerseys, floodlights, " +
                "smoke, dark $colors color grading. The bottom third of the frame is dark smoke"
            "STARS" -> "$STYLE. Wide shot of three fictional footballers standing side by side, full heads and shoulders visible with space around them, centered in the frame, " +
                "wearing $shirt, dramatic backlight, stadium floodlights, smoke and sparks, dark $colors color grading. The players fill the upper half of the frame and the bottom half is dark smoke"
            "MOMENT" -> "$STYLE. Football fans waving flags and holding $colors flares in a packed night stadium, smoke, floodlights, energetic atmosphere, " +
                "dark $colors color grading. The bottom half of the frame is dark smoke"
            else -> "$STYLE. Wide cinematic view of a packed football stadium at night with bright floodlights, $colors flags and flares in the stands, smoke, " +
                "dark $colors color grading. The bottom third of the frame is dark smoke"
        }
    }

    // ------------------------------------------------------------------ IA de texto: camisa do clube e traços do jogador (com cache)
    private suspend fun cacheGet(repo: Repo, plan: String, key: String): String? =
        repo.dao.plan(0, plan)?.json?.let { runCatching { JSONObject(it).optString(key) }.getOrNull() }?.takeIf { it.isNotBlank() }

    private suspend fun cachePut(repo: Repo, plan: String, key: String, value: String) {
        val j = repo.dao.plan(0, plan)?.json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        j.put(key, value)
        repo.dao.putPlan(PlanEntity(0, plan, j.toString(), System.currentTimeMillis()))
    }

    private suspend fun askText(ctx: Context, prompt: String): String? {
        val g = runCatching { AiClient.askGrounded(ctx, prompt) }.getOrNull()
        if (g != null && g.ok && !g.text.isNullOrBlank()) return g.text
        val r = runCatching { AiClient.ask(ctx, prompt, null, 60000L) }.getOrNull()
        return r?.takeIf { it.ok }?.text
    }

    /** Camisa titular do clube em inglês (cores, padrão, gola): pergunta 1× por clube à IA do app (com busca na web quando há Gemini). Vazio se não deu. */
    suspend fun kitFor(ctx: Context, repo: Repo, team: String, comp: String?): String {
        val key = Txt.key(team)
        cacheGet(repo, "kits", key)?.let { return it }
        val q = "Descreva em UMA frase curta, em inglês, a camisa titular (home kit) do clube de futebol \"$team\"" + (comp?.let { " (campeonato: $it)" } ?: "") +
            ": cores principais, padrão (listras verticais, listras horizontais, faixa, lisa) e cor da gola. Comece com \"a\" e termine com \"football jersey\". " +
            "Sem nomes de patrocinadores. Se não souber, responda exatamente: UNKNOWN."
        val t = askText(ctx, q)?.trim()?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.trim('"', '.', '*')
        val ok = t != null && t.length in 12..160 && !t.contains("UNKNOWN", true) && t.contains("jersey", true)
        if (ok) cachePut(repo, "kits", key, t!!)
        return if (ok) t!! else ""
    }

    /** Cabelo e barba do jogador (a IA do app pesquisa o nome na web); só traços genéricos. Padrão: cabelo curto, sem barba. */
    suspend fun lookFor(ctx: Context, repo: Repo, name: String, age: Int?, country: String?, pos: String?): String {
        val key = Txt.key(name)
        cacheGet(repo, "looks", key)?.let { return lookWords(runCatching { JSONObject(it) }.getOrNull()) }
        val q = "Jogador de futebol \"$name\"" + (age?.let { ", $it anos" } ?: "") + (country?.let { ", nacionalidade $it" } ?: "") + (pos?.let { ", posição $it" } ?: "") +
            ". Pesquise na web como ele costuma aparecer, SÓ quanto ao cabelo e à barba. Responda APENAS um JSON: " +
            "{\"hair\":\"bald|buzz|short|medium|long|curly|afro|braids|bun|mohawk|blond|red|unknown\",\"beard\":\"none|stubble|short|full|unknown\"}"
        val j = AiClient.parseJson(askText(ctx, q))
        if (j != null) cachePut(repo, "looks", key, j.toString())
        return lookWords(j)
    }


    /** Retrato fictício (cabeça e ombros, rosto centrado) para a capa de jornal; recortado depois em círculo por `ArtsDraw.news`. */
    fun portraitPrompt(colors: String, kit: String, look: String): String {
        val shirt = if (kit.isNotBlank()) kit else "$colors football jersey"
        return "Studio portrait photograph of a fictional male professional footballer (not a real person), $look, face centered in the upper half of the frame, " +
            "looking at the camera with a calm serious expression, wearing $shirt, head and shoulders, soft studio light, plain dark $colors background, ultra realistic, sharp focus, " +
            "no text, no letters, no logos, no watermark"
    }

    /**
     * Fala do treinador para a capa de jornal (a ÚNICA parte inventada, a pedido do usuário): confiante e ligada aos números do slot. A IA só recebe [facts]
     * (números e nomes já lidos) e é proibida de criar outros; sem IA ou com resposta ruim, [fallbackQuote] monta a fala só com os mesmos números.
     */
    suspend fun quote(ctx: Context, facts: String, fallback: String): String {
        val q = "Escreva a fala de um treinador de futebol, sempre CONFIANTE, em português do Brasil, com 2 ou 3 frases curtas (no máximo 45 palavras), para uma capa de jornal. " +
            "Use vários destes números do time dele (e só eles; não invente outros números, nomes, placares nem fatos): $facts. " +
            "Responda APENAS com a fala, sem aspas, sem título e sem explicação."
        val t = askText(ctx, q)?.trim()?.trim('"', '“', '”', '*')?.replace(Regex("\\s+"), " ")
        return if (t != null && t.length in 40..380 && !t.contains("\n")) t else fallback
    }


    /**
     * Notícia do jogo para a capa de jornal pós-jogo: a IA de texto escreve manchete, linha fina e 2 parágrafos SÓ com os [facts] (placar, rival, rodada, gols lidos,
     * forças, posição). Devolve null se a IA não respondeu direito (quem chama usa um texto montado só com os mesmos fatos).
     */
    suspend fun matchStory(ctx: Context, facts: String): Triple<String, String, List<String>>? {
        val q = "Você é redator esportivo de um jornal brasileiro. Escreva a notícia do jogo abaixo, em português do Brasil, estilo de capa de jornal esportivo. " +
            "Use SOMENTE estes fatos: $facts. Não invente nomes, números, minutos, lances, declarações nem fatos além destes (não cite turno, primeiro/segundo tempo, público nem torcida); respeite exatamente quem foi mandante e quem foi visitante; se faltar informação, escreva de forma geral. " +
            "Responda APENAS um JSON: {\"headline\":\"manchete forte de até 70 caracteres\",\"sub\":\"linha fina de até 140 caracteres\",\"body\":[\"parágrafo 1 de até 55 palavras\",\"parágrafo 2 de até 55 palavras\"]}"
        val j = AiClient.parseJson(askText(ctx, q)) ?: return null
        val h = j.optString("headline").trim(); val sub = j.optString("sub").trim()
        val arr = j.optJSONArray("body") ?: return null
        val body = (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.length >= 20 }.take(3)
        return if (h.length in 8..110 && sub.length >= 10 && body.isNotEmpty()) Triple(h, sub.take(200), body) else null
    }

    /** Cena do jornal pós-jogo (torcida/jogadores), conforme o resultado. */
    fun matchScenePrompt(result: String, colors: String): String = when (result) {
        "V" -> "$STYLE. Fictional football players and fans celebrating a victory in a night stadium, arms raised, $colors flares and confetti, joyful atmosphere. The bottom third of the frame is dark smoke"
        "D" -> "$STYLE. A fictional football player kneeling on the pitch dejected after a defeat, hands on face, empty stands behind, night stadium, cold blue light, moody atmosphere. The bottom third of the frame is dark smoke"
        else -> "$STYLE. Two lines of fictional football players shaking hands on the pitch after a draw, night stadium, floodlights, tense calm atmosphere, $colors tones. The bottom third of the frame is dark smoke"
    }

    // ------------------------------------------------------------------ gerador de imagem (Pollinations)
    private fun url(prompt: String, seed: Int): String =
        "https://image.pollinations.ai/prompt/" + URLEncoder.encode(prompt, "UTF-8").replace("+", "%20") +
            "?width=1024&height=1280&model=flux&nologo=true&seed=$seed"

    /** Brilho médio (0..255) da parte de cima da cena: cena preta demais (serviço devolveu quase nada) é refeita com outra semente. */
    internal fun brightness(b: Bitmap): Float {
        val s = Bitmap.createScaledBitmap(b, 24, 30, true)
        var sum = 0f; var n = 0
        for (y in 0 until 18) for (x in 0 until 24) { val p = s.getPixel(x, y); sum += 0.3f * Color.red(p) + 0.59f * Color.green(p) + 0.11f * Color.blue(p); n++ }
        s.recycle()
        return sum / n
    }

    /** Segundos pedidos pelo serviço gratuito ("Retry after 27s") quando passa do limite de 1 imagem a cada 45 s por IP. */
    internal fun retryAfter(body: String): Int? = Regex("[Rr]etry after (\\d+)s").find(body)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Baixa a cena (1024×1280). O serviço gratuito deixa 1 imagem a cada 45 s por IP (resposta 402 "FREE_LIMIT"): espera o tempo que ele pede e tenta de novo
     * (até 4 tentativas). Cena escura demais é refeita. null com o motivo em [lastError].
     */
    suspend fun scene(prompt: String, seed: Int): Bitmap? = withContext(Dispatchers.IO) {
        var err = "sem resposta"
        var dark: Bitmap? = null
        for (attempt in 0 until 4) {
            try {
                val c = URL(url(prompt, seed + attempt * 7919)).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 120000
                c.setRequestProperty("User-Agent", "OSMCoach")
                val code = c.responseCode
                if (code == 200 && (c.contentType ?: "").startsWith("image")) {
                    val bytes = c.inputStream.use { it.readBytes() }
                    val b = if (bytes.size > 8000) BitmapFactory.decodeByteArray(bytes, 0, bytes.size) else null
                    if (b != null && b.width >= 400) {
                        // a faixa de baixo traz a marca d'água do serviço: corta
                        val cut = (b.height * 0.045f).toInt()
                        val ok = Bitmap.createBitmap(b, 0, 0, b.width, b.height - cut)
                        if (brightness(ok) >= 18f || attempt == 3) { dark?.recycle(); return@withContext ok }
                        dark?.recycle(); dark = ok   // escura demais: tenta outra semente (guarda esta se for a última)
                        err = "cena escura"
                        continue
                    }
                    err = "imagem inválida"
                } else {
                    val body = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8).take(400) }.orEmpty()
                    val wait = if (code == 402) retryAfter(body) else null
                    err = if (wait != null) "limite do serviço gratuito (1 imagem a cada 45 s)" else "serviço respondeu $code"
                    if (wait != null) { delay((wait.coerceIn(1, 60) + 2) * 1000L); continue }
                }
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
            }
            delay(4000)
        }
        if (dark != null) return@withContext dark
        lastError = "IA de imagem indisponível agora ($err). Tente de novo em instantes."
        null
    }
}
