package com.osmaicoach.collector

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Base Mundial — banco próprio `world_football.db` (ver [WorldSchema]). Acesso simples por SQL; leituras paginadas no servidor/SQLite, nunca a base inteira na memória. */
class WorldDb private constructor(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "world_football.db", null, WorldSchema.VERSION) {
    override fun onCreate(db: SQLiteDatabase) { WorldSchema.DDL.forEach { db.execSQL(it) } }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) { WorldSchema.DDL.forEach { db.execSQL(it) } }   // só aditivo
    override fun onConfigure(db: SQLiteDatabase) { db.enableWriteAheadLogging() }

    val w: SQLiteDatabase get() = writableDatabase

    fun scalar(sql: String, vararg args: String): Long? = readableDatabase.rawQuery(sql, args).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
    fun count(table: String, where: String? = null, vararg args: String): Long = scalar("SELECT COUNT(*) FROM $table" + (where?.let { " WHERE $it" } ?: ""), *args) ?: 0L
    fun <T> query(sql: String, vararg args: String, map: (Cursor) -> T): List<T> = readableDatabase.rawQuery(sql, args).use { c -> val out = ArrayList<T>(); while (c.moveToNext()) out.add(map(c)); out }
    fun fileSize(ctx: Context): Long = ctx.getDatabasePath("world_football.db").let { f -> f.length() + java.io.File(f.path + "-wal").length() }

    fun now() = System.currentTimeMillis()

    // ------------------------------------------------------------------ fontes e checkpoints
    fun setCheckpoint(key: String, source: String, offset: Long, cursor: String? = null, etag: String? = null, lastModified: String? = null, hash: String? = null, total: Long? = null, header: String? = null) {
        val cv = ContentValues().apply {
            put("job_key", key); put("source_id", source); put("byte_offset", offset); put("cursor", cursor); put("etag", etag); put("last_modified", lastModified); put("hash", hash); put("total", total); put("header", header); put("updated_at", now())
        }
        w.insertWithOnConflict("football_sync_checkpoints", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }
    class Checkpoint(val offset: Long, val cursor: String?, val etag: String?, val lastModified: String?, val hash: String?, val total: Long?, val header: String?)
    fun checkpoint(key: String): Checkpoint? = query("SELECT byte_offset,cursor,etag,last_modified,hash,total,header FROM football_sync_checkpoints WHERE job_key=?", key) { c ->
        Checkpoint(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), if (c.isNull(5)) null else c.getLong(5), c.getString(6))
    }.firstOrNull()

    fun bumpUsage(source: String, status: Int, limit: Int? = null) {
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        w.execSQL("INSERT INTO football_api_usage(source_id,day,calls,limit_hint,last_status,last_at) VALUES(?,?,1,?,?,?) ON CONFLICT(source_id,day) DO UPDATE SET calls=calls+1,last_status=excluded.last_status,last_at=excluded.last_at", arrayOf(source, day, limit, status, now()))
    }
    fun callsToday(source: String): Long {
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        return scalar("SELECT calls FROM football_api_usage WHERE source_id=? AND day=?", source, day) ?: 0L
    }

    fun metric(name: String, scope: String = "", value: Long) { w.execSQL("INSERT INTO football_coverage_metrics(at,metric,scope,value) VALUES(?,?,?,?)", arrayOf(now(), name, scope, value)) }

    companion object {
        @Volatile private var inst: WorldDb? = null
        fun get(ctx: Context): WorldDb = inst ?: synchronized(this) { inst ?: WorldDb(ctx).also { inst = it } }
    }
}
