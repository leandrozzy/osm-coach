package com.osmaicoach.collector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Arte pelo GEMINI (Google): o MESMO pedido do botão ChatGPT (dados reais do jogo + instruções do guia) vai à API do Gemini com as chaves que você já cadastrou
 * em Ajustes → IA, e a imagem volta pronta para a lista de artes. Só o texto do pedido sai do aparelho (nada de foto do jogador nem de chave em log).
 * Modelo de imagem descoberto sozinho (a lista do Google muda): nome com "image", não Imagen, que aceite generateContent; "flash" primeiro, versão maior primeiro.
 * Atleta é sempre FICTÍCIO (regra do guia). Limite/cota do Gemini para imagem varia por conta e por modelo: o erro do Google aparece na tela.
 */
object ArtsGemini {
    @Volatile var lastError: String? = null
    private const val PREFS = "arts"
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"

    /** Ligado = o "criar sozinho" usa o Gemini (precisa de chave); desligado = cena gratuita do app como antes. */
    fun autoOn(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("gem_auto", false)
    fun setAutoOn(ctx: Context, on: Boolean) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("gem_auto", on).apply() }
    fun hasKey(ctx: Context): Boolean = Settings.geminiKeys(ctx).isNotEmpty()

    // ------------------------------------------------------------------ escolha do modelo (pura, testada)
    /** Ordena nomes de modelos: só os de imagem do Gemini ("…-image…", nunca "imagen"), "flash" antes do resto, versão mais nova primeiro. */
    fun rankImageModels(names: List<String>): List<String> {
        fun ver(n: String): Double = Regex("gemini-(\\d+(?:\\.\\d+)?)").find(n)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        return names.map { it.removePrefix("models/") }
            .filter { it.contains("image", true) && !it.contains("imagen", true) && !it.contains("embed", true) }
            .distinct()
            .sortedWith(compareByDescending<String> { it.contains("flash", true) }.thenByDescending { ver(it) }.thenBy { it.contains("preview", true) })
    }

    /** O corpo do pedido: instruções do guia + pedido da arte, formato retrato, só imagem. */
    fun requestBody(prompt: String, aspect: String? = "3:4"): String {
        val parts = JSONArray().put(JSONObject().put("text", GptGuide.INSTRUCTIONS + "\n\n" + prompt + "\n\nGere UMA imagem final, formato retrato, com todos os textos escritos EXATAMENTE como indicados (sem inventar dados, nomes ou resultados)."))
        val gen = JSONObject().put("responseModalities", JSONArray().put("IMAGE").put("TEXT"))
        if (aspect != null) gen.put("imageConfig", JSONObject().put("aspectRatio", aspect))
        return JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", parts))).put("generationConfig", gen).toString()
    }

