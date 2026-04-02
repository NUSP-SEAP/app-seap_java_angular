package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.RegistroAnormalidadeAdmin;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.service.DashboardQueryHelper.PagedResult;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Lógica de negócio dos dashboards admin.
 * Equivale a dashboard_operadores.py, dashboard_checklists.py,
 * dashboard_operacoes.py e dashboard_anormalidades.py do Python.
 */
@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    private final EntityManager em;

    // ══ Operadores ════════════════════════════════════════════

    private static final Map<String, String> OP_SORT = Map.of(
            "nome", "o.NOME_COMPLETO", "email", "o.EMAIL");

    public PagedResult listOperadores(int page, int limit, String search, String sort, String dir,
                                       Map<String, Object> filters) {
        return DashboardQueryHelper.executePagedQuery(em,
                "o.ID, o.NOME_COMPLETO, o.EMAIL",
                "FROM PES_OPERADOR o",
                null, OP_SORT, List.of("o.NOME_COMPLETO", "o.EMAIL"),
                Map.of("nome", "o.NOME_COMPLETO", "email", "o.EMAIL"),
                Map.of("nome", "text", "email", "text"),
                page, limit, search, sort, dir, null, filters);
    }

    // ══ Checklists ════════════════════════════════════════════

    private static final Map<String, String> CL_SORT = new LinkedHashMap<>() {{
        put("data", "c.DATA_OPERACAO"); put("sala", "s.NOME"); put("nome", "o.NOME_COMPLETO");
    }};

    public PagedResult listChecklists(int page, int limit, String search, String sort, String dir,
                                       Map<String, Object> periodo, Map<String, Object> filters) {
        return DashboardQueryHelper.executePagedQuery(em,
                "c.ID, c.DATA_OPERACAO AS data, s.NOME AS sala_nome, c.TURNO, " +
                "o.NOME_COMPLETO AS operador_nome, c.HORA_INICIO_TESTES, c.HORA_TERMINO_TESTES, c.EDITADO, " +
                "CASE WHEN EXISTS (SELECT 1 FROM FRM_CHECKLIST_RESPOSTA r WHERE r.CHECKLIST_ID = c.ID AND r.STATUS = 'Falha') THEN 'Falha' " +
                "WHEN EXISTS (SELECT 1 FROM FRM_CHECKLIST_RESPOSTA r WHERE r.CHECKLIST_ID = c.ID) THEN 'Ok' ELSE '--' END AS status",
                "FROM FRM_CHECKLIST c JOIN CAD_SALA s ON s.ID = c.SALA_ID LEFT JOIN PES_OPERADOR o ON o.ID = c.CRIADO_POR",
                "c.DATA_OPERACAO", CL_SORT, List.of("s.NOME", "o.NOME_COMPLETO"),
                Map.of("data", "c.DATA_OPERACAO", "sala", "s.NOME", "turno", "c.TURNO", "nome", "o.NOME_COMPLETO"),
                Map.of("data", "date", "sala", "text", "turno", "text", "nome", "text"),
                page, limit, search, sort, dir, periodo, filters);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getChecklistDetalhe(long checklistId) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT c.ID, c.DATA_OPERACAO, c.SALA_ID, s.NOME AS SALA_NOME, c.TURNO,
                       c.HORA_INICIO_TESTES, c.HORA_TERMINO_TESTES, c.OBSERVACOES,
                       c.USB_01, c.USB_02, c.EDITADO, o.NOME_COMPLETO AS OPERADOR_NOME,
                       c.CRIADO_POR
                FROM FRM_CHECKLIST c
                JOIN CAD_SALA s ON s.ID = c.SALA_ID
                LEFT JOIN PES_OPERADOR o ON o.ID = c.CRIADO_POR
                WHERE c.ID = ?1
                """).setParameter(1, checklistId).getResultList();
        if (rows.isEmpty()) return null;

        Object[] h = rows.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", num(h[0])); result.put("data_operacao", str(h[1]));
        result.put("sala_id", num(h[2])); result.put("sala_nome", str(h[3]));
        result.put("turno", str(h[4])); result.put("hora_inicio_testes", str(h[5]));
        result.put("hora_termino_testes", str(h[6])); result.put("observacoes", str(h[7]));
        result.put("usb_01", str(h[8])); result.put("usb_02", str(h[9]));
        result.put("editado", boolVal(h[10])); result.put("operador_nome", str(h[11]));
        result.put("criado_por", str(h[12]));

        List<Object[]> itens = em.createNativeQuery("""
                SELECT r.ID, r.ITEM_TIPO_ID, t.NOME AS ITEM_NOME, r.STATUS,
                       r.DESCRICAO_FALHA, r.VALOR_TEXTO, r.EDITADO
                FROM FRM_CHECKLIST_RESPOSTA r
                JOIN FRM_CHECKLIST_ITEM_TIPO t ON t.ID = r.ITEM_TIPO_ID
                LEFT JOIN FRM_CHECKLIST_SALA_CONFIG sc ON sc.ITEM_TIPO_ID = t.ID AND sc.SALA_ID = ?2
                WHERE r.CHECKLIST_ID = ?1
                ORDER BY sc.ORDEM ASC, t.ID ASC
                """).setParameter(1, checklistId).setParameter(2, num(h[2])).getResultList();

        List<Map<String, Object>> itensList = new ArrayList<>();
        for (Object[] it : itens) {
            itensList.add(Map.of("id", num(it[0]), "item_tipo_id", num(it[1]),
                    "item_nome", str(it[2]), "status", str(it[3]),
                    "descricao_falha", str(it[4]) != null ? str(it[4]) : "",
                    "valor_texto", str(it[5]) != null ? str(it[5]) : "",
                    "editado", boolVal(it[6])));
        }
        result.put("itens", itensList);
        return result;
    }

    // ══ Operações (sessões) ═══════════════════════════════════

    private static final Map<String, String> OP_SESS_SORT = new LinkedHashMap<>() {{
        put("data", "r.DATA"); put("sala", "s.NOME");
    }};

    public PagedResult listOperacoes(int page, int limit, String search, String sort, String dir,
                                      Map<String, Object> periodo, Map<String, Object> filters) {
        return DashboardQueryHelper.executePagedQuery(em,
                "r.ID, r.DATA AS data, s.NOME AS sala_nome, r.EM_ABERTO, " +
                "r.CHECKLIST_DO_DIA_OK, o.NOME_COMPLETO AS criado_por_nome",
                "FROM OPR_REGISTRO_AUDIO r JOIN CAD_SALA s ON s.ID = r.SALA_ID " +
                "LEFT JOIN PES_OPERADOR o ON o.ID = r.CRIADO_POR",
                "r.DATA", OP_SESS_SORT, List.of("s.NOME"),
                Map.of("data", "r.DATA", "sala", "s.NOME", "em_aberto", "r.EM_ABERTO"),
                Map.of("data", "date", "sala", "text", "em_aberto", "bool"),
                page, limit, search, sort, dir, periodo, filters);
    }

    // ══ Operações (entradas) ══════════════════════════════════

    private static final Map<String, String> ENT_SORT = new LinkedHashMap<>() {{
        put("data", "r.DATA"); put("sala", "s.NOME"); put("operador", "o.NOME_COMPLETO");
    }};

    public PagedResult listOperacoesEntradas(int page, int limit, String search, String sort, String dir,
                                              Map<String, Object> periodo, Map<String, Object> filters) {
        return DashboardQueryHelper.executePagedQuery(em,
                "e.ID, r.DATA AS data, s.NOME AS sala_nome, o.NOME_COMPLETO AS operador_nome, " +
                "e.TIPO_EVENTO, e.NOME_EVENTO, e.HORARIO_PAUTA, e.HORARIO_INICIO, e.HORARIO_TERMINO, " +
                "e.HOUVE_ANORMALIDADE, e.EDITADO",
                "FROM OPR_REGISTRO_ENTRADA e " +
                "JOIN OPR_REGISTRO_AUDIO r ON r.ID = e.REGISTRO_ID " +
                "JOIN CAD_SALA s ON s.ID = r.SALA_ID " +
                "JOIN PES_OPERADOR o ON o.ID = e.OPERADOR_ID",
                "r.DATA", ENT_SORT, List.of("s.NOME", "o.NOME_COMPLETO", "e.NOME_EVENTO"),
                Map.of("data", "r.DATA", "sala", "s.NOME", "operador", "o.NOME_COMPLETO", "tipo_evento", "e.TIPO_EVENTO"),
                Map.of("data", "date", "sala", "text", "operador", "text", "tipo_evento", "text"),
                page, limit, search, sort, dir, periodo, filters);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getEntradaDetalhe(long entradaId) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT e.ID, e.REGISTRO_ID, r.DATA, s.NOME AS SALA_NOME, o.NOME_COMPLETO,
                       e.ORDEM, e.SEQ, e.NOME_EVENTO, e.HORARIO_PAUTA, e.HORARIO_INICIO,
                       e.HORARIO_TERMINO, e.TIPO_EVENTO, e.USB_01, e.USB_02, e.OBSERVACOES,
                       e.COMISSAO_ID, e.RESPONSAVEL_EVENTO, e.HORA_ENTRADA, e.HORA_SAIDA,
                       e.HOUVE_ANORMALIDADE, e.EDITADO, r.SALA_ID, c.NOME AS COMISSAO_NOME
                FROM OPR_REGISTRO_ENTRADA e
                JOIN OPR_REGISTRO_AUDIO r ON r.ID = e.REGISTRO_ID
                JOIN CAD_SALA s ON s.ID = r.SALA_ID
                JOIN PES_OPERADOR o ON o.ID = e.OPERADOR_ID
                LEFT JOIN CAD_COMISSAO c ON c.ID = e.COMISSAO_ID
                WHERE e.ID = ?1
                """).setParameter(1, entradaId).getResultList();
        if (rows.isEmpty()) return null;

        Object[] r = rows.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", num(r[0])); result.put("registro_id", num(r[1]));
        result.put("data", str(r[2])); result.put("sala_nome", str(r[3]));
        result.put("operador_nome", str(r[4])); result.put("ordem", num(r[5]));
        result.put("seq", num(r[6])); result.put("nome_evento", str(r[7]));
        result.put("horario_pauta", str(r[8])); result.put("horario_inicio", str(r[9]));
        result.put("horario_termino", str(r[10])); result.put("tipo_evento", str(r[11]));
        result.put("usb_01", str(r[12])); result.put("usb_02", str(r[13]));
        result.put("observacoes", str(r[14])); result.put("comissao_id", num(r[15]));
        result.put("responsavel_evento", str(r[16])); result.put("hora_entrada", str(r[17]));
        result.put("hora_saida", str(r[18])); result.put("houve_anormalidade", boolVal(r[19]));
        result.put("editado", boolVal(r[20])); result.put("sala_id", num(r[21]));
        result.put("comissao_nome", str(r[22]));
        return result;
    }

    // ══ Entradas de uma sessão ═════════════════════════════════

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listEntradasDeSessao(long registroId) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT e.ID, e.ORDEM, o.NOME_COMPLETO, e.TIPO_EVENTO,
                       e.NOME_EVENTO, e.HORARIO_PAUTA, e.HORARIO_INICIO, e.HORARIO_TERMINO,
                       e.HOUVE_ANORMALIDADE
                FROM OPR_REGISTRO_ENTRADA e
                JOIN PES_OPERADOR o ON o.ID = e.OPERADOR_ID
                WHERE e.REGISTRO_ID = ?1
                ORDER BY e.ORDEM ASC, e.SEQ ASC
                """).setParameter(1, registroId).getResultList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] r : rows) {
            result.add(Map.of(
                    "id", num(r[0]), "ordem", num(r[1]),
                    "operador", str(r[2]) != null ? str(r[2]) : "",
                    "tipo", str(r[3]) != null ? str(r[3]) : "",
                    "evento", str(r[4]) != null ? str(r[4]) : "",
                    "pauta", str(r[5]) != null ? str(r[5]) : "",
                    "inicio", str(r[6]) != null ? str(r[6]) : "",
                    "fim", str(r[7]) != null ? str(r[7]) : "",
                    "anormalidade", boolVal(r[8])
            ));
        }
        return result;
    }

    // ══ Anormalidades ═════════════════════════════════════════

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listSalasComAnormalidades(String search) {
        String sql = """
                SELECT DISTINCT s.ID, s.NOME
                FROM OPR_ANORMALIDADE a
                JOIN CAD_SALA s ON s.ID = a.SALA_ID
                """ + (search != null && !search.isBlank() ? "WHERE UPPER(s.NOME) LIKE ?1" : "") +
                " ORDER BY s.NOME";
        Query q = em.createNativeQuery(sql);
        if (search != null && !search.isBlank()) q.setParameter(1, "%" + search.toUpperCase() + "%");
        List<Object[]> rows = q.getResultList();
        return rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ((Number) r[0]).intValue());
            m.put("nome", r[1].toString());
            return m;
        }).toList();
    }

    private static final Map<String, String> ANOM_SORT = new LinkedHashMap<>() {{
        put("data", "a.DATA"); put("sala", "s.NOME"); put("nome_evento", "a.NOME_EVENTO");
    }};

    public PagedResult listAnormalidades(int page, int limit, String search, String sort, String dir,
                                          Map<String, Object> periodo, Map<String, Object> filters, Integer salaId) {
        String fromJoins = "FROM OPR_ANORMALIDADE a " +
                "JOIN CAD_SALA s ON s.ID = a.SALA_ID " +
                "LEFT JOIN PES_OPERADOR o ON o.ID = a.CRIADO_POR";
        if (salaId != null) {
            fromJoins += " AND a.SALA_ID = " + salaId;  // safe: int value
        }

        return DashboardQueryHelper.executePagedQuery(em,
                "a.ID, a.DATA AS data, s.NOME AS sala_nome, a.NOME_EVENTO, " +
                "o.NOME_COMPLETO AS registrado_por, a.DESCRICAO_ANORMALIDADE, " +
                "a.DATA_SOLUCAO, a.HOUVE_PREJUIZO, a.HOUVE_RECLAMACAO, a.RESOLVIDA_PELO_OPERADOR",
                fromJoins, "a.DATA", ANOM_SORT, List.of("s.NOME", "a.NOME_EVENTO", "o.NOME_COMPLETO"),
                Map.of("data", "a.DATA", "sala", "s.NOME", "nome_evento", "a.NOME_EVENTO"),
                Map.of("data", "date", "sala", "text", "nome_evento", "text"),
                page, limit, search, sort, dir, periodo, filters);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getAnormalidadeDetalhe(long anomId) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT a.ID, a.REGISTRO_ID, a.ENTRADA_ID, a.DATA, a.SALA_ID, s.NOME AS SALA_NOME,
                       a.NOME_EVENTO, a.HORA_INICIO_ANORMALIDADE, a.DESCRICAO_ANORMALIDADE,
                       a.HOUVE_PREJUIZO, a.DESCRICAO_PREJUIZO,
                       a.HOUVE_RECLAMACAO, a.AUTORES_CONTEUDO_RECLAMACAO,
                       a.ACIONOU_MANUTENCAO, a.HORA_ACIONAMENTO_MANUTENCAO,
                       a.RESOLVIDA_PELO_OPERADOR, a.PROCEDIMENTOS_ADOTADOS,
                       a.DATA_SOLUCAO, a.HORA_SOLUCAO, a.RESPONSAVEL_EVENTO,
                       o.NOME_COMPLETO AS CRIADO_POR_NOME,
                       adm.OBSERVACAO_SUPERVISOR, adm.OBSERVACAO_CHEFE
                FROM OPR_ANORMALIDADE a
                JOIN CAD_SALA s ON s.ID = a.SALA_ID
                LEFT JOIN PES_OPERADOR o ON o.ID = a.CRIADO_POR
                LEFT JOIN OPR_ANORMALIDADE_ADMIN adm ON adm.REGISTRO_ANORMALIDADE_ID = a.ID
                WHERE a.ID = ?1
                """).setParameter(1, anomId).getResultList();
        if (rows.isEmpty()) return null;

        Object[] r = rows.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", num(r[0])); result.put("registro_id", num(r[1]));
        result.put("entrada_id", num(r[2])); result.put("data", str(r[3]));
        result.put("sala_id", num(r[4])); result.put("sala_nome", str(r[5]));
        result.put("nome_evento", str(r[6])); result.put("hora_inicio_anormalidade", str(r[7]));
        result.put("descricao_anormalidade", str(r[8]));
        result.put("houve_prejuizo", boolVal(r[9])); result.put("descricao_prejuizo", str(r[10]));
        result.put("houve_reclamacao", boolVal(r[11])); result.put("autores_conteudo_reclamacao", str(r[12]));
        result.put("acionou_manutencao", boolVal(r[13])); result.put("hora_acionamento_manutencao", str(r[14]));
        result.put("resolvida_pelo_operador", boolVal(r[15])); result.put("procedimentos_adotados", str(r[16]));
        result.put("data_solucao", str(r[17])); result.put("hora_solucao", str(r[18]));
        result.put("responsavel_evento", str(r[19])); result.put("criado_por_nome", str(r[20]));
        result.put("observacao_supervisor", str(r[21])); result.put("observacao_chefe", str(r[22]));
        result.put("anormalidade_solucionada", str(r[17]) != null || str(r[18]) != null);
        return result;
    }

    // ══ Observações de anormalidade ═══════════════════════════

    @Transactional
    public void salvarObservacaoSupervisor(long anomId, String observacao, String userId) {
        upsertAnormalidadeAdmin(anomId, userId);
        em.createNativeQuery("UPDATE OPR_ANORMALIDADE_ADMIN SET OBSERVACAO_SUPERVISOR = ?1, ATUALIZADO_POR = ?2, ATUALIZADO_EM = SYSTIMESTAMP WHERE REGISTRO_ANORMALIDADE_ID = ?3")
                .setParameter(1, observacao).setParameter(2, userId).setParameter(3, anomId).executeUpdate();
    }

    @Transactional
    public void salvarObservacaoChefe(long anomId, String observacao, String userId) {
        upsertAnormalidadeAdmin(anomId, userId);
        em.createNativeQuery("UPDATE OPR_ANORMALIDADE_ADMIN SET OBSERVACAO_CHEFE = ?1, ATUALIZADO_POR = ?2, ATUALIZADO_EM = SYSTIMESTAMP WHERE REGISTRO_ANORMALIDADE_ID = ?3")
                .setParameter(1, observacao).setParameter(2, userId).setParameter(3, anomId).executeUpdate();
    }

    private void upsertAnormalidadeAdmin(long anomId, String userId) {
        int exists = ((Number) em.createNativeQuery("SELECT COUNT(*) FROM OPR_ANORMALIDADE_ADMIN WHERE REGISTRO_ANORMALIDADE_ID = ?1")
                .setParameter(1, anomId).getSingleResult()).intValue();
        if (exists == 0) {
            RegistroAnormalidadeAdmin admin = new RegistroAnormalidadeAdmin();
            admin.setRegistroAnormalidadeId(anomId);
            admin.setCriadoPor(userId);
            em.persist(admin);
            em.flush();
        }
    }

    // ══ RDS (Registro Diário de Sessões) ═════════════════════════

    /** Lista anos distintos com registros de operação. */
    @SuppressWarnings("unchecked")
    public List<Integer> listRdsAnos() {
        String sql = "SELECT DISTINCT EXTRACT(YEAR FROM DATA) AS ANO FROM OPR_REGISTRO_AUDIO ORDER BY ANO ASC";
        List<Object> rows = em.createNativeQuery(sql).getResultList();
        return rows.stream().map(o -> ((Number) o).intValue()).toList();
    }

    /** Lista meses distintos para um ano. */
    @SuppressWarnings("unchecked")
    public List<Integer> listRdsMeses(int ano) {
        String sql = """
                SELECT DISTINCT EXTRACT(MONTH FROM DATA) AS MES
                FROM OPR_REGISTRO_AUDIO
                WHERE EXTRACT(YEAR FROM DATA) = ?1
                ORDER BY MES ASC
                """;
        List<Object> rows = em.createNativeQuery(sql).setParameter(1, ano).getResultList();
        return rows.stream().map(o -> ((Number) o).intValue()).toList();
    }

    /** Busca dados brutos para geração do RDS XLSX. Equivale a rds_db.fetch_rds_rows(). */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> fetchRdsRows(int ano, int mes) {
        java.time.LocalDate start = java.time.LocalDate.of(ano, mes, 1);
        java.time.LocalDate end = mes == 12 ? java.time.LocalDate.of(ano + 1, 1, 1) : java.time.LocalDate.of(ano, mes + 1, 1);

        String sql = """
                SELECT
                    ra.ID               AS REGISTRO_ID,
                    ra.DATA             AS DATA,
                    ra.EM_ABERTO        AS EM_ABERTO,
                    s.NOME              AS SALA_NOME,
                    rop.ID              AS ENTRADA_ID,
                    rop.ORDEM           AS ORDEM,
                    rop.SEQ             AS SEQ,
                    rop.NOME_EVENTO     AS NOME_EVENTO,
                    rop.HORARIO_PAUTA   AS HORARIO_PAUTA,
                    rop.HORARIO_INICIO  AS HORARIO_INICIO,
                    rop.HORARIO_TERMINO AS HORARIO_TERMINO,
                    op.NOME_EXIBICAO    AS OPERADOR_NOME_EXIBICAO,
                    c.NOME              AS COMISSAO_NOME
                FROM OPR_REGISTRO_AUDIO ra
                JOIN CAD_SALA s ON s.ID = ra.SALA_ID
                JOIN OPR_REGISTRO_ENTRADA rop ON rop.REGISTRO_ID = ra.ID
                JOIN PES_OPERADOR op ON op.ID = rop.OPERADOR_ID
                LEFT JOIN CAD_COMISSAO c ON c.ID = rop.COMISSAO_ID
                WHERE ra.DATA >= ?1 AND ra.DATA < ?2
                ORDER BY ra.DATA ASC, rop.HORARIO_PAUTA ASC NULLS LAST,
                         rop.HORARIO_INICIO ASC NULLS LAST, s.NOME ASC,
                         ra.ID ASC, rop.ORDEM ASC, rop.SEQ ASC, rop.ID ASC
                """;

        Query q = em.createNativeQuery(sql)
                .setParameter(1, java.sql.Date.valueOf(start))
                .setParameter(2, java.sql.Date.valueOf(end));

        List<Object[]> rows = q.getResultList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("registro_id", r[0]);
            m.put("data", r[1] instanceof java.sql.Timestamp ts ? ts.toLocalDateTime().toLocalDate() :
                           r[1] instanceof java.sql.Date sd ? sd.toLocalDate() : r[1]);
            m.put("em_aberto", r[2] != null && ((Number) r[2]).intValue() == 1);
            m.put("sala_nome", str(r[3]));
            m.put("entrada_id", r[4]);
            m.put("ordem", r[5]);
            m.put("seq", r[6]);
            m.put("nome_evento", str(r[7]));
            m.put("horario_pauta", str(r[8]));
            m.put("horario_inicio", str(r[9]));
            m.put("horario_termino", str(r[10]));
            m.put("operador_nome_exibicao", str(r[11]));
            m.put("comissao_nome", str(r[12]));
            result.add(m);
        }
        return result;
    }

    // ── Helpers ──
    private static String str(Object o) { return NativeQueryUtils.str(o); }
    private static Long num(Object o) { return NativeQueryUtils.num(o); }
    private static boolean boolVal(Object o) { return NativeQueryUtils.boolVal(o); }
}
