package com.osmaicoach.collector

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CancellationException
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
    @Volatile var step: Int = 0; private set
    @Volatile var steps: Int = 0; private set
    val overall: Float? get() = if (running == null || steps == 0) null else ((step.toFloat() + (progress ?: 0f)) / steps).coerceIn(0f, 1f)

    /** Tarefas deixadas "sincronizando" por um app fechado no meio da importação: viram "interrompida" (a importação retoma do checkpoint). */
    fun healStale(db: WorldDb) {
        if (running != null) return
        db.w.execSQL("UPDATE football_sync_jobs SET status='interrompida', finished=COALESCE(finished,started), message=COALESCE(message,'')||' (o app foi fechado; retoma do ponto salvo)' WHERE status='sincronizando'")
        db.w.execSQL("UPDATE football_sources SET status=CASE WHEN last_sync IS NOT NULL THEN 'atualizada' WHEN imported>0 THEN 'operacional' ELSE 'aguardando sincronização' END WHERE status='sincronizando'")
    }

    private val failStreak = HashMap<String, Int>()
    private val blockedUntil = HashMap<String, Long>()

    fun pause() { pause.set(true) }
    fun resume(ctx: Context) { pause.set(false); requestRun(ctx) }
    val paused get() = pause.get()

    fun unmetered(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    private fun allowedNet(ctx: Context) = unmetered(ctx) || Settings.get(ctx, "world_mobile_data", "0") == "1"

    /** Ordem da fila (prioridade 8 = demais entidades; as de OCR/slots são resolvidas localmente e vêm antes, em [WorldOcr]). */
    class Task(val sourceId: String, val key: String, val url: String, val label: String, val intervalMs: Long, val make: (WorldDb, WorldSql, WorldImport.Countries.Index, String) -> WorldImport.Sink)
    private val TASKS = listOf(
        Task(WorldSources.OFB_CLUBS, "csv:ofb_clubs", WorldSources.URL_OFB_CLUBS, "Clubes do mundo (OpenFootballBase)", 7 * DAY) { _, s, ci, v -> WorldImport.OfbClubsSink(s, ci, v) },
        Task(WorldSources.REEP_COMP, "csv:reep_comp", WorldSources.URL_REEP_COMP, "Competições (Reep)", 30 * DAY) { _, s, ci, v -> WorldImport.ReepCompetitionsSink(s, ci, v) },
        Task(WorldSources.REEP_TEAMS, "csv:reep_teams", WorldSources.URL_REEP_TEAMS, "Clubes (Reep)", 30 * DAY) { _, s, ci, v -> WorldImport.ReepTeamsSink(s, ci, v) },
        Task(WorldSources.REEP_PEOPLE, "csv:reep_people", WorldSources.URL_REEP_PEOPLE, "Jogadores (Reep)", 30 * DAY) { _, s, ci, v -> WorldImport.ReepPeopleSink(s, ci, v) }
    )

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

    private fun due(db: WorldDb, t: Task, now: Long): Boolean {
        val cp = db.checkpoint(t.key)
        if (cp == null) return true
        if (cp.cursor != "done") return true                              // incompleta: retoma
        val last = db.scalar("SELECT last_sync FROM football_sources WHERE id=?", t.sourceId) ?: 0L
        return now - last >= t.intervalMs                                 // completa: só reconsulta (ETag) no intervalo
    }

    suspend fun runDue(ctx: Context, force: Boolean, only: String? = null) {
        if (!lock.tryLock()) return
        val db = WorldDb.get(ctx)
        try {
            WorldSources.seed(db); healStale(db)
            running = "Lendo o que o OSM já mostrou"; step = 0; steps = 4 + TASKS.size; progress = null; UiBus.bump()
            runCatching { WorldOsm.refresh(ctx, force = true) }                           // local: não depende de rede
            if (!allowedNet(ctx)) { lastMessage = "Aguardando Wi‑Fi (a importação grande só roda em rede sem limite de dados)."; return }
            ensureCountries(ctx, db)
            val now = System.currentTimeMillis()
            step = 1
            for (t in TASKS) {
                if (only != null && t.sourceId != only) continue
                if (!currentCoroutineContext().isActive || pause.get()) break
                if ((blockedUntil[t.sourceId] ?: 0L) > now) continue
                val src = db.query("SELECT enabled FROM football_sources WHERE id=?", t.sourceId) { it.getInt(0) }.firstOrNull() ?: 1
                if (src == 0) continue
                if (!force && !due(db, t, now)) continue
                runTask(ctx, db, t); step++
            }
            running = "Confederações (Wikidata)"; progress = null; UiBus.bump()
            if (only == null || only == WorldSources.WIKIDATA) runCatching { WorldWikidata.syncConfederations(ctx, db) }
            step++
            running = "Identificando o que o OSM mostrou"; UiBus.bump()
            runCatching { WorldOsm.refresh(ctx, force = true) }
            step++; running = "Bandeiras, escudos e fotos do OSM"; progress = null; UiBus.bump()
            runCatching { WorldOsm.prefetchImages(ctx) }
            runCatching { WorldCoverage.snapshot(db) }
            lastMessage = "Concluída: " + WorldOsm.lastReport
        } finally { running = null; progress = null; step = 0; steps = 0; lock.unlock(); UiBus.bump() }
    }

    private suspend fun currentCoroutineContext() = kotlin.coroutines.coroutineContext

    private fun ensureCountries(ctx: Context, db: WorldDb) {
        if (db.count("football_countries") > 200) return
        val (code, body) = WorldHttp.text(WorldSources.URL_FLAG_CODES)
        db.bumpUsage(WorldSources.FLAGS, code)
        if (code in 200..299 && body != null) { WorldImport.importCountries(AndroidSql(db.w), body); WorldSources.status(db, WorldSources.FLAGS, "operacional") }
    }

    private suspend fun runTask(ctx: Context, db: WorldDb, t: Task) {
        running = t.label; progress = null; counters = WorldImport.Counters(); lastMessage = "${t.label}: conectando…"; UiBus.bump()
        val now = System.currentTimeMillis()
        db.w.execSQL("INSERT INTO football_sync_jobs(source_id,kind,status,started,message) VALUES(?,?,?,?,?)", arrayOf(t.sourceId, "csv", "sincronizando", now, t.label))
        val jobId = db.scalar("SELECT last_insert_rowid()")!!
        WorldSources.status(db, t.sourceId, "sincronizando")
        val sql = AndroidSql(db.w)
        val ci = WorldImport.Countries.index(sql)
        val sink = t.make(db, sql, ci, "ofb-${java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())}")
        val c = counters
        var status = "atualizada"; var msg = ""
        try {
            val cp = db.checkpoint(t.key)
            val resume = cp != null && cp.cursor != "done" && cp.offset > 0 && cp.header != null
            val offset = if (resume) cp!!.offset else 0L
            val etag = if (!resume) cp?.etag else null
            WorldHttp.get(t.url, offset = offset, etag = etag).use { resp ->
                db.bumpUsage(t.sourceId, resp.code)
                if (resp.code == 304) { msg = "arquivo inalterado (ETag): nada baixado"; db.setCheckpoint(t.key, t.sourceId, cp?.offset ?: 0, "done", cp?.etag, cp?.lastModified, cp?.hash, cp?.total, cp?.header); return@use }
                if (!resp.ok) throw java.io.IOException("HTTP ${resp.code}")
                val total = if (resume) cp!!.total else resp.length.takeIf { it > 0 }
                val csv = WorldCsv(resp.stream!!, if (resp.code == 206) offset else 0L)
                val header: List<String> = if (resume && resp.code == 206) WorldCsv.clean(cp!!.header!!.split('\u0001')) else WorldCsv.clean(csv.readRecord() ?: throw java.io.IOException("arquivo vazio"))
                sink.header(header)
                val hdrStr = header.joinToString("\u0001")
                var inBatch = 0; sql.begin()
                try {
                    while (true) {
                        if (pause.get() || !currentCoroutineContext().isActive) { status = "pausada"; break }
                        val r = csv.readRecord() ?: break
                        if (r.isEmpty()) continue
                        try { sink.row(r, c) } catch (e: Exception) { c.failed++ }
                        if (++inBatch >= WorldImport.BATCH) {
                            sql.commit(); db.setCheckpoint(t.key, t.sourceId, csv.offset, null, resp.etag, resp.lastModified, null, total, hdrStr); sql.begin(); inBatch = 0
                            progress = total?.let { (csv.offset.toFloat() / it).coerceIn(0f, 1f) }
                            lastMessage = "${t.label}: ${c.analyzed} lidos"; UiBus.bump()
                            db.w.execSQL("UPDATE football_sync_jobs SET analyzed=?,inserted=?,updated=?,deduped=?,failed=?,total=? WHERE id=?", arrayOf(c.analyzed, c.inserted, c.updated, c.deduped, c.failed, total, jobId))
                        }
                    }
                    sql.commit()
                } catch (e: Exception) { runCatching { sql.rollback() }; throw e }
                val done = status != "pausada"
                db.setCheckpoint(t.key, t.sourceId, csv.offset, if (done) "done" else null, resp.etag, resp.lastModified, null, total, hdrStr)
                msg = "${c.analyzed} analisados, ${c.inserted} novos, ${c.deduped} duplicados"
            }
            failStreak[t.sourceId] = 0
            db.w.execSQL("UPDATE football_sources SET last_sync=?,next_sync=?,imported=imported+?,updated=updated+?,version=? WHERE id=?", arrayOf(System.currentTimeMillis(), System.currentTimeMillis() + t.intervalMs, c.inserted, c.updated + c.deduped, java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()), t.sourceId))
        } catch (e: CancellationException) { status = "pausada"; throw e
        } catch (e: Exception) {
            status = "erro"; msg = e.message ?: e.javaClass.simpleName
            val n = (failStreak[t.sourceId] ?: 0) + 1; failStreak[t.sourceId] = n
            db.w.execSQL("UPDATE football_sources SET errors=errors+1 WHERE id=?", arrayOf(t.sourceId))
            if (n >= 3) { blockedUntil[t.sourceId] = System.currentTimeMillis() + 6 * 3_600_000L; msg += " (disjuntor: nova tentativa em 6 h)" }
            else delay(minOf(60_000L, 2_000L shl n))                      // backoff exponencial
        } finally {
            sink.close()
            val st = when (status) { "atualizada" -> if (c.inserted + c.updated + c.deduped > 0 || msg.contains("inalterado")) "atualizada" else "operacional"; else -> status }
            WorldSources.status(db, t.sourceId, st)
            db.w.execSQL("UPDATE football_sync_jobs SET status=?,finished=?,analyzed=?,inserted=?,updated=?,deduped=?,failed=?,message=? WHERE id=?", arrayOf(status, System.currentTimeMillis(), c.analyzed, c.inserted, c.updated, c.deduped, c.failed, msg, jobId))
            lastMessage = "${t.label}: $msg"
        }
    }

    // ------------------------------------------------------------------ verificação REAL das fontes (requisição de teste)
    suspend fun verify(ctx: Context) = withContext(Dispatchers.IO) {
        val db = WorldDb.get(ctx); WorldSources.seed(db)
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
