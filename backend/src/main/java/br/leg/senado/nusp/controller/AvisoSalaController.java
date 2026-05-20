package br.leg.senado.nusp.controller;

import br.leg.senado.nusp.security.UserPrincipal;
import br.leg.senado.nusp.service.AvisoSalaService;
import br.leg.senado.nusp.service.DashboardQueryHelper.PagedResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Endpoints do Aviso de Sala.
 * Admin: CRUD em /api/admin/avisos-sala/**
 * Operador: consulta/ciência em /api/forms/checklist/aviso/**
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Aviso de Sala",
     description = "Avisos cadastrados pelo admin e exibidos ao operador antes da Verificação de Plenários")
public class AvisoSalaController {

    private final AvisoSalaService avisoService;
    private final ObjectMapper objectMapper;

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) return null;
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception e) { return null; }
    }

    // ══ Admin ═══════════════════════════════════════════════════

    @GetMapping("/api/admin/avisos-sala/list")
    public ResponseEntity<?> listar(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "data") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) String filters) {
        PagedResult r = avisoService.listarTodosPaginado(page, limit, search, sort, direction, parseJson(filters));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("data", r.data());
        body.put("meta", Map.of(
                "distinct", r.distinct(),
                "page", page, "limit", limit,
                "total", r.total(),
                "pages", limit > 0 ? (r.total() + limit - 1) / limit : 1
        ));
        return ResponseEntity.ok(body);
    }

    @PostMapping("/api/admin/avisos-sala")
    public ResponseEntity<?> criar(
            @RequestBody Map<String, Object> payload,
            @AuthenticationPrincipal UserPrincipal principal) {
        Integer salaId = payload.get("sala_id") != null
                ? Integer.valueOf(payload.get("sala_id").toString()) : null;
        String mensagem = payload.get("mensagem") != null ? payload.get("mensagem").toString() : null;
        Integer duracao = payload.get("duracao_dias") != null
                ? Integer.valueOf(payload.get("duracao_dias").toString()) : null;
        var data = avisoService.criar(salaId, mensagem, duracao, principal.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("ok", true, "data", data));
    }

    @PatchMapping("/api/admin/avisos-sala/{id}/desativar")
    public ResponseEntity<?> desativar(@PathVariable String id) {
        avisoService.desativar(id);
        return ResponseEntity.ok(Map.of("ok", true, "message", "Aviso desativado."));
    }

    @GetMapping("/api/admin/avisos-sala/{id}/cientes")
    public ResponseEntity<?> cientes(@PathVariable String id) {
        return ResponseEntity.ok(Map.of("ok", true, "data", avisoService.listarCientes(id)));
    }

    // ══ Operador ════════════════════════════════════════════════

    /**
     * Retorna o aviso ativo pendente de ciência para o operador logado.
     * Se não houver aviso ou se o operador já marcou ciente, retorna data=null.
     */
    @GetMapping("/api/forms/checklist/aviso-pendente")
    public ResponseEntity<?> avisoPendente(
            @RequestParam("sala_id") Integer salaId,
            @AuthenticationPrincipal UserPrincipal principal) {
        var aviso = avisoService.buscarPendenteParaOperador(salaId, principal.getId());
        return ResponseEntity.ok(Map.of("ok", true, "data", aviso.orElse(null)));
    }

    @PostMapping("/api/forms/checklist/aviso/{id}/ciencia")
    public ResponseEntity<?> registrarCiencia(
            @PathVariable("id") String avisoId,
            @AuthenticationPrincipal UserPrincipal principal) {
        avisoService.registrarCiencia(avisoId, principal.getId());
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
