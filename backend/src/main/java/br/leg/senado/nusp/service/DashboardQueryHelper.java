package br.leg.senado.nusp.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.time.LocalDate;
import java.util.*;

/**
 * Equivale ao query_helpers.py do Python.
 * Constrói queries dinâmicas para dashboards com paginação, busca, filtros e faceted search.
 * Adaptado de PostgreSQL (ILIKE, LIMIT/OFFSET) para Oracle (UPPER LIKE, FETCH/OFFSET).
 */
public class DashboardQueryHelper {

    /** Resultado de uma listagem paginada. */
    public record PagedResult(List<Map<String, Object>> data, int total, Map<String, List<Map<String, String>>> distinct) {}

    /**
     * Executa uma listagem paginada com busca, filtros, faceted search e ordenação.
     * Equivale a _admin_list_view() + fetch_fn() do Python.
     */
    public static PagedResult executePagedQuery(
            EntityManager em,
            String selectCols,
            String fromJoins,
            String dateCol,
            Map<String, String> validSortCols,
            List<String> searchCols,
            Map<String, String> colMap,
            Map<String, String> colTypes,
            int page, int limit, String search, String sort, String direction,
            Map<String, Object> periodo, Map<String, Object> filters) {
        return executePagedQuery(em, selectCols, fromJoins, dateCol, validSortCols,
                searchCols, colMap, colTypes, page, limit, search, sort, direction, periodo, filters, null);
    }

