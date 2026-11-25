package com.osmaicoach.collector

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Base Mundial — sincronização automática. Fila por prioridade, tarefas RETOMÁVEIS (checkpoint em bytes), uma por vez (concorrência 1, memória constante),
 * backoff exponencial, disjuntor por fonte (3 falhas seguidas = pausa de 6 h) e controle de cota. Nunca bloqueia OCR/slots: roda em `AppScope`/IO e só em Wi‑Fi (ou com permissão em Ajustes).
 * Agendamento: reutiliza o monitor em primeiro plano (`KeepAliveService`, 1 verificação/minuto) — `tick` decide o que está vencido (diário/semanal/mensal).
 */
object WorldSync {
    const val DAY = 86_400_000L
    private val lock = Mutex()
    private val pause = AtomicBoolean(false)
    @Volatile var running: String? = null; private set
    @Volatile var lastMessage: String = ""; private set
    /** Progresso REAL da tarefa em andamento (bytes lidos / bytes do arquivo); null quando não há total conhecido. */
    @Volatile var progress: Float? = null; private set
    @Volatile var counters: WorldImport.Counters = WorldImport.Counters(); private set
    /** Etapa geral da sincronização ("2 de 6") — a barra geral = etapas concluídas + progresso da atual. */
    @Volatile var imagesNote: String = ""; private set
    @Volatile var step: Int = 0; private set
    @Volatile var steps: Int = 0; private set
    val overall: Float? get() = if (running == null || steps == 0) null else ((step.toFloat() + (progress ?: 0f)) / steps).coerceIn(0f, 1f)

    /** Tarefas deixadas "sincronizando" por um app fechado no meio da importação: viram "interrompida" (a importação retoma do checkpoint). */
    fun healStale(db: WorldDb) {
        if (running != null) return
        db.w.execSQL("UPDATE football_sync_jobs SET status='interrompida', finished=COALESCE(finished,started), message=COALESCE(message,'')||' (o app foi fechado; retoma do ponto salvo)' WHERE status='sincronizando'")
        db.w.execSQL("UPDATE football_sources SET status=CASE WHEN last_sync IS NOT NULL THEN 'atualizada' WHEN imported>0 THEN 'operacional' ELSE 'aguardando sincronização' END WHERE status='sincronizando'")
    }

    fun pause() { pause.set(true) }
    fun resume(ctx: Context) { pause.set(false); requestRun(ctx) }
    val paused get() = pause.get()

