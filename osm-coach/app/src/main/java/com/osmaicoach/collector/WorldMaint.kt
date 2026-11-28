package com.osmaicoach.collector

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.ConcurrentHashMap

/**
 * Manutenções ÚNICAS da cópia local (limpar cache automático, reidentificar…), feitas de forma SEGURA:
 *  - a conclusão só é gravada DEPOIS de o passo terminar com sucesso (fechar o app ou falhar no meio = o passo roda de novo, nunca fica "feito" pela metade);
 *  - cada passo é idempotente e, quando mexe no SQLite, roda numa transação (tudo ou nada);
 *  - erro vira aviso RECUPERÁVEL na faixa do topo ("tento de novo na próxima sincronização"), sem derrubar a sincronização nem travar a navegação;
 *  - só apaga caches automáticos; slots, elencos, partidas, histórico, preferências, artes e confirmações manuais não são tocados.
 */
object WorldMaint {
    /** Aviso recuperável da última manutenção que falhou (null = nenhum). */
    var error by mutableStateOf<String?>(null)
    private val running = ConcurrentHashMap.newKeySet<String>()

    /** Roda [block] se [key] ainda não foi concluído; grava a conclusão só se [block] não lançar. Devolve true se está concluído ao final. */
    /** Núcleo testável: [get]/[put] guardam a conclusão; só grava "1" se [block] terminar sem lançar. */
    internal fun onceWith(get: (String) -> String, put: (String, String) -> Unit, key: String, block: () -> Unit): Boolean {
        if (get(key) == "1") return true
        block()
        put(key, "1")
        return true
    }

    fun once(ctx: Context, key: String, label: String, block: () -> Unit): Boolean {
        if (Settings.get(ctx, key, "") == "1") return true
        if (!running.add(key)) return false                    // já rodando (tick de 1 min / outra sincronização)
        Live.on("maint:$key", "Base: $label")
        return try {
            onceWith({ Settings.get(ctx, it, "") }, { k, v -> Settings.put(ctx, k, v) }, key, block)
            if (error?.startsWith(label) == true) error = null
            true
        } catch (e: Exception) {
            error = "$label falhou (${e.javaClass.simpleName}); tento de novo na próxima sincronização. Seus dados continuam como estavam."
            Diag.log("Base: $error")
            false
        } finally { running.remove(key); Live.off("maint:$key") }
    }

    /** Executa [block] numa transação do [db]: tudo ou nada. */
    fun <T> atomic(db: WorldSql, block: () -> T): T {
        db.begin()
        try { val r = block(); db.commit(); return r } catch (e: Exception) { runCatching { db.rollback() }; throw e }
    }
}
