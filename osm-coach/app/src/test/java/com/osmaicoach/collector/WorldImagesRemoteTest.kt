package com.osmaicoach.collector

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class WorldImagesRemoteTest {
    @Test fun onlyValidLocalImagesAreCandidatesForUpload() {
        val d = createTempDir("wfimg")
        try {
            fun f(path: String, size: Int) = File(d, path).apply { parentFile.mkdirs(); writeBytes(ByteArray(size) { 1 }) }
            f("flags/br.png", 3000); f("flags/gb-eng.png", 3000); f("flags/_miss.png", 3000); f("flags/BR UPPER.png", 3000)
            f("crests/web/vfbstuttgart.png", 20_000); f("crests/web/_miss.json", 50); f("crests/web/grande.png", 400_000); f("crests/web/pequeno.png", 10)
            f("photos/kylianmbappe.png", 30_000); f("photos/osm/recorte.png", 30_000)
            val items = WorldImagesRemote.localItems(d).map { it.id }.toSet()
            assertEquals(setOf("flag/br", "flag/gb-eng", "crest/vfbstuttgart", "photo/kylianmbappe"), items)      // sem "_", maiúsculas/espaço, tamanho fora de 100 B..300 KB, nem recortes do jogo (photos/osm)
            assertEquals("crests/vfbstuttgart.png", WorldImagesRemote.localItems(d).first { it.kind == "crest" }.path)
        } finally { d.deleteRecursively() }
    }
}