    fun unmetered(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    /** Sem importação em massa, as consultas são pequenas (nomes lidos): qualquer rede com internet serve. */
    private fun allowedNet(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    fun requestRun(ctx: Context) { val app = ctx.applicationContext; AppScope.scope.launch(Dispatchers.IO) { runCatching { runDue(app, force = true) } } }
    fun requestSource(ctx: Context, sourceId: String) { val app = ctx.applicationContext; AppScope.scope.launch(Dispatchers.IO) { runCatching { runDue(app, force = true, only = sourceId) } } }

    /** Chamado pelo monitor a cada minuto: barato (só consulta a hora do próximo ciclo). */
    fun tick(ctx: Context) {
        if (running != null || pause.get()) return
        val app = ctx.applicationContext
        val last = Settings.get(app, "world_last_tick", "0").toLongOrNull() ?: 0L
        val now = System.currentTimeMillis()
        if (now - last < 10 * 60_000L) return
        Settings.put(app, "world_last_tick", now.toString())
        AppScope.scope.launch(Dispatchers.IO) { runCatching { runDue(app, force = false) } }
    }

    fun networkOk(ctx: Context): Boolean = allowedNet(ctx)

    /** Sincronização LEVE (nada de importação em massa no aparelho): lê o que o OSM mostrou → pede ao Supabase os candidatos desses nomes → identifica → atualiza só o que mudou (1x/dia) → imagens do OSM. */
    suspend fun runDue(ctx: Context, force: Boolean, only: String? = null) {
        if (!lock.tryLock()) return
        val db = WorldDb.get(ctx)
        try {
            WorldSources.seed(db); healStale(db)
            steps = 4; step = 0; progress = null
            running = "Lendo o que o OSM já mostrou"; UiBus.bump()
            runCatching { WorldOsm.refresh(ctx, force = true) }                           // local + Supabase (só os nomes lidos)
            if (pause.get()) { lastMessage = "Pausada."; return }
            if (!allowedNet(ctx)) { lastMessage = "Sem rede liberada (Wi‑Fi). O que já está no aparelho continua valendo."; return }
            step = 1; running = "Países"; UiBus.bump()
            runCatching { ensureCountries(ctx, db) }
            if (pause.get()) { lastMessage = "Pausada."; return }
            runCatching { checkSupabase(ctx, db) }
            step = 2; running = "Atualizando o que mudou no Supabase"; UiBus.bump()
            val changed = runCatching { WorldRemote.refreshCache(ctx, AndroidSql(db.w)) }.getOrDefault(0)
            step = 3; running = "Bandeiras, escudos e fotos do OSM"; progress = null; UiBus.bump()
            val got = runCatching { WorldImagesRemote.pull(ctx) }.getOrDefault(0)        // 1º: o que já está no Supabase (evita baixar da internet)
            runCatching { WorldOsm.prefetchImages(ctx) }                                 // 2º: o que falta, pelos baixadores do app
            val sent = runCatching { WorldImagesRemote.push(ctx) }.getOrDefault(0)       // 3º: o que o app tem e o Supabase ainda não
            imagesNote = "Imagens: $got baixadas do Supabase • $sent enviadas"
            runCatching { WorldCoverage.snapshot(db) }
            lastMessage = "Concluída: " + WorldOsm.lastReport + (if (changed > 0) " $changed itens atualizados do Supabase." else "") + " " + imagesNote
        } finally { running = null; progress = null; step = 0; steps = 0; lock.unlock(); UiBus.bump() }
    }

    private fun ensureCountries(ctx: Context, db: WorldDb) {
        if (db.count("football_countries") > 200) return
        val (code, body) = WorldHttp.text(WorldSources.URL_FLAG_CODES)
        db.bumpUsage(WorldSources.FLAGS, code)
        if (code in 200..299 && body != null) { WorldImport.importCountries(AndroidSql(db.w), body); WorldSources.status(db, WorldSources.FLAGS, "operacional") }
    }

    /** Grava o resultado do teste no banco local (pode esperar se o banco estiver ocupado: por isso NUNCA é chamado de dentro de um botão). */
    fun recordSupabase(ctx: Context, r: Result<WorldRemote.Stats>) {
        val db = WorldDb.get(ctx)
        r.fold(
            { st -> WorldSources.status(db, WorldSources.SUPABASE, if (st.players + st.clubs > 0) "atualizada" else "aguardando sincronização", "Conectado: ${st.countries} países • ${st.competitions} competições • ${st.clubs} clubes • ${st.players} jogadores • ${st.images} imagens" + (st.lastRun?.let { " • última carga: $it" } ?: "")); db.w.execSQL("UPDATE football_sources SET verified_at=? WHERE id=?", arrayOf(System.currentTimeMillis(), WorldSources.SUPABASE)) },
            { e -> WorldSources.status(db, WorldSources.SUPABASE, if (WorldRemote.cfg(ctx) == null) "aguardando configuração" else "indisponível", e.message) })
        UiBus.bump()
    }

    /** Teste REAL do Supabase + registro do status (uso em segundo plano). */
    fun checkSupabase(ctx: Context, db: WorldDb): Result<WorldRemote.Stats> {
        val r = if (WorldRemote.cfg(ctx) == null) Result.failure(IllegalStateException("não configurado")) else WorldRemote.stats(ctx)
        recordSupabase(ctx, r)
        return r
    }

    // ------------------------------------------------------------------ verificação REAL das fontes (requisição de teste)
    suspend fun verify(ctx: Context) = withContext(Dispatchers.IO) {
        val db = WorldDb.get(ctx); WorldSources.seed(db)
        checkSupabase(ctx, db)
        for (s in WorldSources.ALL) {
            val url = s.probe ?: continue
            if (!WorldHttp.allowed(url)) { db.w.execSQL("UPDATE football_sources SET notes=COALESCE(notes,'') WHERE id=?", arrayOf(s.id)); continue }
            val cur = db.query("SELECT status FROM football_sources WHERE id=?", s.id) { it.getString(0) }.firstOrNull()
            if (cur in setOf("desativada por licença", "sincronizando")) continue
            try {
                val key = if (s.id == WorldSources.APIF) Settings.get(ctx, PhotoWeb.KEY, "") else ""
                if (s.id == WorldSources.APIF && key.isBlank()) { WorldSources.status(db, s.id, "aguardando configuração"); continue }
                val headers = (if (key.isNotBlank()) mapOf("x-apisports-key" to key) else emptyMap()) + (if (url.endsWith(".csv")) mapOf("Range" to "bytes=0-0") else emptyMap())
                WorldHttp.get(url, headers = headers).use { r ->
                    db.bumpUsage(s.id, r.code)
                    val imported = db.scalar("SELECT imported FROM football_sources WHERE id=?", s.id) ?: 0L
                    val st = when {
                        r.code == 429 -> "limite atingido"
                        r.code in 200..299 || r.code == 206 -> if (imported > 0) "atualizada" else "operacional"
                        r.code in 400..499 && s.id == WorldSources.APIF -> "aguardando configuração"
                        else -> "indisponível"
                    }
                    WorldSources.status(db, s.id, st)
                    db.w.execSQL("UPDATE football_sources SET verified_at=? WHERE id=?", arrayOf(System.currentTimeMillis(), s.id))
                }
            } catch (e: Exception) { WorldSources.status(db, s.id, "indisponível"); db.w.execSQL("UPDATE football_sources SET errors=errors+1,verified_at=? WHERE id=?", arrayOf(System.currentTimeMillis(), s.id)) }
        }
        UiBus.bump()
    }
}
