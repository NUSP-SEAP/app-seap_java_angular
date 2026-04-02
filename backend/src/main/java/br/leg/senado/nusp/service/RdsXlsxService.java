package br.leg.senado.nusp.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * Gera o RDS (Registro Diario de Sessoes) em XLSX usando Apache POI.
 * Equivale a rds_xlsx_service.py do Python (openpyxl).
 *
 * O template Modelo.xlsx tem 31 sheets (01..31), um para cada dia do mes.
 * Cada sheet tem header fixo + corpo de dados (linhas 11-40).
 */
@Service
public class RdsXlsxService {

    private static final Logger log = LoggerFactory.getLogger(RdsXlsxService.class);

    private static final String[] PT_BR_WEEKDAYS = {
            "segunda-feira", "terça-feira", "quarta-feira", "quinta-feira",
            "sexta-feira", "sábado", "domingo"
    };
    private static final String[] PT_BR_MONTHS = {
            "janeiro", "fevereiro", "março", "abril", "maio", "junho",
            "julho", "agosto", "setembro", "outubro", "novembro", "dezembro"
    };

    private static final int START_ROW = 10; // row index 10 = linha 11 do Excel
    private static final int BASE_END_ROW = 39; // row index 39 = linha 40

    public byte[] gerarRdsXlsx(int ano, int mes, List<Map<String, Object>> rawRows) {
        try {
            ClassPathResource res = new ClassPathResource("assets/Modelo.xlsx");
            Workbook wb = new XSSFWorkbook(res.getInputStream());

            int lastDay = YearMonth.of(ano, mes).lengthOfMonth();
            Map<Integer, List<Map<String, Object>>> linesByDay = buildLinesByDay(rawRows);

            for (int d = 1; d <= 31; d++) {
                String sheetName = String.format("%02d", d);
                Sheet ws = wb.getSheet(sheetName);
                if (ws == null) continue;

                clearBody(ws);

                if (d > lastDay) {
                    // Dia inexistente (ex: 31 em abril)
                    setCellValue(ws, 6, 0, null); // A7
                    continue;
                }

                List<Map<String, Object>> lines = linesByDay.getOrDefault(d, List.of());

                if (lines.isEmpty()) {
                    setCellValue(ws, 6, 0, null); // A7
                    continue;
                }

                // Preencher A7 com data extenso
                LocalDate date = LocalDate.of(ano, mes, d);
                setCellValue(ws, 6, 0, formatDataExtenso(date));

                ensureRows(ws, lines.size());

                for (int idx = 0; idx < lines.size(); idx++) {
                    int row = START_ROW + idx;
                    Map<String, Object> line = lines.get(idx);

                    setCellValue(ws, row, 0, objStr(line, "sala_nome"));        // A
                    setCellValue(ws, row, 1, "SGM");                            // B
                    setCellValue(ws, row, 2, objStr(line, "atividade_legislativa")); // C
                    setCellValue(ws, row, 3, objStr(line, "nome_evento"));       // D
                    setCellValue(ws, row, 4, objStr(line, "horario_pauta"));     // E
                    setCellValue(ws, row, 5, null);                              // F
                    setCellValue(ws, row, 6, objStr(line, "horario_inicio"));    // G
                    setCellValue(ws, row, 7, objStr(line, "horario_termino"));   // H
                    setCellValue(ws, row, 8, objStr(line, "op1"));               // I
                    setCellValue(ws, row, 9, objStr(line, "op2"));               // J
                    setCellValue(ws, row, 10, objStr(line, "op3"));              // K
                    setCellValue(ws, row, 11, objStr(line, "obs"));              // L
                }
            }

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            wb.write(buf);
            wb.close();
            return buf.toByteArray();
        } catch (Exception e) {
            log.error("Erro ao gerar RDS XLSX: {}", e.getMessage(), e);
            throw new RuntimeException("Erro ao gerar RDS XLSX", e);
        }
    }

    // ══ Construção de linhas por dia ════════════════════════════