    /** Bytes da primeira imagem da resposta (inlineData / inline_data) ou null. */
    fun imageBytes(body: String): ByteArray? = runCatching {
        val cands = JSONObject(body).optJSONArray("candidates") ?: return null
        for (i in 0 until cands.length()) {
            val parts = cands.optJSONObject(i)?.optJSONObject("content")?.optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val p = parts.optJSONObject(j) ?: continue
                val inl = p.optJSONObject("inlineData") ?: p.optJSONObject("inline_data") ?: continue
                val data = inl.optString("data"); if (data.isNotBlank()) return Base64.decode(data, Base64.DEFAULT)
            }
        }
        null
    }.getOrNull()

    /** Por que veio 200 sem imagem: bloqueio do pedido, motivo de parada ou o texto que o modelo escreveu no lugar. */
    fun noImageReason(body: String): String = runCatching {
        val o = JSONObject(body)
        val block = o.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
        val c = o.optJSONArray("candidates")?.optJSONObject(0)
        val fin = c?.optString("finishReason").orEmpty()
        val txt = c?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text").orEmpty().take(120)
        listOf(if (block.isNotBlank()) "pedido bloqueado ($block)" else "", if (fin.isNotBlank() && fin != "STOP") "parou por $fin" else "", if (txt.isNotBlank()) "disse: \"$txt\"" else "").filter { it.isNotBlank() }.joinToString("; ").ifBlank { "resposta vazia" }
    }.getOrDefault("resposta ilegível")

    /** Mensagem curta e em português do erro do Google (cota, chave, modelo). */
    fun explain(code: Int, body: String): String {
        val msg = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty().take(140)
        return when {
            code == 429 || msg.contains("quota", true) -> "Gemini: sem cota para imagem nesta chave hoje/neste modelo" + (if (msg.contains("limit: 0")) " (a cota grátis de imagem é 0 nesta conta: use outra chave ou ative o faturamento no Google AI Studio)" else "") + "."
            code == 400 && msg.contains("API key", true) -> "Gemini: chave recusada (confira em Ajustes → IA)."
            code == 403 -> "Gemini: chave sem permissão para este modelo de imagem."
            code == 404 -> "Gemini: modelo de imagem indisponível."
            code == 503 -> "Gemini: sobrecarregado agora, tente de novo em instantes."
            else -> "Gemini: HTTP $code" + (if (msg.isNotBlank()) " — $msg" else "")
        }
    }

    private class Resp(val code: Int, val body: String)

    private fun call(method: String, url: String, key: String, body: String?): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method; c.connectTimeout = 20_000; c.readTimeout = 150_000
            c.setRequestProperty("x-goog-api-key", key)
            if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) } }
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            return Resp(code, txt)
        } finally { c.disconnect() }
    }

    private fun models(ctx: Context, key: String): List<String> {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = sp.getLong("gem_models_at", 0L)
        val cached = sp.getString("gem_models", "").orEmpty()
        if (cached.isNotBlank() && System.currentTimeMillis() - at < 12 * 3_600_000L) return cached.split(",").filter { it.isNotBlank() }
        val r = runCatching { call("GET", "$BASE/models?pageSize=200", key, null) }.getOrNull()
        val names = if (r != null && r.code in 200..299) {
            val arr = runCatching { JSONObject(r.body).optJSONArray("models") }.getOrNull()
            val out = ArrayList<String>()
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val m = o.optJSONArray("supportedGenerationMethods"); var gen = m == null
                if (m != null) for (j in 0 until m.length()) if (m.optString(j) == "generateContent") gen = true
                if (gen) out.add(o.optString("name"))
            }
            rankImageModels(out)
        } else emptyList()
        val use = names.ifEmpty { listOf("gemini-2.5-flash-image") }
        if (names.isNotEmpty()) sp.edit().putString("gem_models", use.joinToString(",")).putLong("gem_models_at", System.currentTimeMillis()).apply()
        return use
    }

    /** Gera a arte [c] pelo Gemini e guarda na lista de artes (mesma lista das outras). Devolve null e preenche [lastError] se falhar. */
    suspend fun generate(ctx: Context, c: Arts.Cand): Arts.Item? = withContext(Dispatchers.IO) {
        lastError = null
        val repo = Repo(ctx)
        val prompt = ArtsGpt.prompt(ctx, repo, c) ?: run { lastError = "Faltam dados lidos para montar o pedido desta arte."; return@withContext null }
        val keys = Settings.geminiKeys(ctx)
        if (keys.isEmpty()) { lastError = "Cadastre a chave do Gemini em Ajustes → IA."; return@withContext null }
        var err: String? = null
        Diag.log("Artes: Gemini gerando ${c.kind} (${keys.size} chave(s))")
        for (key in keys) {
            for (model in models(ctx, key).take(3)) {
                var aspect: String? = "3:4"
                repeat(2) {
                    val r = runCatching { call("POST", "$BASE/models/$model:generateContent", key, requestBody(prompt, aspect)) }.getOrElse { e -> err = "Gemini: sem resposta (${e.javaClass.simpleName})."; null }
                    if (r != null) {
                        if (r.code in 200..299) {
                            val bytes = imageBytes(r.body)
                            val bmp = bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
                            if (bmp != null) {
                                val item = Arts.storeBitmap(ctx, repo, c, bmp)
                                Diag.log("Artes: Gemini ($model) criou ${c.kind}")
                                return@withContext item
                            }
                            err = "Gemini ($model): respondeu sem imagem — " + noImageReason(r.body)
                            Diag.log("Artes: $err")
                            return@repeat
                        }
                        // imageConfig não aceito por alguns modelos: tenta de novo sem ele
                        if (r.code == 400 && aspect != null && r.body.contains("imageConfig", true)) { aspect = null; return@repeat }
                        err = explain(r.code, r.body)
                        Diag.log("Artes: Gemini $model HTTP ${r.code}: " + r.body.take(200).replace(Regex("\\s+"), " "))
                        if (r.code == 429 || r.code == 403) return@repeat    // próxima chave
                    }
                }
            }
        }
        lastError = err ?: "Gemini: não consegui gerar a imagem."
        null
    }
}
