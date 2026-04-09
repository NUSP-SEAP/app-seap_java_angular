package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.*;;
import br.leg.senado.nusp.enums.StatusResposta;
import br.leg.senado.nusp.enums.Turno;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/**
 * Equivale ao checklist_service.py do Python (233 linhas).
 * Lógica de registro e edição de checklists "Testes Diários".
 */
@Service
@RequiredArgsConstructor
public class ChecklistService {

    private final ChecklistRepository checklistRepo;
    private final ChecklistRespostaRepository respostaRepo;
    private final ChecklistItemTipoRepository itemTipoRepo;
    private final ChecklistOperadorRepository checklistOperadorRepo;
    private final SalaRepository salaRepo;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    // ── Validação de itens ────────────────────────

    /**
     * Equivale a _validar_itens_checklist() do Python.
     * Valida a lista de itens e retorna o total de itens marcados.
     */
    private int validarItens(List<Map<String, Object>> itens) {
        List<Integer> invalid = new ArrayList<>();
        List<Integer> falhaSemDesc = new ArrayList<>();
        int totalMarcados = 0;

        for (int idx = 0; idx < itens.size(); idx++) {
            Map<String, Object> it = itens.get(idx);
            if (it == null) { invalid.add(idx); continue; }

            Object itemId = it.get("item_tipo_id");
            String nome = str(it.get("nome")).strip();
            if (itemId == null && nome.isEmpty()) { invalid.add(idx); continue; }

            String status = str(it.get("status")).strip();
            String descFalha = str(it.get("descricao_falha")).strip();
            String valorTexto = str(it.get("valor_texto")).strip();

            if (!status.isEmpty() || !valorTexto.isEmpty()) {
                totalMarcados++;
                if ("falha".equalsIgnoreCase(status) && (descFalha.isEmpty() || descFalha.length() < 10)) {
                    falhaSemDesc.add(idx);
                }
            }
        }

        if (!invalid.isEmpty())
            throw new ServiceValidationException("Itens de checklist inválidos (deve conter 'item_tipo_id').");
        if (totalMarcados == 0)
            throw new ServiceValidationException("Pelo menos um item do checklist deve ser preenchido.");
        if (!falhaSemDesc.isEmpty())
            throw new ServiceValidationException("Itens marcados como Falha precisam de descrição com no mínimo 10 caracteres.");

        return totalMarcados;
    }

    private static String str(Object o) { String s = NativeQueryUtils.str(o); return s == null ? "" : s; }
    private static String blankToNull(String s) { return (s == null || s.isBlank()) ? null : s.strip(); }

    /** Resolve item_tipo_id (pelo id direto ou pelo nome). */
    private Integer resolveItemTipoId(Map<String, Object> item, Map<String, Integer> nomeToId) {
        Object idRaw = item.get("item_tipo_id");
        if (idRaw != null) {
            try { return Integer.parseInt(idRaw.toString()); }
            catch (Exception e) { return null; }
        }
        String nome = str(item.get("nome")).strip();
        return nomeToId.get(nome);
    }

    // ── Registrar checklist ──────────────────────