    @SuppressWarnings("unchecked")
    private Map<Integer, List<Map<String, Object>>> buildLinesByDay(List<Map<String, Object>> rows) {
        // 1. Agrupar por registro_id (sessão)
        Map<Integer, Map<String, Object>> sessions = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            int rid = intVal(r, "registro_id");
            Map<String, Object> sess = sessions.computeIfAbsent(rid, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("registro_id", rid);
                m.put("data", r.get("data"));
                m.put("sala_nome", r.get("sala_nome"));
                m.put("em_aberto", boolVal(r, "em_aberto"));
                m.put("rows", new ArrayList<Map<String, Object>>());
                return m;
            });
            ((List<Map<String, Object>>) sess.get("rows")).add(r);
        }

        Map<Integer, List<Map<String, Object>>> linesByDay = new LinkedHashMap<>();

        for (Map<String, Object> sess : sessions.values()) {
            List<Map<String, Object>> rawRows = (List<Map<String, Object>>) sess.get("rows");

            // Colapsar por ordem (pega a última por seq + entrada_id)
            Map<Integer, Map<String, Object>> byOrdem = new LinkedHashMap<>();
            for (Map<String, Object> rr : rawRows) {
                int ordem = intVal(rr, "ordem");
                Map<String, Object> cur = byOrdem.get(ordem);
                int rrSeq = intVal(rr, "seq");
                int rrEntId = intVal(rr, "entrada_id");
                if (cur == null) {
                    byOrdem.put(ordem, rr);
                } else {
                    int curSeq = intVal(cur, "seq");
                    int curEntId = intVal(cur, "entrada_id");
                    if (rrSeq > curSeq || (rrSeq == curSeq && rrEntId > curEntId)) {
                        byOrdem.put(ordem, rr);
                    }
                }
            }

            if (byOrdem.isEmpty()) continue;

            // FIM por sessão
            String fim = null;
            for (Map<String, Object> rr : rawRows) {
                Object ht = rr.get("horario_termino");
                if (ht != null && !ht.toString().isEmpty()) {
                    if (fim == null || ht.toString().compareTo(fim) > 0) fim = ht.toString();
                }
            }

            boolean isOpen = boolVal(sess, "em_aberto") || fim == null;
            String fimOut = isOpen ? null : fim;
            String obs = isOpen ? "Evento não encerrado" : "";

            int maxOrdem = byOrdem.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
            int numGroups = (maxOrdem + 2) / 3;

            for (int g = 0; g < numGroups; g++) {
                int start = g * 3 + 1;
                List<Map<String, Object>> groupEntries = new ArrayList<>();
                for (int o = start; o < start + 3; o++) {
                    if (byOrdem.containsKey(o)) groupEntries.add(byOrdem.get(o));
                }
                if (groupEntries.isEmpty()) continue;

                groupEntries.sort(Comparator.comparingInt(e -> intVal(e, "ordem")));

                String comissaoNome = chooseValue(groupEntries, "comissao_nome");
                String atividade = null;
                if (comissaoNome != null && comissaoNome.contains("-")) {
                    atividade = comissaoNome.split("-")[0].trim();
                } else if (comissaoNome != null) {
                    atividade = comissaoNome.trim();
                }

                Map<String, Object> line = new LinkedHashMap<>();
                line.put("registro_id", sess.get("registro_id"));
                line.put("group_index", g);
                line.put("data", sess.get("data"));
                line.put("sala_nome", sess.get("sala_nome"));
                line.put("atividade_legislativa", atividade);
                line.put("nome_evento", chooseValue(groupEntries, "nome_evento"));
                line.put("horario_pauta", chooseValue(groupEntries, "horario_pauta"));
                line.put("horario_inicio", chooseValue(groupEntries, "horario_inicio"));
                line.put("horario_termino", fimOut);
                line.put("op1", objStr(byOrdem.getOrDefault(start, Map.of()), "operador_nome_exibicao"));
                line.put("op2", objStr(byOrdem.getOrDefault(start + 1, Map.of()), "operador_nome_exibicao"));
                line.put("op3", objStr(byOrdem.getOrDefault(start + 2, Map.of()), "operador_nome_exibicao"));
                line.put("obs", obs);

                Object dataObj = sess.get("data");
                int day = extractDay(dataObj);
                linesByDay.computeIfAbsent(day, k -> new ArrayList<>()).add(line);
            }
        }

        // Ordenar dentro do dia
        for (List<Map<String, Object>> lines : linesByDay.values()) {
            lines.sort(Comparator
                    .comparing((Map<String, Object> l) -> strForSort(l, "horario_pauta"))
                    .thenComparing(l -> strForSort(l, "horario_inicio"))
                    .thenComparing(l -> objStr(l, "sala_nome"))
                    .thenComparingInt(l -> intVal(l, "registro_id"))
                    .thenComparingInt(l -> intVal(l, "group_index")));
        }

        return linesByDay;
    }

    // ══ choose_value — regra de divergência ═════════════════════

    private String chooseValue(List<Map<String, Object>> entries, String field) {
        List<String> nonEmpty = new ArrayList<>();
        for (Map<String, Object> e : entries) {
            Object v = e.get(field);
            if (v != null && !v.toString().trim().isEmpty()) nonEmpty.add(v.toString());
        }

        Set<String> uniq = new LinkedHashSet<>(nonEmpty);

        if (uniq.size() <= 1) {
            // Sem divergência: pega a primeira (menor ordem)
            for (Map<String, Object> e : entries) {
                Object v = e.get(field);
                if (v != null && !v.toString().trim().isEmpty()) return v.toString();
            }
            return null;
        }

        // Com divergência: pega a última (maior ordem)
        List<Map<String, Object>> reversed = new ArrayList<>(entries);
        reversed.sort(Comparator.comparingInt((Map<String, Object> e) -> intVal(e, "ordem")).reversed());
        for (Map<String, Object> e : reversed) {
            Object v = e.get(field);
            if (v != null && !v.toString().trim().isEmpty()) return v.toString();
        }
        return null;
    }

    // ══ Sheet helpers ═══════════════════════════════════════════

    private void clearBody(Sheet ws) {
        int endRow = Math.max(ws.getLastRowNum(), BASE_END_ROW);
        for (int r = START_ROW; r <= endRow; r++) {
            Row row = ws.getRow(r);
            if (row == null) continue;
            for (int c = 0; c < 12; c++) { // A..L
                Cell cell = row.getCell(c);
                if (cell != null) cell.setBlank();
            }
        }
    }

    private void ensureRows(Sheet ws, int totalLines) {
        int baseCapacity = BASE_END_ROW - START_ROW + 1; // 30
        if (totalLines <= baseCapacity) return;

        int extra = totalLines - baseCapacity;
        int insertAt = BASE_END_ROW + 1;
        ws.shiftRows(insertAt, ws.getLastRowNum(), extra);

        for (int i = 0; i < extra; i++) {
            Row srcRow = ws.getRow(BASE_END_ROW);
            Row dstRow = ws.createRow(insertAt + i);
            if (srcRow != null) {
                dstRow.setHeight(srcRow.getHeight());
                for (int c = 0; c < 12; c++) {
                    Cell src = srcRow.getCell(c);
                    Cell dst = dstRow.createCell(c);
                    if (src != null) dst.setCellStyle(src.getCellStyle());
                }
            }
        }
    }

    private void setCellValue(Sheet ws, int rowIdx, int colIdx, String value) {
        Row row = ws.getRow(rowIdx);
        if (row == null) row = ws.createRow(rowIdx);
        Cell cell = row.getCell(colIdx);
        if (cell == null) cell = row.createCell(colIdx);
        if (value == null || value.isEmpty()) {
            cell.setBlank();
        } else {
            cell.setCellValue(value);
        }
    }

    // ══ Data helpers ════════════════════════════════════════════

    private String formatDataExtenso(LocalDate d) {
        int dow = d.getDayOfWeek().getValue() - 1; // 0=segunda
        return String.format("%s, %d de %s de %d",
                PT_BR_WEEKDAYS[dow], d.getDayOfMonth(), PT_BR_MONTHS[d.getMonthValue() - 1], d.getYear());
    }

    private String objStr(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private int intVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) { try { return Integer.parseInt(s); } catch (Exception e) { return 0; } }
        return 0;
    }

    private boolean boolVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        return false;
    }

    private int extractDay(Object dataObj) {
        if (dataObj instanceof LocalDate ld) return ld.getDayOfMonth();
        if (dataObj instanceof java.sql.Date sd) return sd.toLocalDate().getDayOfMonth();
        if (dataObj instanceof java.util.Date ud) { var c = Calendar.getInstance(); c.setTime(ud); return c.get(Calendar.DAY_OF_MONTH); }
        return 1;
    }

    private String strForSort(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return "23:59:59";
        String s = v.toString().trim();
        return s.isEmpty() ? "23:59:59" : s;
    }
}
