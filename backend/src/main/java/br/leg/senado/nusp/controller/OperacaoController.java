package br.leg.senado.nusp.controller;

import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.Sala;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.repository.RegistroOperacaoOperadorRepository;
import br.leg.senado.nusp.repository.SalaRepository;
import br.leg.senado.nusp.security.UserPrincipal;
import br.leg.senado.nusp.service.OperacaoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Endpoints de Operação de Áudio — equivale a api/views/operacao.py do Python.
 * 6 endpoints: estado-sessao, salvar-entrada, finalizar-sessao, editar-entrada,
 * registro (original), lookup registro-operacao.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class OperacaoController {

    private final OperacaoService operacaoService;
    private final RegistroOperacaoOperadorRepository entradaRepo;
    private final OperadorRepository operadorRepo;
    private final SalaRepository salaRepo;

    // ══ Lookups autenticados para operadores ═════════════════════

    /**
     * GET /api/operacao/lookup/salas
     * Retorna salas filtradas: se operador não tem PLENARIO_PRINCIPAL,
     * esconde salas com MULTI_OPERADOR=true.
     * Inclui flag multi_operador para o frontend adaptar o formulário.
     */
    @GetMapping("/operacao/lookup/salas")
    public ResponseEntity<?> salasParaOperador(@AuthenticationPrincipal UserPrincipal principal) {
        Operador op = operadorRepo.findById(principal.getId()).orElse(null);
        boolean isPlenario = op != null && Boolean.TRUE.equals(op.getPlenarioPrincipal());
        List<Sala> salas = isPlenario
                ? salaRepo.findAtivasOrdenadas()
                : salaRepo.findAtivasOrdenadasSemMultiOperador();
        List<Map<String, Object>> data = salas.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", String.valueOf(s.getId()));
            m.put("nome", s.getNome());
            m.put("multi_operador", Boolean.TRUE.equals(s.getMultiOperador()));
            return m;
        }).toList();
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    /**
     * GET /api/operacao/lookup/operadores-plenario
     * Retorna operadores com PLENARIO_PRINCIPAL=true (para multi-select).
     */
    @GetMapping("/operacao/lookup/operadores-plenario")
    public ResponseEntity<?> operadoresPlenario() {
        List<Map<String, Object>> data = operadorRepo.findOperadoresPlenarioPrincipal()
                .stream().map(o -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", o.getId());
                    m.put("nome_completo", o.getNomeCompleto());
                    return m;
                }).toList();
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    // ══ Operação de Áudio ═══════════════════════════════════════

    /**
     * GET /api/operacao/audio/estado-sessao?sala_id=...
     * Equivale a estado_sessao_operacao_audio_view() do Python.
     */
    @GetMapping("/operacao/audio/estado-sessao")
    public ResponseEntity<?> estadoSessao(
            @RequestParam("sala_id") int salaId,
            @AuthenticationPrincipal UserPrincipal principal) {
        Map<String, Object> estado = operacaoService.obterEstadoSessao(salaId, principal.getId());
        return ResponseEntity.ok(Map.of("ok", true, "data", estado));
    }

    /**
     * POST /api/operacao/audio/salvar-entrada
     * Equivale a salvar_entrada_operacao_audio_view() do Python.
     */
    @PostMapping("/operacao/audio/salvar-entrada")
    public ResponseEntity<?> salvarEntrada(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal UserPrincipal principal) {
        Map<String, Object> result = operacaoService.salvarEntrada(body, principal.getId());
        boolean isEdicao = Boolean.TRUE.equals(result.get("is_edicao"));
        result.put("ok", true);
        return ResponseEntity.status(isEdicao ? 200 : 201).body(result);
    }

    /**
     * POST /api/operacao/audio/finalizar-sessao
     * Equivale a finalizar_sessao_operacao_audio_view() do Python.
     */
    @PostMapping("/operacao/audio/finalizar-sessao")
    public ResponseEntity<?> finalizarSessao(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal UserPrincipal principal) {
        Object salaIdRaw = body.get("sala_id");
        if (salaIdRaw == null) return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "sala_id inválido ou ausente."));
        int salaId;
        try { salaId = Integer.parseInt(salaIdRaw.toString()); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "sala_id inválido.")); }

        Map<String, Object> result = operacaoService.finalizarSessao(salaId, principal.getId());
        result.put("ok", true);
        return ResponseEntity.ok(result);
    }

    /**
     * PUT /api/operacao/audio/editar-entrada
     * Equivale a entrada_operacao_editar_view() do Python.
     */
    @PutMapping("/operacao/audio/editar-entrada")
    public ResponseEntity<?> editarEntrada(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal UserPrincipal principal) {
        Object entradaIdRaw = body.get("entrada_id");
        if (entradaIdRaw == null) return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "entrada_id obrigatório"));
        long entradaId;
        try { entradaId = Long.parseLong(entradaIdRaw.toString()); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "entrada_id inválido")); }

        // Verificação de ownership
        String operadorOwner = entradaRepo.findOperadorIdByEntradaId(entradaId).orElse(null);
        if (operadorOwner == null) return ResponseEntity.status(404).body(Map.of("ok", false, "error", "not_found"));
        if (!operadorOwner.equals(principal.getId())) return ResponseEntity.status(403).body(Map.of("ok", false, "error", "forbidden"));

        Map<String, Object> result = operacaoService.editarEntrada(entradaId, body, principal.getId());
        result.put("ok", true);
        return ResponseEntity.ok(result);
    }

    /**
     * POST /api/operacao/registro
     * Endpoint original de cadastro do formulário "Registro de Operação de Áudio".
     * Equivale a registro_operacao_audio_view() do Python.
     */
    @PostMapping("/operacao/registro")
    public ResponseEntity<?> registroOriginal(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal UserPrincipal principal) {
        Map<String, Object> result = operacaoService.registrarOperacao(body, principal.getId());
        result.put("ok", true);
        return ResponseEntity.status(201).body(result);
    }

    /**
     * GET /api/forms/lookup/registro-operacao?id=...&entrada_id=...
     * Equivale a lookup_registro_operacao_view() do Python.
     * Nota: movido para OperacaoController pois depende de lógica de operação.
     */
    @GetMapping("/forms/lookup/registro-operacao")
    public ResponseEntity<?> lookupRegistroOperacao(
            @RequestParam(value = "id", required = false) Long id,
            @RequestParam(value = "registro_id", required = false) Long registroId,
            @RequestParam(value = "entrada_id", required = false) Long entradaId) {
        long rid = id != null ? id : (registroId != null ? registroId : 0);
        if (rid == 0) return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Parâmetro 'id' é obrigatório."));

        Map<String, Object> data = operacaoService.lookupRegistroOperacao(rid, entradaId);
        if (data == null) return ResponseEntity.status(404).body(Map.of("ok", false, "error", "not_found"));
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }
}
