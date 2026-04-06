package br.leg.senado.nusp.service;

import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.service.DashboardQueryHelper.PagedResult;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Equivale a operador_dashboard views + dashboard_home.py do Python.
 * 7 endpoints: meus-checklists, minhas-operacoes, detalhes de cada.
 * Todos com verificação de ownership (criado_por == userId).
 */
@Service
@RequiredArgsConstructor
public class OperadorDashboardService {

    private final EntityManager em;

    // ══ Meus Checklists ═══════════════════════════════════════

    private static final Map<String, String> MC_SORT = new LinkedHashMap<>() {{
        put("data", "c.DATA_OPERACAO"); put("sala", "s.NOME");
    }};

    public PagedResult listMeusChecklists(String userId, int page, int limit,
                                           String sort, String dir, Map<String, Object> filters) {
        return DashboardQueryHelper.executePagedQuery(em,
                "c.ID, c.DATA_OPERACAO AS data, s.NOME AS sala_nome, c.TURNO, " +
                "c.HORA_INICIO_TESTES, c.HORA_TERMINO_TESTES, c.EDITADO, " +
                "(SELECT COUNT(*) FROM FRM_CHECKLIST_RESPOSTA r JOIN FRM_CHECKLIST_ITEM_TIPO t ON t.ID = r.ITEM_TIPO_ID " +
                "WHERE r.CHECKLIST_ID = c.ID AND r.STATUS = 'Ok' AND t.TIPO_WIDGET != 'text') AS qtde_ok, " +
                "(SELECT COUNT(*) FROM FRM_CHECKLIST_RESPOSTA r JOIN FRM_CHECKLIST_ITEM_TIPO t ON t.ID = r.ITEM_TIPO_ID " +
                "WHERE r.CHECKLIST_ID = c.ID AND r.STATUS = 'Falha' AND t.TIPO_WIDGET != 'text') AS qtde_falha",
                "FROM FRM_CHECKLIST c JOIN CAD_SALA s ON s.ID = c.SALA_ID " +
                "WHERE (c.CRIADO_POR = '" + userId + "' OR EXISTS (SELECT 1 FROM FRM_CHECKLIST_OPERADOR co WHERE co.CHECKLIST_ID = c.ID AND co.OPERADOR_ID = '" + userId + "'))",
                "c.DATA_OPERACAO", MC_SORT, List.of("s.NOME"),
                Map.of("data", "c.DATA_OPERACAO", "sala", "s.NOME"),
                Map.of("data", "date", "sala", "text"),
                page, limit, null, sort, dir, null, filters);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getMeuChecklistDetalhe(long checklistId, String userId) {
        @SuppressWarnings("unchecked")
        List<Object> rows = em.createNativeQuery("""
                SELECT c.CRIADO_POR FROM FRM_CHECKLIST c WHERE c.ID = ?1
                UNION
                SELECT co.OPERADOR_ID FROM FRM_CHECKLIST_OPERADOR co WHERE co.CHECKLIST_ID = ?1
                """).setParameter(1, checklistId).getResultList();
        if (rows.isEmpty()) throw new ServiceValidationException("Checklist não encontrado.", HttpStatus.NOT_FOUND);
        boolean isOwner = rows.stream().anyMatch(r -> r != null && userId.equals(r.toString()));
        if (!isOwner) throw new ServiceValidationException("Acesso negado.", HttpStatus.FORBIDDEN);

        // Reutiliza o detalhe do admin
        List<Object[]> detRows = em.createNativeQuery("""
                SELECT c.ID, c.DATA_OPERACAO, s.NOME AS SALA_NOME, c.TURNO,
                       c.HORA_INICIO_TESTES, c.HORA_TERMINO_TESTES, c.OBSERVACOES,
                       c.USB_01, c.USB_02, c.EDITADO, c.SALA_ID, c.OBSERVACOES_EDITADO
                FROM FRM_CHECKLIST c
                JOIN CAD_SALA s ON s.ID = c.SALA_ID
                WHERE c.ID = ?1
                """).setParameter(1, checklistId).getResultList();
        if (detRows.isEmpty()) return null;

        Object[] h = detRows.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", num(h[0])); result.put("data_operacao", str(h[1]));
        result.put("sala_nome", str(h[2])); result.put("turno", str(h[3]));
        result.put("hora_inicio_testes", str(h[4])); result.put("hora_termino_testes", str(h[5]));
        result.put("observacoes", str(h[6])); result.put("usb_01", str(h[7]));
        result.put("usb_02", str(h[8])); result.put("editado", boolVal(h[9]));
        result.put("sala_id", num(h[10]));
        result.put("observacoes_editado", boolVal(h[11]));

        @SuppressWarnings("unchecked")
        List<Object[]> itens = em.createNativeQuery("""
                SELECT r.ID, r.ITEM_TIPO_ID, t.NOME, t.TIPO_WIDGET, r.STATUS, r.DESCRICAO_FALHA, r.VALOR_TEXTO, r.EDITADO
                FROM FRM_CHECKLIST_RESPOSTA r
                JOIN FRM_CHECKLIST_ITEM_TIPO t ON t.ID = r.ITEM_TIPO_ID
                LEFT JOIN FRM_CHECKLIST_SALA_CONFIG sc ON sc.ITEM_TIPO_ID = t.ID AND sc.SALA_ID = ?2
                WHERE r.CHECKLIST_ID = ?1 ORDER BY sc.ORDEM ASC, t.ID ASC
                """).setParameter(1, checklistId).setParameter(2, num(h[10])).getResultList();

        List<Map<String, Object>> itensList = new ArrayList<>();
        for (Object[] it : itens) {
            itensList.add(Map.of("id", num(it[0]), "item_tipo_id", num(it[1]),
                    "item_nome", str(it[2]), "tipo_widget", str(it[3]),
                    "status", str(it[4]), "descricao_falha", str(it[5]) != null ? str(it[5]) : "",
                    "valor_texto", str(it[6]) != null ? str(it[6]) : "", "editado", boolVal(it[7])));
        }
        result.put("itens", itensList);
        return result;
    }

    // ══ Minhas Operações ══════════════════════════════════════

    private static final Map<String, String> MO_SORT = new LinkedHashMap<>() {{
        put("data", "r.DATA"); put("sala", "s.NOME");
    }};

    public PagedResult listMinhasOperacoes(String userId, int page, int limit,
                                            String sort, String dir, Map<String, Object> filters) {
        return DashboardQueryHelper.executePagedQuery(em,
                "e.ID AS entrada_id, r.DATA AS data, s.NOME AS sala_nome, " +
                "e.TIPO_EVENTO, e.NOME_EVENTO, e.HORA_ENTRADA, e.HORA_SAIDA, e.HOUVE_ANORMALIDADE, " +
                "a.ID AS anormalidade_id",
                "FROM OPR_REGISTRO_ENTRADA e " +
                "JOIN OPR_REGISTRO_AUDIO r ON r.ID = e.REGISTRO_ID " +
                "JOIN CAD_SALA s ON s.ID = r.SALA_ID " +
                "LEFT JOIN OPR_ANORMALIDADE a ON a.ENTRADA_ID = e.ID " +
                "WHERE (e.OPERADOR_ID = '" + userId + "' OR EXISTS (SELECT 1 FROM OPR_ENTRADA_OPERADOR eo WHERE eo.ENTRADA_ID = e.ID AND eo.OPERADOR_ID = '" + userId + "'))",
                "r.DATA", MO_SORT, List.of("s.NOME", "e.NOME_EVENTO"),
                Map.of("data", "r.DATA", "sala", "s.NOME"),
                Map.of("data", "date", "sala", "text"),
                page, limit, null, sort, dir, null, filters);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getMinhaOperacaoDetalhe(long entradaId, String userId) {
        @SuppressWarnings("unchecked")
        List<Object> rows = em.createNativeQuery("""
                SELECT e.OPERADOR_ID FROM OPR_REGISTRO_ENTRADA e WHERE e.ID = ?1
                UNION
                SELECT eo.OPERADOR_ID FROM OPR_ENTRADA_OPERADOR eo WHERE eo.ENTRADA_ID = ?1
                """).setParameter(1, entradaId).getResultList();
        if (rows.isEmpty()) throw new ServiceValidationException("Operação não encontrada.", HttpStatus.NOT_FOUND);
        boolean isOwner = rows.stream().anyMatch(r -> r != null && userId.equals(r.toString()));
        if (!isOwner) throw new ServiceValidationException("Acesso negado.", HttpStatus.FORBIDDEN);

        List<Object[]> detRows = em.createNativeQuery("""
                SELECT e.ID, r.DATA, s.NOME AS SALA_NOME, e.NOME_EVENTO,
                       e.HORARIO_PAUTA, e.HORARIO_INICIO, e.HORARIO_TERMINO,
                       e.TIPO_EVENTO, e.USB_01, e.USB_02, e.OBSERVACOES,
                       e.RESPONSAVEL_EVENTO, e.HORA_ENTRADA, e.HORA_SAIDA,
                       e.HOUVE_ANORMALIDADE, e.EDITADO, r.SALA_ID, e.REGISTRO_ID,
                       e.COMISSAO_ID, e.ORDEM, c.NOME AS COMISSAO_NOME,
                       e.NOME_EVENTO_EDITADO, e.RESPONSAVEL_EVENTO_EDITADO,
                       e.HORARIO_PAUTA_EDITADO, e.HORARIO_INICIO_EDITADO,
                       e.HORARIO_TERMINO_EDITADO, e.USB_01_EDITADO, e.USB_02_EDITADO,
                       e.OBSERVACOES_EDITADO, e.COMISSAO_EDITADO, e.SALA_EDITADO,
                       e.HORA_ENTRADA_EDITADO, e.HORA_SAIDA_EDITADO
                FROM OPR_REGISTRO_ENTRADA e
                JOIN OPR_REGISTRO_AUDIO r ON r.ID = e.REGISTRO_ID
                JOIN CAD_SALA s ON s.ID = r.SALA_ID
                LEFT JOIN CAD_COMISSAO c ON c.ID = e.COMISSAO_ID
                WHERE e.ID = ?1
                """).setParameter(1, entradaId).getResultList();
        if (detRows.isEmpty()) return null;

        Object[] r = detRows.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", num(r[0])); result.put("data", str(r[1]));
        result.put("sala_nome", str(r[2])); result.put("nome_evento", str(r[3]));
        result.put("horario_pauta", str(r[4])); result.put("horario_inicio", str(r[5]));
        result.put("horario_termino", str(r[6])); result.put("tipo_evento", str(r[7]));
        result.put("usb_01", str(r[8])); result.put("usb_02", str(r[9]));
        result.put("observacoes", str(r[10])); result.put("responsavel_evento", str(r[11]));
        result.put("hora_entrada", str(r[12])); result.put("hora_saida", str(r[13]));
        result.put("houve_anormalidade", boolVal(r[14])); result.put("editado", boolVal(r[15]));
        result.put("sala_id", num(r[16])); result.put("registro_id", num(r[17]));
        result.put("comissao_id", num(r[18])); result.put("ordem", num(r[19]));
        result.put("comissao_nome", str(r[20]));
        result.put("nome_evento_editado", boolVal(r[21]));
        result.put("responsavel_evento_editado", boolVal(r[22]));
        result.put("horario_pauta_editado", boolVal(r[23]));
        result.put("horario_inicio_editado", boolVal(r[24]));
        result.put("horario_termino_editado", boolVal(r[25]));
        result.put("usb_01_editado", boolVal(r[26]));
        result.put("usb_02_editado", boolVal(r[27]));
        result.put("observacoes_editado", boolVal(r[28]));
        result.put("comissao_editado", boolVal(r[29]));
        result.put("sala_editado", boolVal(r[30]));
        result.put("hora_entrada_editado", boolVal(r[31]));
        result.put("hora_saida_editado", boolVal(r[32]));
        return result;
    }

    public Map<String, Object> getMinhaAnormalidadeDetalhe(long anomId, String userId) {
        @SuppressWarnings("unchecked")
        List<Object> rows = em.createNativeQuery("""
                SELECT a.CRIADO_POR FROM OPR_ANORMALIDADE a WHERE a.ID = ?1
                """).setParameter(1, anomId).getResultList();
        if (rows.isEmpty()) throw new ServiceValidationException("Anormalidade não encontrada.", HttpStatus.NOT_FOUND);
        String owner = rows.get(0) != null ? rows.get(0).toString() : "";
        if (!owner.equals(userId)) throw new ServiceValidationException("Acesso negado.", HttpStatus.FORBIDDEN);

        List<Object[]> detRows = em.createNativeQuery("""
                SELECT a.ID, a.DATA, s.NOME AS SALA_NOME, a.NOME_EVENTO,
                       a.HORA_INICIO_ANORMALIDADE, a.DESCRICAO_ANORMALIDADE,
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
        if (detRows.isEmpty()) return null;

        Object[] r = detRows.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", num(r[0])); result.put("data", str(r[1]));
        result.put("sala_nome", str(r[2])); result.put("nome_evento", str(r[3]));
        result.put("hora_inicio_anormalidade", str(r[4])); result.put("descricao_anormalidade", str(r[5]));
        result.put("houve_prejuizo", boolVal(r[6])); result.put("descricao_prejuizo", str(r[7]));
        result.put("houve_reclamacao", boolVal(r[8])); result.put("autores_conteudo_reclamacao", str(r[9]));
        result.put("acionou_manutencao", boolVal(r[10])); result.put("hora_acionamento_manutencao", str(r[11]));
        result.put("resolvida_pelo_operador", boolVal(r[12])); result.put("procedimentos_adotados", str(r[13]));
        result.put("data_solucao", str(r[14])); result.put("hora_solucao", str(r[15]));
        result.put("responsavel_evento", str(r[16])); result.put("criado_por_nome", str(r[17]));
        result.put("observacao_supervisor", str(r[18])); result.put("observacao_chefe", str(r[19]));
        result.put("anormalidade_solucionada", str(r[14]) != null || str(r[15]) != null);
        return result;
    }

    private static String str(Object o) { return NativeQueryUtils.str(o); }
    private static Long num(Object o) { return NativeQueryUtils.num(o); }
    private static boolean boolVal(Object o) { return NativeQueryUtils.boolVal(o); }
}
