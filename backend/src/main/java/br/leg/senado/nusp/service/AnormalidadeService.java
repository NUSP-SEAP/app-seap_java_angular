package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.RegistroAnormalidade;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.RegistroAnormalidadeRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/**
 * Equivale ao anormalidade_service.py do Python (207 linhas).
 *
 * PONTO CRÍTICO: contém syncHouveAnormalidade() que substitui a trigger
 * operacao.sync_houve_anormalidade() do PostgreSQL. Esta é a única lógica
 * de negócio que estava no banco e agora é 100% responsabilidade do Java.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnormalidadeService {

    private final RegistroAnormalidadeRepository anormalidadeRepo;
    private final EntityManager entityManager;

    // ══════════════════════════════════════════════════════════
    //  syncHouveAnormalidade — SUBSTITUI TRIGGER DO POSTGRESQL
    // ══════════════════════════════════════════════════════════

    /**
     * Recalcula o campo houve_anormalidade na tabela OPR_REGISTRO_ENTRADA
     * com base na existência de registros na tabela OPR_ANORMALIDADE.
     *
     * Equivale à trigger function operacao.sync_houve_anormalidade() do PostgreSQL.
     *
     * DEVE ser chamado após qualquer INSERT, UPDATE ou DELETE em OPR_ANORMALIDADE
     * que envolva o campo entrada_id. Se o entrada_id mudou (UPDATE), chamar
     * para AMBOS os valores (antigo e novo).
     *
     * @param entradaId ID da entrada (OPR_REGISTRO_ENTRADA) a recalcular
     */
    public void syncHouveAnormalidade(Long entradaId) {
        if (entradaId == null) return;
        boolean existe = anormalidadeRepo.existsByEntradaId(entradaId);
        anormalidadeRepo.updateHouveAnormalidade(entradaId, existe ? 1 : 0);
    }

    // ══════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════

    private static String clean(Map<String, Object> body, String key) {
        Object val = body.get(key);
        return val == null ? "" : val.toString().strip();
    }

    private static boolean parseBool(String s) {
        if (s == null || s.isEmpty()) return false;
        String lower = s.toLowerCase();
        return "true".equals(lower) || "1".equals(lower) || "sim".equals(lower)
                || "yes".equals(lower) || "t".equals(lower);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.strip();
    }

    // ══════════════════════════════════════════════════════════
    //  Registrar / Editar Anormalidade
    // ══════════════════════════════════════════════════════════

    /**
     * POST /api/operacao/anormalidade/registro
     * Equivale a registrar_anormalidade() do Python.
     *
     * Substitui CHECK constraints do PostgreSQL:
     *   - ck_prejuizo_desc → se houve_prejuizo, descricao_prejuizo obrigatória
     *   - ck_reclamacao_desc → se houve_reclamacao, autores obrigatórios
     *   - ck_manutencao_hora → se acionou_manutencao, hora obrigatória
     *   - ck_datas_coerentes → data_solucao >= data, hora_solucao >= hora_inicio
     */
    @Transactional
    public Map<String, Object> registrar(Map<String, Object> body, String userId) {
        // 1) Leitura dos campos
        String registroIdRaw = clean(body, "registro_id");
        String dataStr = clean(body, "data");
        if (dataStr.length() > 10) dataStr = dataStr.substring(0, 10); // "2026-03-19 00:00:00.0" → "2026-03-19"
        String salaIdRaw = clean(body, "sala_id");
        String nomeEvento = clean(body, "nome_evento");
        String horaInicioAnormalidade = clean(body, "hora_inicio_anormalidade");
        String descricaoAnormalidade = clean(body, "descricao_anormalidade");

        boolean houvePrejuizo = parseBool(clean(body, "houve_prejuizo"));
        String descricaoPrejuizo = clean(body, "descricao_prejuizo");

        boolean houveReclamacao = parseBool(clean(body, "houve_reclamacao"));
        String autoresConteudoReclamacao = clean(body, "autores_conteudo_reclamacao");

        boolean acionouManutencao = parseBool(clean(body, "acionou_manutencao"));
        String horaAcionamentoManutencao = clean(body, "hora_acionamento_manutencao");

        boolean resolvidaPeloOperador = parseBool(clean(body, "resolvida_pelo_operador"));
        String procedimentosAdotados = clean(body, "procedimentos_adotados");

        String dataSolucao = clean(body, "data_solucao");
        String horaSolucao = clean(body, "hora_solucao");
        String responsavelEvento = clean(body, "responsavel_evento");
        String entradaIdRaw = clean(body, "entrada_id");
        String anomIdRaw = clean(body, "id");
        if (anomIdRaw.isEmpty()) anomIdRaw = clean(body, "registro_anormalidade_id");

        // 2) Validações — substituem CHECK constraints do banco
        Map<String, String> errors = new LinkedHashMap<>();

        if (registroIdRaw.isEmpty()) errors.put("registro_id", "Campo obrigatório.");
        if (dataStr.isEmpty()) errors.put("data", "Campo obrigatório.");
        if (salaIdRaw.isEmpty()) errors.put("sala_id", "Campo obrigatório.");
        if (nomeEvento.isEmpty()) errors.put("nome_evento", "Campo obrigatório.");
        if (horaInicioAnormalidade.isEmpty()) errors.put("hora_inicio_anormalidade", "Campo obrigatório.");
        if (descricaoAnormalidade.isEmpty()) errors.put("descricao_anormalidade", "Campo obrigatório.");
        if (responsavelEvento.isEmpty()) errors.put("responsavel_evento", "Campo obrigatório.");

        // ck_prejuizo_desc
        if (houvePrejuizo && descricaoPrejuizo.isEmpty())
            errors.put("descricao_prejuizo", "Campo obrigatório quando houve prejuízo.");

        // ck_reclamacao_desc
        if (houveReclamacao && autoresConteudoReclamacao.isEmpty())
            errors.put("autores_conteudo_reclamacao", "Campo obrigatório quando houve reclamação.");

        // ck_manutencao_hora
        if (acionouManutencao && horaAcionamentoManutencao.isEmpty())
            errors.put("hora_acionamento_manutencao", "Campo obrigatório quando houve acionamento de manutenção.");

        // Validação extra: resolvida exige procedimentos
        if (resolvidaPeloOperador && procedimentosAdotados.isEmpty())
            errors.put("procedimentos_adotados", "Campo obrigatório quando a anormalidade foi resolvida pelo operador.");

        // ck_datas_coerentes
        if (!dataSolucao.isEmpty()) {
            if (dataSolucao.compareTo(dataStr) < 0)
                errors.put("data_solucao", "Data da solução da anormalidade não pode ser anterior à data da ocorrência.");
            else if (dataSolucao.equals(dataStr) && !horaSolucao.isEmpty() && !horaInicioAnormalidade.isEmpty()
                    && horaSolucao.compareTo(horaInicioAnormalidade) < 0)
                errors.put("hora_solucao", "Hora da solução não pode ser anterior ao início da anormalidade.");
        }

        // entrada_id — opcional, mas validado se presente
        Long entradaId = null;
        if (!entradaIdRaw.isEmpty()) {
            try {
                long val = Long.parseLong(entradaIdRaw);
                if (val <= 0) throw new NumberFormatException();
                entradaId = val;
            } catch (NumberFormatException e) {
                errors.put("entrada_id", "Entrada inválida.");
            }
        }

        if (!errors.isEmpty())
            throw new ServiceValidationException("Erros de validação nos campos.");

        // 3) Conversões numéricas
        long registroId;
        try { registroId = Long.parseLong(registroIdRaw); }
        catch (NumberFormatException e) { throw new ServiceValidationException("Registro inválido."); }

        int salaId;
        try { salaId = Integer.parseInt(salaIdRaw); }
        catch (NumberFormatException e) { throw new ServiceValidationException("Local inválido."); }

        Long anomId = null;
        if (!anomIdRaw.isEmpty()) {
            try {
                long val = Long.parseLong(anomIdRaw);
                if (val <= 0) throw new NumberFormatException();
                anomId = val;
            } catch (NumberFormatException e) {
                throw new ServiceValidationException("Registro de anormalidade inválido.");
            }
        }

        // 4) INSERT ou UPDATE
        long resultId;

        if (anomId != null) {
            // ── EDIÇÃO ──
            RegistroAnormalidade anom = anormalidadeRepo.findById(anomId)
                    .orElseThrow(() -> new ServiceValidationException("Anormalidade não encontrada."));

            Long entradaIdAnterior = anom.getEntradaId();

            anom.setData(LocalDate.parse(dataStr));
            anom.setSalaId(salaId);
            anom.setNomeEvento(nomeEvento);
            anom.setHoraInicioAnormalidade(horaInicioAnormalidade);
            anom.setDescricaoAnormalidade(descricaoAnormalidade);
            anom.setHouvePrejuizo(houvePrejuizo);
            anom.setDescricaoPrejuizo(blankToNull(descricaoPrejuizo));
            anom.setHouveReclamacao(houveReclamacao);
            anom.setAutoresConteudoReclamacao(blankToNull(autoresConteudoReclamacao));
            anom.setAcionouManutencao(acionouManutencao);
            anom.setHoraAcionamentoManutencao(blankToNull(horaAcionamentoManutencao));
            anom.setResolvidaPeloOperador(resolvidaPeloOperador);
            anom.setProcedimentosAdotados(blankToNull(procedimentosAdotados));
            anom.setDataSolucao(dataSolucao.isEmpty() ? null : LocalDate.parse(dataSolucao));
            anom.setHoraSolucao(blankToNull(horaSolucao));
            anom.setResponsavelEvento(responsavelEvento);
            anom.setAtualizadoPor(userId);

            anormalidadeRepo.save(anom);
            resultId = anomId;

            // ── syncHouveAnormalidade (UPDATE) ──
            // Se entrada_id mudou, sincronizar AMBAS
            syncHouveAnormalidade(anom.getEntradaId());
            if (entradaIdAnterior != null && !entradaIdAnterior.equals(anom.getEntradaId())) {
                syncHouveAnormalidade(entradaIdAnterior);
            }

        } else {
            // ── CRIAÇÃO ──
            RegistroAnormalidade anom = new RegistroAnormalidade();
            anom.setRegistroId(registroId);
            anom.setEntradaId(entradaId);
            anom.setData(LocalDate.parse(dataStr));
            anom.setSalaId(salaId);
            anom.setNomeEvento(nomeEvento);
            anom.setHoraInicioAnormalidade(horaInicioAnormalidade);
            anom.setDescricaoAnormalidade(descricaoAnormalidade);
            anom.setHouvePrejuizo(houvePrejuizo);
            anom.setDescricaoPrejuizo(blankToNull(descricaoPrejuizo));
            anom.setHouveReclamacao(houveReclamacao);
            anom.setAutoresConteudoReclamacao(blankToNull(autoresConteudoReclamacao));
            anom.setAcionouManutencao(acionouManutencao);
            anom.setHoraAcionamentoManutencao(blankToNull(horaAcionamentoManutencao));
            anom.setResolvidaPeloOperador(resolvidaPeloOperador);
            anom.setProcedimentosAdotados(blankToNull(procedimentosAdotados));
            anom.setDataSolucao(dataSolucao.isEmpty() ? null : LocalDate.parse(dataSolucao));
            anom.setHoraSolucao(blankToNull(horaSolucao));
            anom.setResponsavelEvento(responsavelEvento);
            anom.setCriadoPor(userId);
            anom.setAtualizadoPor(userId);

            anom = anormalidadeRepo.save(anom);
            resultId = anom.getId();

            // ── syncHouveAnormalidade (INSERT) ──
            syncHouveAnormalidade(entradaId);
        }

        return new java.util.LinkedHashMap<>(Map.of("registro_anormalidade_id", resultId, "registro_id", registroId));
    }

    // ══════════════════════════════════════════════════════════
    //  Buscar anormalidade por entrada (para edição)
    // ══════════════════════════════════════════════════════════

    /**
     * GET /api/operacao/anormalidade/registro?entrada_id=...
     * Equivale a get_registro_anormalidade_por_entrada() do Python.
     */
    public Map<String, Object> buscarPorEntrada(long entradaId) {
        List<Object[]> rows = anormalidadeRepo.findByEntradaIdNative(entradaId);
        if (rows.isEmpty()) return null;

        Object[] r = rows.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", num(r[0]));
        result.put("registro_id", num(r[1]));
        result.put("entrada_id", num(r[2]));
        result.put("data", str(r[3]));
        result.put("sala_id", r[4] != null ? ((Number) r[4]).intValue() : null);
        result.put("nome_evento", str(r[5]));
        result.put("hora_inicio_anormalidade", str(r[6]));
        result.put("descricao_anormalidade", str(r[7]));
        result.put("houve_prejuizo", bool(r[8]));
        result.put("descricao_prejuizo", str(r[9]));
        result.put("houve_reclamacao", bool(r[10]));
        result.put("autores_conteudo_reclamacao", str(r[11]));
        result.put("acionou_manutencao", bool(r[12]));
        result.put("hora_acionamento_manutencao", str(r[13]));
        result.put("resolvida_pelo_operador", bool(r[14]));
        result.put("procedimentos_adotados", str(r[15]));
        result.put("data_solucao", str(r[16]));
        result.put("hora_solucao", str(r[17]));
        result.put("responsavel_evento", str(r[18]));

        // Campo derivado para o frontend
        result.put("anormalidade_solucionada", str(r[16]) != null || str(r[17]) != null);

        return result;
    }

    private static String str(Object o) { return NativeQueryUtils.str(o); }
    private static Long num(Object o) { return NativeQueryUtils.num(o); }
    private static boolean bool(Object o) { return NativeQueryUtils.boolVal(o); }
}
