package com.osmaicoach.collector

import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase

/** Acesso SQL mínimo usado pelos importadores: no aparelho é o SQLite do Android; nos testes, SQLite de verdade via JDBC (mesmo código, dados reais). */
interface WorldSql {
    /** Executa e devolve quantas linhas mudaram. */
    fun exec(sql: String, args: Array<out Any?> = emptyArray()): Int
    fun long(sql: String, vararg a: String): Long?
    fun rows(sql: String, vararg a: String): List<List<String?>>
    fun begin(); fun commit(); fun rollback()
}

class AndroidSql(private val db: SQLiteDatabase) : WorldSql {
    override fun exec(sql: String, args: Array<out Any?>): Int {
        db.execSQL(sql, args as Array<Any?>)
        return DatabaseUtils.longForQuery(db, "SELECT changes()", null).toInt()
    }
    override fun long(sql: String, vararg a: String): Long? = db.rawQuery(sql, a).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
    override fun rows(sql: String, vararg a: String): List<List<String?>> = db.rawQuery(sql, a).use { c -> val out = ArrayList<List<String?>>(); while (c.moveToNext()) out.add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) }); out }
    override fun begin() = db.beginTransaction()
    override fun commit() { db.setTransactionSuccessful(); db.endTransaction() }
    override fun rollback() = db.endTransaction()
}
