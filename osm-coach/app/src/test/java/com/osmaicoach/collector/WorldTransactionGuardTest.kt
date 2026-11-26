package com.osmaicoach.collector

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Trava de regressão (25/11): transação do SQLite pertence a UMA thread. Chamada suspend (Room/DAO) ou rede DENTRO de begin()…commit() pode retomar em outra thread
 * e deixar o banco da Base preso para sempre (a sincronização ficava em "etapa 1, 0%" e o teste de conexão em "Testando…").
 */
class WorldTransactionGuardTest {
    private val forbidden = listOf("repo.dao", "dao.", "Atlas.load", "FlagManual", "WorldHttp.", "WorldRemote.get", "competition(ctx", "Thread.sleep", "runBlocking")
    @Test fun noSuspendOrNetworkInsideWorldTransactions() {
        val dir = listOf(File("src/main/java/com/osmaicoach/collector"), File("app/src/main/java/com/osmaicoach/collector")).first { it.exists() }
        for (f in dir.listFiles()!!.filter { it.name.startsWith("World") && it.name.endsWith(".kt") && it.name != "WorldClean.kt" }) {
            val lines = f.readLines(); var inTx = false
            for ((i, l) in lines.withIndex()) {
                if (l.contains(".begin()") && !l.contains("fun begin")) inTx = true
                else if (inTx && (l.contains(".commit()") || l.contains(".rollback()"))) inTx = false
                else if (inTx) for (bad in forbidden) assertFalse("${f.name}:${i + 1} usa '$bad' dentro de uma transação", l.contains(bad) && !l.trimStart().startsWith("//"))
            }
        }
    }

    /** O SQLite do Android NÃO tem JSON1 (json_extract): em 25/11 a Visão Geral ficou carregando para sempre porque os testes (sqlite-jdbc) têm JSON1 e o aparelho não. */
    @Test fun noJsonFunctionsInSqlSources() {
        val dir = listOf(File("src/main/java/com/osmaicoach/collector"), File("app/src/main/java/com/osmaicoach/collector")).first { it.exists() }
        for (f in dir.listFiles()!!.filter { it.name.endsWith(".kt") }) for ((i, l) in f.readLines().withIndex())
            assertFalse("${f.name}:${i + 1} usa função JSON do SQLite (não existe no Android)", l.contains("json_extract(") || l.contains("json_each(") || l.contains("json_object("))
    }
}
