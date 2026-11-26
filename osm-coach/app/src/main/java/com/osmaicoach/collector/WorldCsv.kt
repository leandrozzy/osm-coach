package com.osmaicoach.collector

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Leitor CSV em fluxo (RFC 4180: aspas, vírgulas e quebras de linha dentro de aspas), com POSIÇÃO EM BYTES do fim de cada registro: é o ponto de retomada
 * do checkpoint (a importação continua com `Range: bytes=<posição>-`). Memória constante, qualquer tamanho de arquivo. UTF-8; ignora BOM.
 */
class WorldCsv(private val input: InputStream, startOffset: Long = 0L) {
    /** Bytes consumidos desde o início do ARQUIVO (inclui `startOffset`). */
    var offset: Long = startOffset
        private set
    private val buf = ByteArray(1 shl 16)
    private var pos = 0
    private var len = 0

    private fun next(): Int {
        if (pos >= len) { len = input.read(buf); pos = 0; if (len <= 0) { len = 0; return -1 } }
        return buf[pos++].toInt() and 0xFF
    }

    /** Próximo registro (lista de campos) ou null no fim do arquivo. Linha em branco devolve lista vazia. */
    fun readRecord(): List<String>? {
        val rec = ByteArrayOutputStream(256)
        var inQ = false; var any = false; var read = 0L
        val fields = ArrayList<String>()
        var cur = ByteArrayOutputStream(64)
        fun endField() { fields.add(cur.toString(Charsets.UTF_8.name())); cur = ByteArrayOutputStream(64) }
        while (true) {
            val b = next()
            if (b == -1) { if (!any) return null; offset += read; endField(); return fields }
            read++; any = true
            if (inQ) {
                if (b == '"'.code) {
                    val n = next()
                    if (n == '"'.code) { read++; cur.write('"'.code) }
                    else { inQ = false; if (n != -1) pos-- }   // devolve o byte lido (não faz parte do campo)
                } else cur.write(b)
            } else when (b) {
                '"'.code -> if (cur.size() == 0) inQ = true else cur.write(b)
                ','.code -> endField()
                '\r'.code -> { /* ignora; o \n fecha */ }
                '\n'.code -> { offset += read; endField(); return if (fields.size == 1 && fields[0].isEmpty()) emptyList() else fields }
                else -> cur.write(b)
            }
        }
    }

    companion object {
        /** Remove o BOM do primeiro campo do cabeçalho, se houver. */
        fun clean(h: List<String>) = if (h.isNotEmpty() && h[0].startsWith("\uFEFF")) listOf(h[0].removePrefix("\uFEFF")) + h.drop(1) else h
    }
}
