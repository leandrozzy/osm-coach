package com.osmaicoach.collector

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Gestão dos escudos (Ajustes → Imagens → Escudos dos clubes), como a das fotos dos jogadores:
 *  - EXCLUIR um escudo errado/inventado (ex.: "25 anniversary", nome que o OCR leu da faixa do jogo) e BLOQUEAR o nome, para o app não recriá-lo;
 *  - ADICIONAR/TROCAR à mão (imagem da galeria): fica em `crests/web/<nome>.png` e passa na frente de qualquer busca automática.
 * Chave de tudo = `Txt.key(nome)` (minúsculo, sem acento).
 */
object CrestAdmin {
    private fun blockedFile(fd: File) = File(File(fd, "crests").apply { mkdirs() }, "blocked.json")
    private fun manualFile(fd: File) = File(File(fd, "crests").apply { mkdirs() }, "manual.json")
    private val cache = HashMap<String, Pair<Long, Set<String>>>()
    private fun readSet(f: File): MutableSet<String> {
        // chamado a cada escudo desenhado: guarda em memória enquanto o arquivo não muda
        val lm = f.lastModified(); val len = f.length()
        synchronized(cache) { cache[f.path]?.let { if (it.first == lm + len) return it.second.toMutableSet() } }
        val s = runCatching { JSONObject(f.readText()).keys().asSequence().toMutableSet() }.getOrDefault(mutableSetOf())
        synchronized(cache) { cache[f.path] = Pair(lm + len, s.toSet()) }
        return s
    }
    private fun writeSet(f: File, s: Set<String>) { val o = JSONObject(); s.forEach { o.put(it, System.currentTimeMillis()) }; f.writeText(o.toString()); synchronized(cache) { cache.remove(f.path) } }

    /** Palavras que nunca são clube (texto de faixa/banner lido como nome): "25 anniversary", "OSM", só números. */
    private val NOT_CLUB = Regex("anniversary|aniversario|^osm$|^\\d+$|^[a-z]$")

    /** Nome sem clube de verdade ou excluído pelo usuário: o app não busca nem grava escudo para ele. */
    fun isBlocked(filesDir: File, team: String?): Boolean {
        val k = Txt.key(team ?: "")
        if (k.isEmpty()) return true
        if (NOT_CLUB.containsMatchIn(Txt.norm(team ?: "").trim()) || NOT_CLUB.containsMatchIn(k)) return true
        return k in readSet(blockedFile(filesDir))
    }

    fun isManual(filesDir: File, team: String?): Boolean = Txt.key(team ?: "") in readSet(manualFile(filesDir))

    /** Apaga o escudo do clube (web, recorte do OSM e manual) e bloqueia o nome. */
    fun delete(ctx: Context, team: String) {
        val fd = ctx.filesDir; val k = Txt.key(team)
        if (k.isEmpty()) return
        File(CrestWeb.dir(fd), "$k.png").delete()
        CrestCut.file(fd, team)?.delete()
        writeSet(manualFile(fd), readSet(manualFile(fd)).also { it.remove(k) })
        writeSet(blockedFile(fd), readSet(blockedFile(fd)).also { it.add(k) })
        UiBus.bump()
    }

    /** Desfaz o bloqueio de um nome (voltar a buscar o escudo sozinho). */
    fun unblock(ctx: Context, team: String) {
        val fd = ctx.filesDir; val k = Txt.key(team)
        writeSet(blockedFile(fd), readSet(blockedFile(fd)).also { it.remove(k) })
        CrestWeb.clearMisses(fd)
        UiBus.bump()
    }

    /** Escudo escolhido à mão: redimensiona (máx. 256 px), grava como PNG e passa na frente da busca automática. */
    fun setManual(ctx: Context, team: String, bmp: Bitmap): Boolean {
        val fd = ctx.filesDir; val k = Txt.key(team)
        if (k.length < 3) return false
        return runCatching {
            val s = minOf(1f, 256f / maxOf(bmp.width, bmp.height))
            val out = if (s < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(8), (bmp.height * s).toInt().coerceAtLeast(8), true) else bmp
            val f = File(CrestWeb.dir(fd), "$k.png")
            FileOutputStream(f).use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(CrestCut.dir(fd), "$k.png").delete()            // o recorte antigo (talvez errado) sai: vale o escolhido
            writeSet(blockedFile(fd), readSet(blockedFile(fd)).also { it.remove(k) })
            writeSet(manualFile(fd), readSet(manualFile(fd)).also { it.add(k) })
            UiBus.bump(); true
        }.getOrDefault(false)
    }

    class Entry(val key: String, val name: String, val source: String)   // source: manual | web | recorte | nenhum | bloqueado

    /** Todos os escudos guardados + os clubes conhecidos (calendário, tabelas, rivais) que ainda não têm escudo; `known` = nomes de clubes lidos. */
    fun entries(fd: File, known: Collection<String>): List<Entry> {
        val names = LinkedHashMap<String, String>()                       // chave → nome bonito
        for (n in known) { val k = Txt.key(n); if (k.length >= 3 && !Fixtures.notAClub(n)) names.putIfAbsent(k, n.trim()) }
        val web = CrestWeb.dir(fd).listFiles()?.filter { it.name.endsWith(".png") }?.map { it.name.removeSuffix(".png") }?.toSet() ?: emptySet()
        val cut = CrestCut.dir(fd).listFiles()?.filter { it.name.endsWith(".png") }?.map { it.name.removeSuffix(".png") }?.toSet() ?: emptySet()
        val blocked = readSet(blockedFile(fd)); val manual = readSet(manualFile(fd))
        for (k in web + cut) names.putIfAbsent(k, k.split(' ', '_').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } })
        for (k in blocked) names.putIfAbsent(k, k)
        return names.map { (k, n) ->
            val src = when { k in blocked || isBlocked(fd, n) -> "bloqueado"; k in manual && k in web -> "manual"; k in web -> "web"; k in cut -> "recorte"; else -> "nenhum" }
            Entry(k, n, src)
        }.sortedBy { Txt.norm(it.name) }
    }

    /** Nomes de clubes já lidos nos 4 slots (adversários do calendário, tabela da Classificação, meu time e rival). */
    suspend fun knownNames(repo: Repo): List<String> {
        val out = LinkedHashSet<String>()
        for (s in 1..4) runCatching {
            repo.dao.matchesOf(s).forEach { m -> Fixtures.opponent(m)?.let { out.add(it) } }
            val f = repo.fieldMap(s)
            f[K.TEAM]?.value?.takeIf { FieldMerge.known(it) }?.let { out.add(it) }
            f[K.RIVAL_TEAM]?.value?.takeIf { FieldMerge.known(it) }?.let { out.add(it) }
            League.table(repo.dao.plan(s, "league")?.json?.let { JSONObject(it) }, "st").forEach { out.add(it.club) }
        }
        return out.toList()
    }
}