    public static PagedResult executePagedQuery(
            EntityManager em,
            String selectCols,
            String fromJoins,
            String dateCol,            // coluna de data para filtro de período (ex: "c.DATA_OPERACAO")
            Map<String, String> validSortCols,   // key=param → value=SQL coluna
            List<String> searchCols,   // colunas para busca textual (UPPER LIKE)
            Map<String, String> colMap,           // key=nome front → value=SQL expr (para filtros/distinct)
            Map<String, String> colTypes,         // key=nome front → value=tipo (text/date/bool/number)
            int page, int limit, String search, String sort, String direction,
            Map<String, Object> periodo, Map<String, Object> filters,
            String tiebreaker) {       // coluna secundária de desempate (ex: "c.ID DESC")

        int offset = (page - 1) * limit;

        // ── WHERE (busca textual) ──
        // Se fromJoins já contém WHERE (ex: filtro de operador), appendCondition
        // deve usar AND. Truque: inicializar where não-vazio para forçar o branch AND.
        boolean fromHasWhere = fromJoins.toUpperCase().contains(" WHERE ");
        StringBuilder where = new StringBuilder(fromHasWhere ? " " : "");
        List<Object> params = new ArrayList<>();

        if (search != null && !search.isBlank()) {
            String term = "%" + search.strip().toUpperCase() + "%";
            List<String> ors = new ArrayList<>();
            for (String col : searchCols) {
                ors.add("UPPER(" + col + ") LIKE ?");
                params.add(term);
            }
            if (!ors.isEmpty()) appendCondition(where, String.join(" OR ", ors));
        }

        // ── WHERE (período) ──
        appendDateFilter(where, params, dateCol, periodo);

        // ── WHERE (filtros de coluna) ──
        appendColumnFilters(where, params, filters, colMap, colTypes, null);

        // ── ORDER BY ──
        String orderCol = validSortCols.getOrDefault(sort != null ? sort : "", validSortCols.values().iterator().next());
        String dir = "desc".equalsIgnoreCase(direction) ? "DESC" : "ASC";
        String orderBy = "ORDER BY " + orderCol + " " + dir;
        if (tiebreaker != null && !tiebreaker.isBlank()) {
            orderBy += ", " + tiebreaker;
        }

        // ── COUNT ──
        String countSql = "SELECT COUNT(*) " + fromJoins + " " + where;
        Query countQ = em.createNativeQuery(countSql);
        setParams(countQ, params);
        int total = ((Number) countQ.getSingleResult()).intValue();

        // ── DATA ──
        List<Map<String, Object>> data = new ArrayList<>();
        if (total > 0) {
            String dataSql = "SELECT " + selectCols + " " + fromJoins + " " + where + " " + orderBy
                    + " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
            List<Object> dataParams = new ArrayList<>(params);
            dataParams.add(offset);
            dataParams.add(limit);
            Query dataQ = em.createNativeQuery(dataSql);
            setParams(dataQ, dataParams);
            @SuppressWarnings("unchecked")
            List<Object[]> rows = dataQ.getResultList();
            String[] colNames = selectCols.split(",");
            for (Object[] row : rows) {
                Map<String, Object> m = new LinkedHashMap<>();
                for (int i = 0; i < row.length && i < colNames.length; i++) {
                    String name = colNames[i].trim();
                    // Extrai alias se existir (ex: "s.NOME AS sala_nome" → "sala_nome")
                    int asIdx = name.toUpperCase().lastIndexOf(" AS ");
                    if (asIdx >= 0) name = name.substring(asIdx + 4).trim();
                    // Remove tabela (ex: "o.NOME_COMPLETO" → "NOME_COMPLETO")
                    int dotIdx = name.lastIndexOf('.');
                    if (dotIdx >= 0) name = name.substring(dotIdx + 1);
                    m.put(name.toLowerCase(), convertValue(row[i]));
                }
                data.add(m);
            }
        }

        // ── DISTINCT MAP (faceted search) ──
        // Simplificado: retorna distinct para cada coluna sem filtro cruzado
        Map<String, List<Map<String, String>>> distinct = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : colMap.entrySet()) {
            String key = entry.getKey();
            String expr = entry.getValue();
            String type = colTypes.getOrDefault(key, "text");
            // Datas: extrair apenas a parte da data (TRUNC) formatada, ordenar DESC
            String selectExpr = "date".equals(type)
                    ? "DISTINCT TRUNC(" + expr + ")"
                    : "DISTINCT (" + expr + ")";
            String orderDir = "date".equals(type) ? "DESC" : "ASC";
            String distSql = "SELECT " + selectExpr + " AS V " + fromJoins + " " + where + " ORDER BY V " + orderDir;
            try {
                Query distQ = em.createNativeQuery(distSql);
                setParams(distQ, params);
                @SuppressWarnings("unchecked")
                List<Object> vals = distQ.getResultList();
                List<Map<String, String>> list = new ArrayList<>();
                for (Object v : vals) {
                    if (v == null) continue;
                    String value = v.toString();
                    String label = value;
                    if ("date".equals(type)) {
                        // Converter para yyyy-MM-dd (value) e dd/MM/yyyy (label)
                        String raw = value.split(" ")[0]; // "2026-03-03 00:00:00.0" → "2026-03-03"
                        value = raw;
                        String[] parts = raw.split("-");
                        if (parts.length == 3) label = parts[2] + "/" + parts[1] + "/" + parts[0];
                        else label = raw;
                    } else if ("bool".equals(type)) {
                        boolean b = "1".equals(value) || "true".equalsIgnoreCase(value);
                        value = b ? "true" : "false";
                        label = b ? "Sim" : "Não";
                    }
                    list.add(Map.of("value", value, "label", label));
                }
                distinct.put(key, list);
            } catch (Exception e) {
                distinct.put(key, List.of());
            }
        }

