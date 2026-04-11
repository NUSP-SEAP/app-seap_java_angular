package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.*;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Equivale ao operacao_service.py do Python (771 linhas).
 * Módulo mais complexo do sistema: gerenciamento de sessões de operação de áudio.
 */
@Service
@RequiredArgsConstructor
public class OperacaoService {

    private final RegistroOperacaoAudioRepository audioRepo;
    private final RegistroOperacaoOperadorRepository entradaRepo;
    private final EntradaOperadorRepository entradaOperadorRepo;
    private final SuspensaoRepository suspensaoRepo;
    private final SalaRepository salaRepo;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    // ── Helpers ───────────────────────────────────────────────

    private static String clean(Map<String, Object> body, String key) {
        Object val = body.get(key);
        return val == null ? "" : val.toString().strip();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.strip();
    }

    /** Normaliza "hh:mm" → "hh:mm:ss" para manter consistência com o formato do banco. */
    private static String normalizeTime(String s) {
        if (s == null || s.isBlank()) return null;
        s = s.strip();
        // "14:30" → "14:30:00"
        if (s.matches("\\d{2}:\\d{2}")) return s + ":00";
        return s;
    }

    /** Normaliza tipo_evento — equivale a _normalizar_tipo_evento() do Python. */
    static String normalizarTipoEvento(String raw) {
        String norm = (raw == null ? "" : raw.strip().toLowerCase());
        if (norm.isEmpty() || norm.equals("operacao") || norm.startsWith("operação comum") || norm.startsWith("operacao")) return "operacao";
        if (norm.equals("cessao") || norm.startsWith("cessão") || norm.startsWith("cessao")) return "cessao";
        if (norm.equals("outros") || norm.startsWith("outros")) return "outros";
        return "";
    }

    private static int parseSalaId(String raw) {
        try { return Integer.parseInt(raw); }
        catch (Exception e) { throw new ServiceValidationException("Local inválido."); }
    }

    private static Long parseComissaoId(Map<String, Object> body) {
        Object raw = body.get("comissao_id");
        if (raw == null) return null;
        String s = raw.toString().strip();
        if (s.isEmpty()) return null;
        try { return Long.parseLong(s); }
        catch (Exception e) { return null; }
    }

