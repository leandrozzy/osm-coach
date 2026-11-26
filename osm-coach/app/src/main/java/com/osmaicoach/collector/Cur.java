package com.osmaicoach.collector;

import java.util.List;

/** Linha de resultado com a mesma API de leitura do Cursor (tipos de plataforma: coluna nula vira null), para testar as consultas no JVM com SQLite de verdade. */
public final class Cur {
    private final List<String> r;
    public Cur(List<String> r) { this.r = r; }
    public String getString(int i) { return r.get(i); }
    public long getLong(int i) { String s = r.get(i); if (s == null) return 0L; try { return (long) Double.parseDouble(s); } catch (NumberFormatException e) { return 0L; } }
    public int getInt(int i) { return (int) getLong(i); }
    public boolean isNull(int i) { return r.get(i) == null; }
}
