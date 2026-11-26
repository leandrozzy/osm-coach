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

    class Entry(val key: String, val name: String, val source: String, val variants: List<String> = emptyList())   // source: manual | web | recorte | nenhum | bloqueado

    /** Forma comparável de um nome lido por OCR: letras que o OCR troca (I/l/1/|, 0/O, 5/S, 8/B, rn/m) viram uma só. */
    internal fun look(name: String): String =
        Txt.norm(name.replace('|', 'i').replace('!', 'i')).replace("rn", "m").map { c -> when (c) { 'l', '1', '|', '!' -> 'i'; '0' -> 'o'; '5' -> 's'; '8' -> 'b'; else -> c } }.filter { it.isLetterOrDigit() }.joinToString("")

    /** Mesmo clube lido de dois jeitos? Igual depois de [look], ou ≥ 0,92 parecido com o MESMO sufixo de numeral (Stuttgart II ≠ Stuttgart III: são clubes diferentes). */
    internal fun sameReading(a: String, b: String): Boolean {
        val la = look(a); val lb = look(b)
        if (la.length < 3 || lb.length < 3) return false
        if (la == lb) return true
        fun suffix(s: String) = s.takeLastWhile { it == 'i' }.length
        return suffix(la) == suffix(lb) && Txt.sim(la, lb) >= 0.92
    }

    /** O OCR perdeu um traço do numeral ("Hoffenheim I" no lugar de "Hoffenheim II", diagnóstico 25/11): o nome da Classificação termina em II e o lido é igual com UM I só. */
    internal fun droppedStroke(trusted: String, read: String): Boolean {
        val a = look(trusted); val b = look(read)
        return a.length >= 6 && a.endsWith("ii") && !a.endsWith("iii") && b == a.dropLast(1)
    }

    /** Agrupa leituras do mesmo clube (união de nomes parecidos). */
    internal fun cluster(names: List<String>): List<List<String>> {
        val groups = ArrayList<MutableList<String>>()
        for (n in names) {
            val hits = groups.filter { g -> g.any { sameReading(it, n) } }
            if (hits.isEmpty()) groups.add(mutableListOf(n))
            else { hits.first().add(n); for (extra in hits.drop(1)) { hits.first().addAll(extra); groups.remove(extra) } }
        }
        return groups
    }

    private fun garbage(n: String) = n.count { !(it.isLetter() || it == ' ' || it == '.' || it == '-' || it == '\'' || it == '(' || it == ')') } + (if (n.any { it.isDigit() } && !n.any { it.isLetter() }) 5 else 0)

    /**
     * Todos os escudos guardados + os clubes conhecidos (calendário, tabelas, rivais). Leituras duplicadas do mesmo clube viram UMA linha:
     * o nome mostrado é o da Classificação (`trusted`), senão o que tem escudo, senão o mais "limpo". `source` usa a MESMA busca do desenho (nome parecido conta).
     */
    fun entries(fd: File, known: Collection<String>, trusted: Set<String> = emptySet()): List<Entry> {
        val names = LinkedHashMap<String, String>()                       // chave → nome bonito
        for (n in known) { val k = Txt.key(n); if (k.length >= 3 && !Fixtures.notAClub(n)) names.putIfAbsent(k, n.trim()) }
        val web = CrestWeb.dir(fd).listFiles()?.filter { it.name.endsWith(".png") }?.map { it.name.removeSuffix(".png") } ?: emptyList()
        val cut = CrestCut.dir(fd).listFiles()?.filter { it.name.endsWith(".png") }?.map { it.name.removeSuffix(".png") } ?: emptyList()
        val blocked = readSet(blockedFile(fd)); val manual = readSet(manualFile(fd))
        for (k in web + cut) names.putIfAbsent(k, k.split(' ', '_').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } })
        for (k in blocked) names.putIfAbsent(k, k)
        val trustedLook = trusted.map { look(it) }.toSet()
        val out = ArrayList<Entry>()
        for (g in cluster(names.values.toList())) {
            val isBlockedG = g.all { isBlocked(fd, it) }
            val pick = g.firstOrNull { it in trusted } ?: g.firstOrNull { look(it) in trustedLook }
                ?: g.firstOrNull { CrestWeb.find(fd, it) != null || CrestCut.find(fd, it) != null }
                ?: g.minWithOrNull(compareBy({ garbage(it) }, { -it.length })) ?: g.first()
            val k = Txt.key(pick)
            val src = when {
                isBlockedG -> "bloqueado"
                CrestWeb.find(fd, pick) != null -> if (g.any { Txt.key(it) in manual }) "manual" else "web"
                CrestCut.find(fd, pick) != null -> "recorte"
                else -> "nenhum"
            }
            out.add(Entry(k, pick, src, g.filter { it != pick }))
        }
        return out.sortedBy { Txt.norm(it.name) }
    }

    /** Exclui as leituras duplicadas/erradas de cada clube (fica só a do nome mostrado). Devolve quantas. */
    fun removeDuplicates(ctx: Context, entries: List<Entry>): Int {
        var n = 0
        for (e in entries) for (v in e.variants) if (Txt.key(v) != e.key) { delete(ctx, v); n++ }
        return n
    }

    private val snapCache = HashMap<String, String>()
    @Volatile private var snapStamp = -1L
    private fun trustedFile(fd: File) = File(File(fd, "crests").apply { mkdirs() }, "trusted.json")
    fun rememberTrusted(ctx: Context, clubs: Collection<String>) {
        val cur = readSet(trustedFile(ctx.filesDir)); val before = cur.size
        for (c in clubs) if (c.trim().length >= 3) cur.add(c.trim())
        if (cur.size != before) writeSet(trustedFile(ctx.filesDir), cur)
    }
    /** Nome "oficial" para um nome lido por OCR: o da Classificação (ou de um escudo já guardado) se for o mesmo clube; senão o próprio. Evita criar escudo duplicado por erro de leitura. */
    fun snap(filesDir: File, team: String?): String? {
        val t = team?.trim() ?: return null
        if (t.length < 3) return t
        val tf = trustedFile(filesDir)
        val stamp = tf.lastModified() + tf.length()
        synchronized(snapCache) {
            if (snapStamp != stamp) { snapCache.clear(); snapStamp = stamp }
            snapCache[t]?.let { return it }
        }
        val trusted = readSet(tf)
        val r = trusted.firstOrNull { sameReading(it, t) } ?: trusted.firstOrNull { droppedStroke(it, t) } ?: t
        synchronized(snapCache) { snapCache[t] = r }
        return r
    }

    /** Nomes dos clubes da Classificação lida (grafia limpa, letra grande): vale como o nome certo do clube. */
    suspend fun trustedNames(repo: Repo): Set<String> {
        val out = LinkedHashSet<String>()
        for (s in 1..4) runCatching { League.table(repo.dao.plan(s, "league")?.json?.let { JSONObject(it) }, "st").forEach { out.add(it.club) } }
        return out
    }

    /** Nomes de clubes já lidos nos 4 slots (adversários do calendário, tabela da Classificação, meu time e rival). */
    suspend fun knownNames(repo: Repo, ctx: Context? = null): List<String> {
        val out = LinkedHashSet<String>()
        for (s in 1..4) runCatching {
            repo.dao.matchesOf(s).forEach { m -> Fixtures.opponent(m)?.let { out.add(it) } }
            val f = repo.fieldMap(s)
            f[K.TEAM]?.value?.takeIf { FieldMerge.known(it) }?.let { out.add(it) }
            f[K.RIVAL_TEAM]?.value?.takeIf { FieldMerge.known(it) }?.let { out.add(it) }
            League.table(repo.dao.plan(s, "league")?.json?.let { JSONObject(it) }, "st").forEach { out.add(it.club) }
        }
        // clubes que o OCR viu em QUALQUER tela (topo do calendário, classificação, jogos) e não estavam nas listas acima
        if (ctx != null) runCatching { WorldDb.get(ctx).query("SELECT DISTINCT raw FROM football_ocr_observations o WHERE kind='club' AND NOT EXISTS(SELECT 1 FROM football_ignored ig WHERE ig.kind='club' AND ig.norm=o.norm) LIMIT 800") { it.getString(0) }.forEach { n -> if (!Fixtures.notAClub(n)) out.add(n) } }
        return out.toList()
    }
}