    /**
     * POST /api/forms/checklist/registro
     * Equivale a registrar_checklist() do Python.
     */
    @Transactional
    public Map<String, Object> registrar(Map<String, Object> body, String userId) {
        // Campos obrigatórios
        List<String> reqFields = List.of("data_operacao", "sala_id", "hora_inicio_testes", "hora_termino_testes");
        List<String> missing = reqFields.stream().filter(k -> str(body.get(k)).strip().isEmpty()).toList();
        if (!missing.isEmpty())
            throw new ServiceValidationException("Campos obrigatórios ausentes.");

        String dataOperacao = str(body.get("data_operacao")).strip();
        int salaId;
        try { salaId = Integer.parseInt(str(body.get("sala_id")).strip()); }
        catch (Exception e) { throw new ServiceValidationException("Local inválido."); }

        String horaInicio = str(body.get("hora_inicio_testes")).strip();
        String horaTermino = str(body.get("hora_termino_testes")).strip();
        String observacoes = blankToNull(str(body.get("observacoes")));
        String usb01 = blankToNull(str(body.get("usb_01")));
        String usb02 = blankToNull(str(body.get("usb_02")));

        // Turno: infere pela hora se não informado
        String turnoRaw = str(body.get("turno")).strip();
        if (turnoRaw.isEmpty()) {
            try {
                int hora = Integer.parseInt(horaInicio.split(":")[0]);
                turnoRaw = hora < 13 ? "Matutino" : "Vespertino";
            } catch (Exception e) { turnoRaw = "Matutino"; }
        }
        Turno turno = Turno.fromValor(turnoRaw);

        // Itens
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> itens = (List<Map<String, Object>>) body.getOrDefault("itens", List.of());
        validarItens(itens);

        // Verificar duplicata (mesmo operador + mesma sala nos últimos 5 minutos)
        Number dupCheck = (Number) entityManager.createNativeQuery("""
                SELECT CASE WHEN EXISTS (
                    SELECT 1 FROM FRM_CHECKLIST
                    WHERE SALA_ID = ?1 AND CRIADO_POR = ?2
                    AND CRIADO_EM >= SYSTIMESTAMP - INTERVAL '5' MINUTE
                ) THEN 1 ELSE 0 END FROM DUAL
                """).setParameter(1, salaId).setParameter(2, userId).getSingleResult();
        if (dupCheck.intValue() == 1) {
            throw new ServiceValidationException(
                "Já existe uma verificação sua para este local enviada há menos de 5 minutos. Aguarde antes de enviar novamente.");
        }

        // Mapa nome → id para resolução de itens legados
        Map<String, Integer> nomeToId = new HashMap<>();
        itemTipoRepo.findAllOrdered().forEach(t -> nomeToId.put(t.getNome(), t.getId()));

        // Inserir cabeçalho
        Checklist checklist = new Checklist();
        checklist.setDataOperacao(LocalDate.parse(dataOperacao));
        checklist.setSalaId(salaId);
        checklist.setTurno(turno);
        checklist.setHoraInicioTestes(horaInicio);
        checklist.setHoraTerminoTestes(horaTermino);
        checklist.setObservacoes(observacoes);
        checklist.setUsb01(usb01);
        checklist.setUsb02(usb02);
        checklist.setCriadoPor(userId);
        checklist.setAtualizadoPor(userId);
        checklist = checklistRepo.save(checklist);

        // Inserir respostas
        int totalRespostas = 0;
        for (Map<String, Object> item : itens) {
            Integer tipoId = resolveItemTipoId(item, nomeToId);
            if (tipoId == null) continue;
            String status = str(item.get("status")).strip();
            String valorTexto = str(item.get("valor_texto")).strip();
            if (status.isEmpty() && !valorTexto.isEmpty()) status = "Ok";
            if (status.isEmpty()) continue;

            ChecklistResposta resp = new ChecklistResposta();
            resp.setChecklistId(checklist.getId());
            resp.setItemTipoId(tipoId);
            resp.setStatus(StatusResposta.fromValor(status));
            resp.setDescricaoFalha(blankToNull(str(item.get("descricao_falha"))));
            resp.setValorTexto(blankToNull(valorTexto));
            resp.setCriadoPor(userId);
            resp.setAtualizadoPor(userId);
            respostaRepo.save(resp);
            totalRespostas++;
        }

        // Salvar operadores da junction table (Plenário Principal)
        Sala sala = salaRepo.findById(salaId).orElse(null);
        if (sala != null && Boolean.TRUE.equals(sala.getMultiOperador())) {
            @SuppressWarnings("unchecked")
            List<String> cabineOps = body.get("operadores_cabine") instanceof List
                    ? (List<String>) body.get("operadores_cabine") : List.of();
            @SuppressWarnings("unchecked")
            List<String> plenarioOps = body.get("operadores_plenario") instanceof List
                    ? (List<String>) body.get("operadores_plenario") : List.of();

            for (String opId : cabineOps) {
                ChecklistOperador co = new ChecklistOperador();
                co.setChecklistId(checklist.getId());
                co.setOperadorId(opId);
                co.setPapel("CABINE");
                checklistOperadorRepo.save(co);
            }
            for (String opId : plenarioOps) {
                ChecklistOperador co = new ChecklistOperador();
                co.setChecklistId(checklist.getId());
                co.setOperadorId(opId);
                co.setPapel("PLENARIO");
                checklistOperadorRepo.save(co);
            }
        }

        return Map.of("checklist_id", checklist.getId(), "total_respostas", totalRespostas);
    }

