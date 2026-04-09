package br.leg.senado.nusp.service;

import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;

import java.awt.Color;

/**
 * Configuracoes compartilhadas entre os services de relatorio (PDF e DOCX).
 * Equivale a report_config.py do Python.
 */
public final class ReportConfig {

    private ReportConfig() {}

    // ── Cores (hex) ────────────────────────────────────────────────
    public static final String HEADER_FILL         = "#dbeafe";
    public static final String HEADER_DETAIL_FILL  = "#bfdbfe";
    public static final String DETAIL_BAR_FILL     = "#e0f2fe";
    public static final String DATA_ROW_FILL       = "#f8fafc";
    public static final String GRID_COLOR          = "#cbd5e1";

    public static final String COLOR_GREEN = "#16a34a";
    public static final String COLOR_RED   = "#dc2626";
    public static final String COLOR_BLUE  = "#2563eb";
    public static final String COLOR_MUTED = "#64748b";
    public static final String COLOR_DARK  = "#0f172a";
    public static final String COLOR_SLATE = "#334155";

    // ── Pesos de coluna por relatorio ──────────────────────────────
    public static final int[] COLS_OPERADORES                = {60, 40};
    public static final int[] COLS_CHECKLISTS_MASTER         = {70, 60, 150, 45, 50, 60, 50};
    public static final int[] COLS_ANORMALIDADES             = {70, 60, 110, 170, 70, 60, 70};
    public static final int[] COLS_OPERACOES_SESSOES_MASTER  = {90, 60, 130, 45, 45, 45, 70};
    public static final int[] COLS_OPERACOES_SESSOES_ENT_NORMAL = {30, 120, 50, 50, 120, 50};
    public static final int[] COLS_OPERACOES_SESSOES_ENT_PLENARIO = {200, 200, 50};
    public static final int[] COLS_OPERACOES_ENTRADAS        = {80, 60, 110, 70, 170, 45, 45, 45, 70};
    public static final int[] COLS_MEUS_CHECKLISTS           = {180, 80, 100, 100};
    public static final int[] COLS_MINHAS_OPERACOES          = {150, 70, 90, 90, 80};

    // ── Helpers ────────────────────────────────────────────────────

    public static Color hex(String hex) {
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        return new Color(Integer.parseInt(h, 16));
    }

    public static float[] scaleWeights(int[] weights, float availableWidth) {
        int sum = 0;
        for (int w : weights) sum += w;
        float scale = availableWidth / sum;
        float[] result = new float[weights.length];
        for (int i = 0; i < weights.length; i++) {
            result[i] = weights[i] * scale;
        }
        return result;
    }

    /** Formata data para DD/MM/YYYY. Aceita LocalDate, String, null. */
    public static String fmtDate(Object v) {
        if (v == null || "".equals(v)) return "--";
        if (v instanceof java.time.LocalDate ld) return String.format("%02d/%02d/%04d", ld.getDayOfMonth(), ld.getMonthValue(), ld.getYear());
        if (v instanceof java.time.LocalDateTime ldt) return String.format("%02d/%02d/%04d", ldt.getDayOfMonth(), ldt.getMonthValue(), ldt.getYear());
        if (v instanceof java.util.Date d) { var c = java.util.Calendar.getInstance(); c.setTime(d); return String.format("%02d/%02d/%04d", c.get(java.util.Calendar.DAY_OF_MONTH), c.get(java.util.Calendar.MONTH)+1, c.get(java.util.Calendar.YEAR)); }
        // String "yyyy-MM-dd..." → dd/MM/yyyy
        String s = v.toString().trim();
        if (s.length() >= 10 && s.charAt(4) == '-' && s.charAt(7) == '-') {
            return s.substring(8, 10) + "/" + s.substring(5, 7) + "/" + s.substring(0, 4);
        }
        return s;
    }

    /** Formata hora para HH:MM. Aceita LocalTime, String "HH:MM:SS", null. */
    public static String fmtTime(Object v) {
        if (v == null || "".equals(v)) return "--";
        if (v instanceof java.time.LocalTime lt) return String.format("%02d:%02d", lt.getHour(), lt.getMinute());
        String s = v.toString();
        if (s.contains(":") && s.length() >= 5) return s.substring(0, 5);
        return s;
    }

    /** Cria Font OpenPDF com o tamanho e cor especificados. */
    public static Font pdfFont(float size, int style, Color color) {
        Font f = FontFactory.getFont(FontFactory.HELVETICA, size, style);
        if (color != null) f.setColor(color);
        return f;
    }
}