        return new PagedResult(data, total, distinct);
    }

    /**
     * Executa uma query sem paginação (para relatórios).
     * Equivale a fetch_all_pages() do Python.
     */
    public static List<Map<String, Object>> executeAllPages(
            EntityManager em, String selectCols, String fromJoins,
            String dateCol, Map<String, String> validSortCols, List<String> searchCols,
            Map<String, String> colMap, Map<String, String> colTypes,
            String search, String sort, String direction,
            Map<String, Object> periodo, Map<String, Object> filters) {

        PagedResult result = executePagedQuery(em, selectCols, fromJoins, dateCol,
                validSortCols, searchCols, colMap, colTypes,
                1, 999999, search, sort, direction, periodo, filters);
        return result.data();
    }

    // ── Helpers privados ──

    private static void appendDateFilter(StringBuilder where, List<Object> params, String dateCol, Map<String, Object> periodo) {
        if (dateCol == null || periodo == null) return;
        @SuppressWarnings("unchecked")
        List<Map<String, String>> ranges = (List<Map<String, String>>) periodo.get("ranges");
        if (ranges == null || ranges.isEmpty()) return;

        List<String> parts = new ArrayList<>();
        for (Map<String, String> r : ranges) {
            String start = r.get("start");
            String end = r.get("end");
            if (start != null && end != null) {
                parts.add(dateCol + " BETWEEN TO_DATE(?, 'YYYY-MM-DD') AND TO_DATE(?, 'YYYY-MM-DD')");
                params.add(start);
                params.add(end);
            }
        }
        if (parts.isEmpty()) return;
        appendCondition(where, String.join(" OR ", parts));
    }

    private static void appendColumnFilters(StringBuilder where, List<Object> params,
                                             Map<String, Object> filters, Map<String, String> colMap,
                                             Map<String, String> colTypes, String excludeKey) {
        if (filters == null) return;
        for (Map.Entry<String, Object> entry : filters.entrySet()) {
            String key = entry.getKey();
            if (key.equals(excludeKey)) continue;
            if (!colMap.containsKey(key)) continue;
            if (!(entry.getValue() instanceof Map)) continue;

            @SuppressWarnings("unchecked")
            Map<String, Object> spec = (Map<String, Object>) entry.getValue();
            String colSql = colMap.get(key);
            String colType = colTypes.getOrDefault(key, "text");

            // text filter
            String text = spec.get("text") != null ? spec.get("text").toString().strip() : "";
            if (!text.isEmpty()) {
                if ("date".equals(colType)) {
                    String cond = "TO_CHAR(" + colSql + ", 'DD/MM/YYYY') LIKE ?";
                    appendCondition(where, cond);
                } else {
                    appendCondition(where, "UPPER(CAST(" + colSql + " AS VARCHAR2(4000))) LIKE ?");
                }
                params.add("%" + text.toUpperCase() + "%");
            }

            // range filter (date from/to)
            if ("date".equals(colType) && spec.get("range") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> range = (Map<String, Object>) spec.get("range");
                String from = range.get("from") != null ? range.get("from").toString().strip() : "";
                String to = range.get("to") != null ? range.get("to").toString().strip() : "";
                if (!from.isEmpty()) {
                    appendCondition(where, "TRUNC(" + colSql + ") >= TO_DATE(?, 'YYYY-MM-DD')");
                    params.add(from);
                }
                if (!to.isEmpty()) {
                    appendCondition(where, "TRUNC(" + colSql + ") <= TO_DATE(?, 'YYYY-MM-DD')");
                    params.add(to);
                }
            }

            // values filter (IN)
            @SuppressWarnings("unchecked")
            List<Object> values = (List<Object>) spec.get("values");
            if (values != null && !values.isEmpty()) {
                if ("date".equals(colType)) {
                    String placeholders = String.join(",", values.stream()
                            .map(v -> "TO_DATE(?, 'YYYY-MM-DD')").toList());
                    appendCondition(where, "TRUNC(" + colSql + ") IN (" + placeholders + ")");
                    params.addAll(values);
                } else if ("bool".equals(colType)) {
                    // Converter "true"/"false" para 1/0 (Oracle NUMBER(1))
                    String placeholders = String.join(",", values.stream().map(v -> "?").toList());
                    appendCondition(where, colSql + " IN (" + placeholders + ")");
                    for (Object v : values) {
                        params.add("true".equalsIgnoreCase(v.toString()) ? 1 : 0);
                    }
                } else {
                    String placeholders = String.join(",", values.stream().map(v -> "?").toList());
                    appendCondition(where, colSql + " IN (" + placeholders + ")");
                    params.addAll(values);
                }
            }
        }
    }

    private static void appendCondition(StringBuilder where, String condition) {
        if (where.isEmpty()) where.append("WHERE (").append(condition).append(")");
        else where.append(" AND (").append(condition).append(")");
        // Nota: quando fromJoins já contém WHERE, 'where' é inicializado com " "
        // (não-vazio), forçando o branch AND — que é concatenado à SQL existente.
    }

    private static void setParams(Query q, List<Object> params) {
        for (int i = 0; i < params.size(); i++) {
            q.setParameter(i + 1, params.get(i));
        }
    }

    private static Object convertValue(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) {
            if (n.longValue() == n.doubleValue()) return n.longValue();
            return n;
        }
        // Delega tratamento de CLOB e toString para NativeQueryUtils
        return NativeQueryUtils.str(v);
    }
}