    // ── Editar checklist ─────────────────────────

    /**
     * PUT /api/forms/checklist/editar
     * Equivale a editar_checklist() do Python.
     * Salva snapshot no histórico antes de aplicar alterações.
     */
    @Transactional
    public Map<String, Object> editar(long checklistId, Map<String, Object> body, String userId) {
        // Campos obrigatórios
        if (str(body.get("data_operacao")).strip().isEmpty() || str(body.get("sala_id")).strip().isEmpty())
            throw new ServiceValidationException("Campos obrigatórios ausentes.");

        String dataOperacao = str(body.get("data_operacao")).strip();
        int salaId;
        try { salaId = Integer.parseInt(str(body.get("sala_id")).strip()); }
        catch (Exception e) { throw new ServiceValidationException("Local inválido."); }
        String observacoes = blankToNull(str(body.get("observacoes")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> itens = (List<Map<String, Object>>) body.getOrDefault("itens", List.of());
        validarItens(itens);

        // Snapshot para histórico
        Checklist cl = checklistRepo.findById(checklistId).orElseThrow(() ->
                new ServiceValidationException("Checklist não encontrado."));

        List<Object[]> respostasRows = respostaRepo.findByChecklistIdNative(checklistId);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("data_operacao", cl.getDataOperacao() != null ? cl.getDataOperacao().toString() : null);
        header.put("sala_id", cl.getSalaId());
        header.put("turno", cl.getTurno() != null ? cl.getTurno().getValor() : null);
        header.put("hora_inicio_testes", cl.getHoraInicioTestes());
        header.put("hora_termino_testes", cl.getHoraTerminoTestes());
        header.put("observacoes", cl.getObservacoes());
        header.put("usb_01", cl.getUsb01());
        header.put("usb_02", cl.getUsb02());
        snapshot.put("header", header);
        List<Map<String, Object>> snapItens = new ArrayList<>();
        for (Object[] r : respostasRows) {
            Map<String, Object> si = new LinkedHashMap<>();
            si.put("resposta_id", ((Number) r[0]).longValue());
            si.put("item_tipo_id", ((Number) r[1]).intValue());
            si.put("status", NativeQueryUtils.str(r[2]));
            si.put("descricao_falha", NativeQueryUtils.str(r[3]));
            si.put("valor_texto", NativeQueryUtils.str(r[4]));
            snapItens.add(si);
        }
        snapshot.put("itens", snapItens);

        // Snapshot dos operadores (se multi-operador)
        List<ChecklistOperador> opSnap = checklistOperadorRepo.findByChecklistId(checklistId);
        if (!opSnap.isEmpty()) {
            List<String> snapCabine = new ArrayList<>(), snapPlenario = new ArrayList<>();
            for (ChecklistOperador co : opSnap) {
                if ("CABINE".equals(co.getPapel())) snapCabine.add(co.getOperadorId());
                else snapPlenario.add(co.getOperadorId());
            }
            snapshot.put("operadores_cabine", snapCabine);
            snapshot.put("operadores_plenario", snapPlenario);
        }

        // Salvar histórico
        String snapshotJson;
        try { snapshotJson = objectMapper.writeValueAsString(snapshot); }
        catch (JsonProcessingException e) { snapshotJson = "{}"; }

        ChecklistHistorico hist = new ChecklistHistorico();
        hist.setChecklistId(checklistId);
        hist.setSnapshot(snapshotJson);
        hist.setEditadoPor(userId);
        entityManager.persist(hist);

        // Atualizar cabeçalho com detecção de mudança em observacoes
        String obsAnterior = cl.getObservacoes() != null ? cl.getObservacoes() : "";
        String obsNova = observacoes != null ? observacoes : "";
        boolean obsMudou = !obsAnterior.equals(obsNova);

        cl.setDataOperacao(LocalDate.parse(dataOperacao));
        cl.setSalaId(salaId);
        cl.setObservacoes(observacoes);
        cl.setEditado(true);
        if (obsMudou) cl.setObservacoesEditado(true);
        cl.setAtualizadoPor(userId);
        checklistRepo.save(cl);

        // Atualizar respostas com detecção de mudança
        int totalAtualizado = 0;
        for (Map<String, Object> item : itens) {
            Integer tipoId = resolveItemTipoId(item, Map.of());
            if (tipoId == null) {
                // Tenta pelo nome
                Object idRaw = item.get("item_tipo_id");
                if (idRaw != null) {
                    try { tipoId = Integer.parseInt(idRaw.toString()); } catch (Exception e) { continue; }
                }
                if (tipoId == null) continue;
            }

            String status = str(item.get("status")).strip();
            String valorTexto = str(item.get("valor_texto")).strip();
            if (status.isEmpty() && !valorTexto.isEmpty()) status = "Ok";
            if (status.isEmpty()) continue;

            String descFalha = blankToNull(str(item.get("descricao_falha")));
            String valorTextoNull = blankToNull(valorTexto);

            Optional<ChecklistResposta> optResp = respostaRepo.findByChecklistAndItem(checklistId, tipoId);
            if (optResp.isPresent()) {
                ChecklistResposta resp = optResp.get();
                // Detectar mudanças
                boolean mudou = !status.equals(resp.getStatus() != null ? resp.getStatus().getValor() : "")
                        || !Objects.equals(descFalha, resp.getDescricaoFalha())
                        || !Objects.equals(valorTextoNull, resp.getValorTexto());

                resp.setStatus(StatusResposta.fromValor(status));
                resp.setDescricaoFalha(descFalha);
                resp.setValorTexto(valorTextoNull);
                if (mudou) resp.setEditado(true);
                resp.setAtualizadoPor(userId);
                respostaRepo.save(resp);
                totalAtualizado++;
            }
        }

        // Atualizar operadores da junction table (Plenário Principal)
        Sala sala = salaRepo.findById(salaId).orElse(null);
        if (sala != null && Boolean.TRUE.equals(sala.getMultiOperador())) {
            @SuppressWarnings("unchecked")
            List<String> cabineOps = body.get("operadores_cabine") instanceof List
                    ? (List<String>) body.get("operadores_cabine") : null;
            @SuppressWarnings("unchecked")
            List<String> plenarioOps = body.get("operadores_plenario") instanceof List
                    ? (List<String>) body.get("operadores_plenario") : null;

            // Só atualiza se o frontend enviou os dados
            if (cabineOps != null || plenarioOps != null) {
                checklistOperadorRepo.deleteByChecklistId(checklistId);

                if (cabineOps != null) {
                    for (String opId : cabineOps) {
                        ChecklistOperador co = new ChecklistOperador();
                        co.setChecklistId(checklistId);
                        co.setOperadorId(opId);
                        co.setPapel("CABINE");
                        checklistOperadorRepo.save(co);
                    }
                }
                if (plenarioOps != null) {
                    for (String opId : plenarioOps) {
                        ChecklistOperador co = new ChecklistOperador();
                        co.setChecklistId(checklistId);
                        co.setOperadorId(opId);
                        co.setPapel("PLENARIO");
                        checklistOperadorRepo.save(co);
                    }
                }
            }
        }

        return new LinkedHashMap<>(Map.of("checklist_id", checklistId, "total_respostas_atualizadas", totalAtualizado));
    }

    // ── Itens tipo por sala ──────────────────────

    /**
     * GET /api/forms/checklist/itens-tipo?sala_id=...
     * Equivale a checklist_itens_tipo_view() + list_checklist_itens_por_sala() do Python.
     */
    public List<Map<String, Object>> itensTipoPorSala(int salaId) {
        List<Object[]> rows = itemTipoRepo.findItensPorSala(salaId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ((Number) r[0]).intValue());
            m.put("nome", r[1] != null ? r[1].toString() : "");
            m.put("ordem", r[2] != null ? ((Number) r[2]).intValue() : null);
            m.put("tipo_widget", r[3] != null ? r[3].toString() : "radio");
            result.add(m);
        }
        return result;
    }
}