    /** Converte Object[] do native query em Map. */
    private Map<String, Object> sessaoRowToMap(Object[] row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ((Number) row[0]).longValue());
        m.put("data", row[1]);
        m.put("sala_id", ((Number) row[2]).intValue());
        m.put("sala_nome", row[3] != null ? row[3].toString() : null);
        m.put("checklist_do_dia_id", row[4] != null ? ((Number) row[4]).longValue() : null);
        m.put("checklist_do_dia_ok", row[5] != null && ((Number) row[5]).intValue() == 1);
        return m;
    }

    private Map<String, Object> entradaRowToMap(Object[] row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entrada_id", ((Number) row[0]).longValue());
        m.put("registro_id", ((Number) row[1]).longValue());
        m.put("operador_id", str(row[2]));
        m.put("operador_nome", str(row[3]));
        m.put("ordem", ((Number) row[4]).intValue());
        m.put("seq", ((Number) row[5]).intValue());
        m.put("nome_evento", str(row[6]));
        m.put("horario_pauta", str(row[7]));
        m.put("horario_inicio", str(row[8]));
        m.put("horario_termino", str(row[9]));
        m.put("tipo_evento", normalizarTipoEvento(str(row[10])));
        m.put("usb_01", str(row[11]));
        m.put("usb_02", str(row[12]));
        m.put("observacoes", str(row[13]));
        m.put("houve_anormalidade", row[14] != null && ((Number) row[14]).intValue() == 1);
        m.put("anormalidade_id", row[15] != null ? ((Number) row[15]).longValue() : null);
        m.put("comissao_id", row[16] != null ? ((Number) row[16]).longValue() : null);
        m.put("responsavel_evento", str(row[17]));
        m.put("hora_entrada", str(row[18]));
        m.put("hora_saida", str(row[19]));
        return m;
    }

    private static String str(Object o) { return NativeQueryUtils.str(o); }

    // ── Estado da sessão ──────────────────────────────────────

    /**
     * GET /api/operacao/audio/estado-sessao?sala_id=...
     * Equivale a obter_estado_sessao_para_operador() do Python.
     */
    public Map<String, Object> obterEstadoSessao(int salaId, String operadorId) {
        List<Object[]> sessaoRows = audioRepo.findSessaoAbertaPorSala(salaId);

        if (sessaoRows.isEmpty()) {
            return Map.ofEntries(
                    Map.entry("sala_id", salaId),
                    Map.entry("existe_sessao_aberta", false),
                    Map.entry("registro_id", 0),
                    Map.entry("tipo_evento", "operacao"),
                    Map.entry("permite_anormalidade", true),
                    Map.entry("nomes_operadores_sessao", List.of()),
                    Map.entry("situacao_operador", "sem_sessao"),
                    Map.entry("entradas_operador", List.of()),
                    Map.entry("entradas_sessao", List.of()),
                    Map.entry("max_entradas_por_operador", 2)
            );
        }

        Map<String, Object> sessao = sessaoRowToMap(sessaoRows.get(0));
        long registroId = (long) sessao.get("id");
        Object dataRaw = sessao.get("data");
        String dataStr = dataRaw != null ? dataRaw.toString() : null;

        List<Object[]> entradaRows = entradaRepo.listarEntradasDaSessao(registroId);
        List<Map<String, Object>> entradas = entradaRows.stream().map(this::entradaRowToMap).collect(Collectors.toList());
        entradas.sort(Comparator.comparingInt((Map<String, Object> e) -> (int) e.get("ordem")).thenComparingLong(e -> (long) e.get("entrada_id")));

        List<Map<String, Object>> entradasOperador = operadorId != null
                ? entradas.stream().filter(e -> operadorId.equals(e.get("operador_id"))).toList()
                : List.of();

        String situacao = entradasOperador.isEmpty() ? "sem_entrada" :
                entradasOperador.size() == 1 ? "uma_entrada" : "duas_entradas";

        // Nomes de operadores únicos
        LinkedHashSet<String> vistos = new LinkedHashSet<>();
        for (var e : entradas) { String n = (String) e.get("operador_nome"); if (n != null) vistos.add(n); }

        // Entrada de referência para o cabeçalho
        Map<String, Object> header = entradas.stream().filter(e -> e.get("comissao_id") != null).findFirst()
                .orElse(entradas.isEmpty() ? null : entradas.get(0));

        String tipoHeader = header != null ? (String) header.get("tipo_evento") : "operacao";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sala_id", salaId);
        result.put("sala_nome", sessao.get("sala_nome"));
        result.put("existe_sessao_aberta", true);
        result.put("registro_id", registroId);
        result.put("tipo_evento", tipoHeader);
        result.put("permite_anormalidade", "operacao".equals(tipoHeader));
        result.put("data", dataStr);
        result.put("checklist_do_dia_id", sessao.get("checklist_do_dia_id"));
        result.put("checklist_do_dia_ok", sessao.get("checklist_do_dia_ok"));
        result.put("nome_evento", header != null ? header.get("nome_evento") : null);
        result.put("horario_pauta", header != null ? header.get("horario_pauta") : null);
        result.put("horario_inicio", header != null ? header.get("horario_inicio") : null);
        result.put("horario_termino", header != null ? header.get("horario_termino") : null);
        result.put("responsavel_evento", header != null ? header.get("responsavel_evento") : null);
        result.put("comissao_id", header != null ? header.get("comissao_id") : null);
        result.put("nomes_operadores_sessao", new ArrayList<>(vistos));
        result.put("situacao_operador", situacao);
        result.put("entradas_operador", entradasOperador);
        result.put("entradas_sessao", entradas);
        result.put("max_entradas_por_operador", 2);
        return result;
    }

    // ── Validação centralizada de horários (plenários numerados) ──

    /**
     * Valida todas as regras de horário para uma entrada de operação (não multi-operador).
     * Chamado tanto na criação quanto na edição.
     *
     * @param horarioPauta  horário da pauta (pode ser null)
     * @param horaInicio    início do evento (obrigatório)
     * @param horaEntrada   início da operação (obrigatório para ordem >= 2)
     * @param horaFim       término do evento (quando encerrado)
     * @param horaSaida     término da operação (quando não encerrado)
     * @param ordem         posição do operador na sessão (1 = primeiro)
     * @param registroId    ID da sessão (para buscar operadores adjacentes)
     * @param entradaId     ID da entrada sendo editada (0 para criação)
     */
    private void validarHorarios(String horarioPauta, String horaInicio,
                                  String horaEntrada, String horaFim, String horaSaida,
                                  int ordem, long registroId, long entradaId) {
        // Regra 1: Início do evento >= Horário da Pauta
        if (horaInicio != null && horarioPauta != null) {
            if (hm(horaInicio).compareTo(hm(horarioPauta)) < 0) {
                throw new ServiceValidationException(
                    "O início do evento não pode ser anterior ao horário da pauta (" + hm(horarioPauta) + ").");
            }
        }

        // Regra 5: hora_entrada obrigatório para ordem >= 2
        if (ordem >= 2 && (horaEntrada == null || horaEntrada.isBlank())) {
            throw new ServiceValidationException("O campo 'Início da operação' é obrigatório.");
        }

        // Referência para validação: usar horaEntrada se disponível, senão horaInicio
        String ref = (horaEntrada != null && !horaEntrada.isBlank()) ? horaEntrada : horaInicio;

        // Regra 2: Término do evento > Início da operação
        if (horaFim != null && ref != null) {
            if (hm(horaFim).compareTo(hm(ref)) <= 0) {
                throw new ServiceValidationException(
                    "O término do evento deve ser posterior ao início da operação (" + hm(ref) + ").");
            }
        }

        // Regra 2: Término da operação > Início da operação
        if (horaSaida != null && ref != null) {
            if (hm(horaSaida).compareTo(hm(ref)) <= 0) {
                throw new ServiceValidationException(
                    "O término da operação deve ser posterior ao início da operação (" + hm(ref) + ").");
            }
        }

        // Regra 3: Início da operação >= Término da operação do operador anterior
        if (ordem >= 2 && horaEntrada != null && registroId > 0) {
            List<?> antResult = entityManager.createNativeQuery(
                "SELECT e.HORA_SAIDA, o.NOME_COMPLETO FROM OPR_REGISTRO_ENTRADA e " +
                "JOIN PES_OPERADOR o ON o.ID = e.OPERADOR_ID " +
                "WHERE e.REGISTRO_ID = ?1 AND e.ORDEM = ?2")
                .setParameter(1, registroId).setParameter(2, ordem - 1).getResultList();
            if (!antResult.isEmpty()) {
                Object[] ant = (Object[]) antResult.get(0);
                String hsAnt = ant[0] != null ? ant[0].toString().strip() : null;
                String nomeAnt = ant[1] != null ? ant[1].toString() : "anterior";
                if (hsAnt != null && !hsAnt.isBlank() && hm(horaEntrada).compareTo(hm(hsAnt)) < 0) {
                    throw new ServiceValidationException(
                        "O horário de início da sua operação deve ser igual ou superior à " +
                        hm(hsAnt) + " (término da operação de " + nomeAnt + ").");
                }
            }
        }

        // Regra 4: Término da operação <= Início da operação do operador seguinte
        // (hora_saida ou horaFim quando encerrado)
        String terminoEfetivo = horaFim != null ? horaFim : horaSaida;
        if (terminoEfetivo != null && registroId > 0) {
            List<?> segResult = entityManager.createNativeQuery(
                "SELECT e.HORA_ENTRADA, o.NOME_COMPLETO FROM OPR_REGISTRO_ENTRADA e " +
                "JOIN PES_OPERADOR o ON o.ID = e.OPERADOR_ID " +
                "WHERE e.REGISTRO_ID = ?1 AND e.ORDEM = ?2")
                .setParameter(1, registroId).setParameter(2, ordem + 1)
                .getResultList();
            if (!segResult.isEmpty()) {
                Object[] seg = (Object[]) segResult.get(0);
                String heSeg = seg[0] != null ? seg[0].toString().strip() : null;
                String nomeSeg = seg[1] != null ? seg[1].toString() : "operador seguinte";
                if (heSeg != null && !heSeg.isBlank() && hm(terminoEfetivo).compareTo(hm(heSeg)) > 0) {
                    throw new ServiceValidationException(
                        "O término da operação não pode ser posterior ao início da operação de " +
                        nomeSeg + " (" + hm(heSeg) + ").");
                }
            }
        }
    }

    /**
     * Propaga os campos compartilhados da sessão (editados pelo operador de ordem 1)
     * para todas as outras entradas da mesma sessão.
     * Campos: nome_evento, horario_pauta, horario_inicio, tipo_evento, comissao_id, responsavel_evento.
     */
    private void propagarCamposSessao(long registroId, long entradaIdOrigem,
                                       String nomeEvento, String horarioPauta, String horaInicio,
                                       String tipoEvento, Long comissaoId, String responsavelEvento) {
        entityManager.createNativeQuery("""
                UPDATE OPR_REGISTRO_ENTRADA SET
                    NOME_EVENTO = :ne, HORARIO_PAUTA = :hp, HORARIO_INICIO = :hi,
                    TIPO_EVENTO = :te, COMISSAO_ID = :ci, RESPONSAVEL_EVENTO = :re
                WHERE REGISTRO_ID = :regId AND ID != :entradaId
                """)
                .setParameter("ne", nomeEvento).setParameter("hp", horarioPauta)
                .setParameter("hi", horaInicio).setParameter("te", tipoEvento)
                .setParameter("ci", comissaoId).setParameter("re", responsavelEvento)
                .setParameter("regId", registroId).setParameter("entradaId", entradaIdOrigem)
                .executeUpdate();
    }

    /** Extrai HH:MM de um valor de horário */
    private static String hm(String t) {
        if (t == null || t.length() < 5) return t;
        return t.substring(0, 5);
    }

    // ── Salvar entrada (criar ou editar) ──────────────────────

    /**
     * POST /api/operacao/audio/salvar-entrada
     * Equivale a salvar_entrada_operacao_audio() do Python.
     */
    @Transactional
    public Map<String, Object> salvarEntrada(Map<String, Object> body, String userId) {
        if (userId == null || userId.isBlank()) throw new ServiceValidationException("Usuário não autenticado.");

        String dataOperacao = clean(body, "data_operacao");
        String salaIdRaw = clean(body, "sala_id");
        String nomeEvento = blankToNull(clean(body, "nome_evento"));
        String horarioPauta = normalizeTime(clean(body, "horario_pauta"));
        String horaInicio = normalizeTime(clean(body, "hora_inicio"));
        String horaFim = normalizeTime(clean(body, "hora_fim"));
        String observacoes = blankToNull(clean(body, "observacoes"));
        String usb01 = blankToNull(clean(body, "usb_01"));
        String usb02 = blankToNull(clean(body, "usb_02"));
        String responsavelEvento = blankToNull(clean(body, "responsavel_evento"));
        String horaEntrada = normalizeTime(clean(body, "hora_entrada"));
        String horaSaida = normalizeTime(clean(body, "hora_saida"));
        String tipoEventoRaw = clean(body, "tipo_evento");
        String tipoEvento = normalizarTipoEvento(tipoEventoRaw.isEmpty() ? "operacao" : tipoEventoRaw);
        if (tipoEvento.isEmpty()) tipoEvento = "operacao";
        Long comissaoId = parseComissaoId(body);
        Object entradaIdRaw = body.get("entrada_id");

        // Validações básicas
        Map<String, String> errors = new LinkedHashMap<>();
        if (dataOperacao.isEmpty()) errors.put("data_operacao", "Campo obrigatório.");
        if (salaIdRaw.isEmpty()) errors.put("sala_id", "Campo obrigatório.");
        if (nomeEvento == null) errors.put("nome_evento", "Campo obrigatório.");
        if (horaInicio == null) errors.put("hora_inicio", "Campo obrigatório.");
        if (!errors.isEmpty()) throw new ServiceValidationException("Erro de validação.");

        int salaId = parseSalaId(salaIdRaw);
        Map<String, Object> estado = obterEstadoSessao(salaId, userId);

        // ── EDIÇÃO ──
        if (entradaIdRaw != null && !entradaIdRaw.toString().isEmpty()) {
            long entradaId;
            try { entradaId = Long.parseLong(entradaIdRaw.toString()); }
            catch (Exception e) { throw new ServiceValidationException("Entrada inválida para edição."); }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entradasOp = (List<Map<String, Object>>) estado.get("entradas_operador");
            Map<String, Object> entradaAtual = entradasOp.stream()
                    .filter(e -> ((Number) e.get("entrada_id")).longValue() == entradaId)
                    .findFirst().orElse(null);
            if (entradaAtual == null) throw new ServiceValidationException("Esta entrada não pertence ao operador ou à sessão atual.");

            boolean existeSessao = (boolean) estado.get("existe_sessao_aberta");
            if (!existeSessao) throw new ServiceValidationException("Não existe sessão aberta para este local.");

            long registroId = ((Number) estado.get("registro_id")).longValue();

            // Validação completa de horários (plenários numerados)
            Sala salaInline = salaRepo.findById(salaId).orElse(null);
            boolean isMultiOpInline = salaInline != null && Boolean.TRUE.equals(salaInline.getMultiOperador());
            if (!isMultiOpInline) {
                int ordemInline = ((Number) entradaAtual.get("ordem")).intValue();
                validarHorarios(horarioPauta, horaInicio, horaEntrada, horaFim, horaSaida,
                        ordemInline, registroId, entradaId);
            }

            entradaRepo.updateEntradaBasica(entradaId, nomeEvento, horarioPauta, horaInicio, horaFim,
                    tipoEvento, observacoes, usb01, usb02, comissaoId, responsavelEvento,
                    horaEntrada, horaSaida, userId);

            // Propagar campos compartilhados para outros operadores da sessão (se ordem = 1)
            if (!isMultiOpInline) {
                int ordemAtual = ((Number) entradaAtual.get("ordem")).intValue();
                if (ordemAtual == 1) {
                    propagarCamposSessao(registroId, entradaId, nomeEvento, horarioPauta,
                            horaInicio, tipoEvento, comissaoId, responsavelEvento);
                }
            }

            if (horaFim != null) audioRepo.finalizarSessao(registroId, userId);

            String houveAnomRaw = clean(body, "houve_anormalidade");
            boolean houveAnom = "sim".equalsIgnoreCase(houveAnomRaw);

            return new LinkedHashMap<>(Map.of("registro_id", registroId, "entrada_id", entradaId,
                    "tipo_evento", tipoEvento, "seq", entradaAtual.get("seq"), "is_edicao", true,
                    "houve_anormalidade", houveAnom));
        }

        // ── CRIAÇÃO ──

        // Proteção contra duplicação: mesmo operador + mesma sala em < 5 min
        Number dupCheck = (Number) entityManager.createNativeQuery("""
                SELECT CASE WHEN EXISTS (
                    SELECT 1 FROM OPR_REGISTRO_ENTRADA e
                    JOIN OPR_REGISTRO_AUDIO r ON r.ID = e.REGISTRO_ID
                    WHERE r.SALA_ID = ?1 AND e.OPERADOR_ID = ?2
                    AND e.CRIADO_EM >= SYSTIMESTAMP - INTERVAL '5' MINUTE
                ) THEN 1 ELSE 0 END FROM DUAL
                """).setParameter(1, salaId).setParameter(2, userId).getSingleResult();
        if (dupCheck.intValue() == 1) {
            throw new ServiceValidationException(
                "Já existe um registro de operação seu para esta sala enviado há menos de 5 minutos. Aguarde antes de enviar novamente.");
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entradasOp = (List<Map<String, Object>>) estado.get("entradas_operador");
        if (entradasOp.size() >= 2) throw new ServiceValidationException("Este operador já possui 2 entradas nesta sessão.");

        int seq = entradasOp.isEmpty() ? 1 : 2;
        boolean existeSessao = (boolean) estado.get("existe_sessao_aberta");
        long registroId;

        if (!existeSessao) {
            // Cria nova sessão
            RegistroOperacaoAudio audio = new RegistroOperacaoAudio();
            audio.setData(LocalDate.parse(dataOperacao));
            audio.setSalaId(salaId);
            audio.setEmAberto(true);
            audio.setCriadoPor(userId);

            // Busca checklist do dia
            List<Object[]> checkRows = audioRepo.findChecklistDoDia(dataOperacao, salaId);
            if (!checkRows.isEmpty()) {
                audio.setChecklistDoDiaId(((Number) checkRows.get(0)[0]).longValue());
                audio.setChecklistDoDiaOk(((Number) checkRows.get(0)[1]).intValue() == 1);
            }

            audio = audioRepo.save(audio);
            registroId = audio.getId();
        } else {
            registroId = ((Number) estado.get("registro_id")).longValue();
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> todasEntradas = (List<Map<String, Object>>) estado.get("entradas_sessao");
        int ordem = todasEntradas.size() + 1;

        // Validação completa de horários (plenários numerados)
        Sala sala = salaRepo.findById(salaId).orElse(null);
        boolean isMultiOp = sala != null && Boolean.TRUE.equals(sala.getMultiOperador());
        if (!isMultiOp) {
            validarHorarios(horarioPauta, horaInicio, horaEntrada, horaFim, horaSaida,
                    ordem, registroId, 0);
        }

        RegistroOperacaoOperador entrada = new RegistroOperacaoOperador();
        entrada.setRegistroId(registroId);
        entrada.setOperadorId(userId);
        entrada.setOrdem(ordem);
        entrada.setSeq(seq);
        entrada.setNomeEvento(nomeEvento);
        entrada.setHorarioPauta(horarioPauta);
        entrada.setHorarioInicio(horaInicio);
        entrada.setHorarioTermino(horaFim);
        entrada.setTipoEvento(br.leg.senado.nusp.enums.TipoEvento.fromValor(tipoEvento));
        entrada.setObservacoes(observacoes);
        entrada.setUsb01(usb01);
        entrada.setUsb02(usb02);
        entrada.setComissaoId(comissaoId);
        entrada.setResponsavelEvento(responsavelEvento);
        entrada.setHoraEntrada(horaEntrada);
        entrada.setHoraSaida(horaSaida);
        entrada.setCriadoPor(userId);
        entrada.setAtualizadoPor(userId);
        entrada = entradaRepo.save(entrada);

        // Multi-operador: salvar junction table + suspensões + auto-encerrar

        if (isMultiOp) {
            @SuppressWarnings("unchecked")
            List<String> operadoresIds = body.get("operadores_ids") instanceof List
                    ? (List<String>) body.get("operadores_ids") : List.of();
            // Sempre incluir o criador
            if (!operadoresIds.contains(userId)) {
                operadoresIds = new ArrayList<>(operadoresIds);
                operadoresIds.add(0, userId);
            }
            for (String opId : operadoresIds) {
                EntradaOperador eo = new EntradaOperador();
                eo.setEntradaId(entrada.getId());
                eo.setOperadorId(opId);
                entradaOperadorRepo.save(eo);
            }

            // Salvar suspensões
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> suspensoes = body.get("suspensoes") instanceof List
                    ? (List<Map<String, Object>>) body.get("suspensoes") : List.of();
            int ordemSusp = 1;
            for (Map<String, Object> susp : suspensoes) {
                String hs = normalizeTime(susp.get("hora_suspensao") != null ? susp.get("hora_suspensao").toString() : "");
                String hr = normalizeTime(susp.get("hora_reabertura") != null ? susp.get("hora_reabertura").toString() : "");
                if (hs != null || hr != null) {
                    Suspensao s = new Suspensao();
                    s.setEntradaId(entrada.getId());
                    s.setHoraSuspensao(hs);
                    s.setHoraReabertura(hr);
                    s.setOrdem(ordemSusp++);
                    suspensaoRepo.save(s);
                }
            }

            // Plenário Principal: sempre encerrar automaticamente
            audioRepo.finalizarSessao(registroId, userId);
        } else {
            if (horaFim != null) audioRepo.finalizarSessao(registroId, userId);
        }

        String houveAnomRaw = clean(body, "houve_anormalidade");
        boolean houveAnom = "sim".equalsIgnoreCase(houveAnomRaw);

        return new LinkedHashMap<>(Map.of("registro_id", registroId, "entrada_id", entrada.getId(),
                "tipo_evento", tipoEvento, "seq", seq, "is_edicao", false,
                "houve_anormalidade", houveAnom));
    }

    // ── Editar entrada (tela de detalhe) ──────────────────────

    /**
     * PUT /api/operacao/audio/editar-entrada
     * Equivale a editar_entrada_operacao() do Python.
     * Salva snapshot no histórico antes de aplicar alterações.
     */
    @Transactional
    public Map<String, Object> editarEntrada(long entradaId, Map<String, Object> body, String userId) {
        if (userId == null || userId.isBlank()) throw new ServiceValidationException("Usuário não autenticado.");

        String nomeEvento = blankToNull(clean(body, "nome_evento"));
        String horaInicio = normalizeTime(clean(body, "hora_inicio"));
        String responsavelEvento = blankToNull(clean(body, "responsavel_evento"));

        // Verificar se é multi-operador para relaxar validações
        Long regId = entradaRepo.findRegistroIdByEntradaId(entradaId).orElse(0L);
        boolean isMultiOp = false;
        if (regId > 0) {
            List<?> salaCheck = entityManager.createNativeQuery(
                "SELECT s.MULTI_OPERADOR FROM OPR_REGISTRO_AUDIO r JOIN CAD_SALA s ON s.ID = r.SALA_ID WHERE r.ID = ?1")
                .setParameter(1, regId).getResultList();
            isMultiOp = !salaCheck.isEmpty() && ((Number) salaCheck.get(0)).intValue() == 1;
        }

        Map<String, String> errors = new LinkedHashMap<>();
        if (nomeEvento == null) errors.put("nome_evento", "Campo obrigatório.");
        if (horaInicio == null) errors.put("hora_inicio", "Campo obrigatório.");
        if (!isMultiOp && responsavelEvento == null) errors.put("responsavel_evento", "Campo obrigatório.");
        if (!errors.isEmpty()) throw new ServiceValidationException("Erro de validação.");

        String horarioPauta = normalizeTime(clean(body, "horario_pauta"));
        String horarioTermino = normalizeTime(clean(body, "hora_fim"));
        String usb01 = blankToNull(clean(body, "usb_01"));
        String usb02 = blankToNull(clean(body, "usb_02"));
        String observacoes = blankToNull(clean(body, "observacoes"));
        String horaEntrada = normalizeTime(clean(body, "hora_entrada"));
        String horaSaida = normalizeTime(clean(body, "hora_saida"));
        String tipoEvento = normalizarTipoEvento(clean(body, "tipo_evento"));
        if (tipoEvento.isEmpty()) tipoEvento = "operacao";
        Long comissaoId = parseComissaoId(body);

        // sala_id (opcional, só quando total_entradas = 1)
        Integer novoSalaId = null;
        Object salaIdRaw = body.get("sala_id");
        if (salaIdRaw != null && !salaIdRaw.toString().strip().isEmpty()) {
            try { novoSalaId = Integer.parseInt(salaIdRaw.toString().strip()); }
            catch (Exception e) { novoSalaId = null; }
        }

        int totalEntradas = entradaRepo.countEntradasPorSessao(entradaId);
        if (totalEntradas > 1) {
            novoSalaId = null;
            horarioTermino = null;
        }

        Long registroId = entradaRepo.findRegistroIdByEntradaId(entradaId).orElse(0L);

        // Validação completa de horários (plenários numerados)
        if (!isMultiOp && registroId > 0) {
            List<?> ordemResult = entityManager.createNativeQuery(
                "SELECT ORDEM FROM OPR_REGISTRO_ENTRADA WHERE ID = ?1")
                .setParameter(1, entradaId).getResultList();
            int ordemAtual = !ordemResult.isEmpty() ? ((Number) ordemResult.get(0)).intValue() : 1;
            validarHorarios(horarioPauta, horaInicio, horaEntrada, horarioTermino, horaSaida,
                    ordemAtual, registroId, entradaId);
        }

        // Snapshot para histórico
        List<Object[]> snapRows = entradaRepo.getSnapshot(entradaId);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        if (!snapRows.isEmpty()) {
            Object[] sr = snapRows.get(0);
            snapshot.put("nome_evento", str(sr[0]));
            snapshot.put("responsavel_evento", str(sr[1]));
            snapshot.put("horario_pauta", str(sr[2]));
            snapshot.put("horario_inicio", str(sr[3]));
            snapshot.put("horario_termino", str(sr[4]));
            snapshot.put("tipo_evento", str(sr[5]));
            snapshot.put("usb_01", str(sr[6]));
            snapshot.put("usb_02", str(sr[7]));
            snapshot.put("observacoes", str(sr[8]));
            snapshot.put("comissao_id", sr[9] != null ? ((Number) sr[9]).longValue() : null);
            snapshot.put("houve_anormalidade", sr[10] != null && ((Number) sr[10]).intValue() == 1);
            snapshot.put("sala_id", sr[11] != null ? ((Number) sr[11]).intValue() : null);
            snapshot.put("hora_entrada", str(sr[12]));
            snapshot.put("hora_saida", str(sr[13]));
        }

        // Se total > 1, manter horario_termino original
        if (totalEntradas > 1) {
            horarioTermino = (String) snapshot.get("horario_termino");
        }

        // Salvar histórico
        String snapshotJson;
        try { snapshotJson = objectMapper.writeValueAsString(snapshot); }
        catch (JsonProcessingException e) { snapshotJson = "{}"; }

        RegistroOperacaoOperadorHistorico hist = new RegistroOperacaoOperadorHistorico();
        hist.setEntradaId(entradaId);
        hist.setSnapshot(snapshotJson);
        hist.setEditadoPor(userId);
        entityManager.persist(hist);

        // Update com flags de edição — usa query nativa Oracle (IS DISTINCT FROM → DECODE)
        entityManager.createNativeQuery("""
                UPDATE OPR_REGISTRO_ENTRADA SET
                    NOME_EVENTO = :ne, RESPONSAVEL_EVENTO = :re,
                    HORARIO_PAUTA = :hp, HORARIO_INICIO = :hi, HORARIO_TERMINO = :ht,
                    USB_01 = :u1, USB_02 = :u2, OBSERVACOES = :ob,
                    COMISSAO_ID = :ci, TIPO_EVENTO = :te,
                    HORA_ENTRADA = :he, HORA_SAIDA = :hs,
                    EDITADO = 1,
                    NOME_EVENTO_EDITADO = CASE WHEN NOME_EVENTO_EDITADO = 1 THEN 1
                        WHEN NVL(NOME_EVENTO, ' ') != NVL(:ne2, ' ') THEN 1 ELSE 0 END,
                    RESPONSAVEL_EVENTO_EDITADO = CASE WHEN RESPONSAVEL_EVENTO_EDITADO = 1 THEN 1
                        WHEN NVL(RESPONSAVEL_EVENTO, ' ') != NVL(:re2, ' ') THEN 1 ELSE 0 END,
                    HORARIO_PAUTA_EDITADO = CASE WHEN HORARIO_PAUTA_EDITADO = 1 THEN 1
                        WHEN NVL(HORARIO_PAUTA, ' ') != NVL(:hp2, ' ') THEN 1 ELSE 0 END,
                    HORARIO_INICIO_EDITADO = CASE WHEN HORARIO_INICIO_EDITADO = 1 THEN 1
                        WHEN NVL(HORARIO_INICIO, ' ') != NVL(:hi2, ' ') THEN 1 ELSE 0 END,
                    HORARIO_TERMINO_EDITADO = CASE WHEN HORARIO_TERMINO_EDITADO = 1 THEN 1
                        WHEN NVL(HORARIO_TERMINO, ' ') != NVL(:ht2, ' ') THEN 1 ELSE 0 END,
                    USB_01_EDITADO = CASE WHEN USB_01_EDITADO = 1 THEN 1
                        WHEN NVL(USB_01, ' ') != NVL(:u12, ' ') THEN 1 ELSE 0 END,
                    USB_02_EDITADO = CASE WHEN USB_02_EDITADO = 1 THEN 1
                        WHEN NVL(USB_02, ' ') != NVL(:u22, ' ') THEN 1 ELSE 0 END,
                    OBSERVACOES_EDITADO = CASE WHEN OBSERVACOES_EDITADO = 1 THEN 1
                        WHEN NVL(TO_CHAR(OBSERVACOES), ' ') != NVL(:ob2, ' ') THEN 1 ELSE 0 END,
                    COMISSAO_EDITADO = CASE WHEN COMISSAO_EDITADO = 1 THEN 1
                        WHEN NVL(COMISSAO_ID, -1) != NVL(:ci2, -1) THEN 1 ELSE 0 END,
                    HORA_ENTRADA_EDITADO = CASE WHEN HORA_ENTRADA_EDITADO = 1 THEN 1
                        WHEN NVL(HORA_ENTRADA, ' ') != NVL(:he2, ' ') THEN 1 ELSE 0 END,
                    HORA_SAIDA_EDITADO = CASE WHEN HORA_SAIDA_EDITADO = 1 THEN 1
                        WHEN NVL(HORA_SAIDA, ' ') != NVL(:hs2, ' ') THEN 1 ELSE 0 END,
                    ATUALIZADO_POR = :userId, ATUALIZADO_EM = SYSTIMESTAMP
                WHERE ID = :entradaId
                """)
                .setParameter("ne", nomeEvento).setParameter("re", responsavelEvento)
                .setParameter("hp", horarioPauta).setParameter("hi", horaInicio)
                .setParameter("ht", horarioTermino).setParameter("u1", usb01)
                .setParameter("u2", usb02).setParameter("ob", observacoes)
                .setParameter("ci", comissaoId).setParameter("te", tipoEvento)
                .setParameter("he", horaEntrada).setParameter("hs", horaSaida)
                .setParameter("ne2", nomeEvento).setParameter("re2", responsavelEvento)
                .setParameter("hp2", horarioPauta).setParameter("hi2", horaInicio)
                .setParameter("ht2", horarioTermino).setParameter("u12", usb01)
                .setParameter("u22", usb02).setParameter("ob2", observacoes)
                .setParameter("ci2", comissaoId).setParameter("he2", horaEntrada)
                .setParameter("hs2", horaSaida).setParameter("userId", userId)
                .setParameter("entradaId", entradaId)
                .executeUpdate();

        // Propagar campos compartilhados para outros operadores da sessão (se ordem = 1)
        if (!isMultiOp && registroId > 0) {
            List<?> ordemCheck = entityManager.createNativeQuery(
                "SELECT ORDEM FROM OPR_REGISTRO_ENTRADA WHERE ID = ?1")
                .setParameter(1, entradaId).getResultList();
            int ordemEdit = !ordemCheck.isEmpty() ? ((Number) ordemCheck.get(0)).intValue() : 1;
            if (ordemEdit == 1) {
                propagarCamposSessao(registroId, entradaId, nomeEvento, horarioPauta,
                        horaInicio, tipoEvento, comissaoId, responsavelEvento);
            }
        }

        // Atualizar sala se permitido
        if (novoSalaId != null) {
            entradaRepo.marcarSalaEditado(entradaId, novoSalaId);
            audioRepo.updateSalaByEntrada(entradaId, novoSalaId);
        }

        // Atualizar suspensões se multi-operador
        if (isMultiOp && body.containsKey("suspensoes")) {
            suspensaoRepo.deleteByEntradaId(entradaId);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> suspensoes = body.get("suspensoes") instanceof List
                    ? (List<Map<String, Object>>) body.get("suspensoes") : List.of();
            int ordemSusp = 1;
            for (Map<String, Object> susp : suspensoes) {
                String hs = normalizeTime(susp.get("hora_suspensao") != null ? susp.get("hora_suspensao").toString() : "");
                String hr = normalizeTime(susp.get("hora_reabertura") != null ? susp.get("hora_reabertura").toString() : "");
                if (hs != null || hr != null) {
                    Suspensao s = new Suspensao();
                    s.setEntradaId(entradaId);
                    s.setHoraSuspensao(hs);
                    s.setHoraReabertura(hr);
                    s.setOrdem(ordemSusp++);
                    suspensaoRepo.save(s);
                }
            }
            entityManager.createNativeQuery(
                "UPDATE OPR_REGISTRO_ENTRADA SET SUSPENSOES_EDITADO = 1 WHERE ID = :id")
                .setParameter("id", entradaId).executeUpdate();
        }

        // Atualizar operadores da sessão se multi-operador
        if (isMultiOp && body.containsKey("operadores_sessao_ids")) {
            @SuppressWarnings("unchecked")
            List<String> opIds = body.get("operadores_sessao_ids") instanceof List
                    ? (List<String>) body.get("operadores_sessao_ids") : List.of();
            entradaOperadorRepo.deleteByEntradaId(entradaId);
            for (String opId : opIds) {
                EntradaOperador eo = new EntradaOperador();
                eo.setEntradaId(entradaId);
                eo.setOperadorId(opId);
                entradaOperadorRepo.save(eo);
            }
        }

        boolean houveAnormalidadeAnterior = Boolean.TRUE.equals(snapshot.get("houve_anormalidade"));
        String houveAnomRaw = clean(body, "houve_anormalidade");
        boolean houveAnomNova = "sim".equalsIgnoreCase(houveAnomRaw);
        boolean houveAnormalidadeNova = !houveAnormalidadeAnterior && houveAnomNova;

        return new LinkedHashMap<>(Map.of("entrada_id", entradaId, "registro_id", registroId,
                "houve_anormalidade_nova", houveAnormalidadeNova));
    }

    // ── Finalizar sessão ──────────────────────────────────────

    /**
     * POST /api/operacao/audio/finalizar-sessao
     * Equivale a finalizar_sessao_operacao_audio() do Python.
     */
    @Transactional
    public Map<String, Object> finalizarSessao(int salaId, String userId) {
        if (userId == null || userId.isBlank()) throw new ServiceValidationException("Usuário não autenticado.");

        List<Object[]> sessaoRows = audioRepo.findSessaoAbertaPorSala(salaId);
        if (sessaoRows.isEmpty()) throw new ServiceValidationException("Não existe registro aberto para este local.");

        long registroId = ((Number) sessaoRows.get(0)[0]).longValue();
        audioRepo.finalizarSessao(registroId, userId);

        return new LinkedHashMap<>(Map.of("registro_id", registroId, "sala_id", salaId, "status", "finalizado"));
    }

    // ── Registro de operação (endpoint original) ──────────────

    /**
     * POST /api/operacao/registro
     * Equivale a registrar_operacao_audio() do Python.
     */
    @Transactional
    public Map<String, Object> registrarOperacao(Map<String, Object> body, String userId) {
        String dataOperacao = clean(body, "data_operacao");
        String salaIdRaw = clean(body, "sala_id");
        String nomeEvento = clean(body, "nome_evento");
        String horaInicio = clean(body, "hora_inicio");

        Map<String, String> errors = new LinkedHashMap<>();
        if (dataOperacao.isEmpty()) errors.put("data_operacao", "Campo obrigatório.");
        if (salaIdRaw.isEmpty()) errors.put("sala_id", "Campo obrigatório.");
        if (nomeEvento.isEmpty()) errors.put("nome_evento", "Campo obrigatório.");
        if (horaInicio.isEmpty()) errors.put("hora_inicio", "Campo obrigatório.");

        @SuppressWarnings("unchecked")
        List<String> operadores = (List<String>) body.getOrDefault("operadores", List.of());
        operadores = operadores.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
        if (operadores.isEmpty()) errors.put("operador_1", "Informe pelo menos um operador.");

        if (!errors.isEmpty()) throw new ServiceValidationException("Erros de validação no formulário.");

        int salaId = parseSalaId(salaIdRaw);

        RegistroOperacaoAudio audio = new RegistroOperacaoAudio();
        audio.setData(LocalDate.parse(dataOperacao));
        audio.setSalaId(salaId);
        audio.setEmAberto(true);
        audio.setCriadoPor(userId);

        List<Object[]> checkRows = audioRepo.findChecklistDoDia(dataOperacao, salaId);
        if (!checkRows.isEmpty()) {
            audio.setChecklistDoDiaId(((Number) checkRows.get(0)[0]).longValue());
            audio.setChecklistDoDiaOk(((Number) checkRows.get(0)[1]).intValue() == 1);
        }
        audio = audioRepo.save(audio);
        long registroId = audio.getId();

        int ordem = 1;
        for (String opId : operadores) {
            RegistroOperacaoOperador entrada = new RegistroOperacaoOperador();
            entrada.setRegistroId(registroId);
            entrada.setOperadorId(opId);
            entrada.setOrdem(ordem++);
            entrada.setSeq(1);
            entrada.setTipoEvento(br.leg.senado.nusp.enums.TipoEvento.OPERACAO);
            entrada.setCriadoPor(userId);
            entrada.setAtualizadoPor(userId);
            entradaRepo.save(entrada);
        }

        return new LinkedHashMap<>(Map.of("registro_id", registroId));
    }

    // ── Lookup registro operação (para anormalidade) ──────────

    /**
     * GET /api/forms/lookup/registro-operacao?id=...&entrada_id=...
     * Equivale a lookup_registro_operacao_view() + get_registro_operacao_audio_for_anormalidade().
     */
    public Map<String, Object> lookupRegistroOperacao(long registroId, Long entradaId) {
        List<Object[]> rows = entradaRepo.findDadosParaAnormalidade(registroId, entradaId);
        if (rows.isEmpty()) return null;

        Object[] r = rows.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", ((Number) r[0]).longValue());
        result.put("data", str(r[1]));
        result.put("sala_id", r[2] != null ? ((Number) r[2]).intValue() : null);
        result.put("nome_evento", str(r[3]));
        result.put("responsavel_evento", str(r[4]));
        return result;
    }
}
