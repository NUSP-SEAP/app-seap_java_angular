package br.leg.senado.nusp.service;

/**
 * Utilitários compartilhados para converter valores de queries nativas Oracle/JPA.
 * Evita duplicação dos helpers str(), num(), boolVal() em cada service.
 */
public final class NativeQueryUtils {

    private NativeQueryUtils() {}

    /** Converte Object para String, tratando CLOB do Oracle. */
    public static String str(Object o) {
        if (o == null) return null;
        if (o instanceof java.sql.Clob clob) {
            try { return clob.getSubString(1, (int) clob.length()); }
            catch (java.sql.SQLException e) { return ""; }
        }
        return o.toString();
    }

    /** Converte Object numérico para Long. */
    public static Long num(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }

    /** Converte NUMBER(1) do Oracle para boolean (0=false, 1=true). */
    public static boolean boolVal(Object o) {
        return o != null && ((Number) o).intValue() == 1;
    }
}
