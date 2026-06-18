package br.leg.senado.nusp.controller;

import br.leg.senado.nusp.enums.PapelPessoa;
import br.leg.senado.nusp.security.UserPrincipal;
import br.leg.senado.nusp.service.AvisoService;
import br.leg.senado.nusp.service.DashboardQueryHelper.PagedResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Endpoints do sistema de avisos.
 * Admin:    CRUD em /api/admin/avisos/**
 * Operador: consulta/ciência em /api/forms/checklist/aviso/**
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Avisos",
     description = "Avisos cadastrados pelo admin e exibidos aos destinatários (operadores/técnicos)")
public class AvisoController {

    private final AvisoService avisoService;
    private final ObjectMapper objectMapper;

    // ══ Admin ═══════════════════════════════════════════════════

    @GetMapping("/api/admin/avisos/list")
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

    @PostMapping("/api/admin/avisos")
    public ResponseEntity<?> criar(
            @RequestBody Map<String, Object> payload,
            @AuthenticationPrincipal UserPrincipal principal) {
        var req = new AvisoService.CriarAvisoRequest(
                asStr(payload.get("tipo")),
                asBool(payload.get("permanente")),
                asInt(payload.get("duracao_dias")),
                asBool(payload.get("manter_apos_ciencia")),
                strList(payload.get("mensagens")),
                asStr(payload.get("alvo_tipo")),
                intList(payload.get("sala_ids")),
                strList(payload.get("operador_ids")),
                strList(payload.get("tecnico_ids")));
        var data = avisoService.criar(req, principal.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("ok", true, "data", data));
    }

    @GetMapping("/api/admin/avisos/{id}/detalhe")
    public ResponseEntity<?> detalhe(@PathVariable String id) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("data", avisoService.obterDetalhe(id));
        return ResponseEntity.ok(body);
    }

    @PatchMapping("/api/admin/avisos/{id}/desativar")
    public ResponseEntity<?> desativar(@PathVariable String id) {
        avisoService.desativar(id);
        return ResponseEntity.ok(Map.of("ok", true, "message", "Aviso desativado."));
    }

    /** Salas com aviso ativo (para desabilitar no form do admin). */
    @GetMapping("/api/admin/avisos/salas-ocupadas")
    public ResponseEntity<?> salasOcupadas(@RequestParam(defaultValue = "VERIFICACAO") String tipo) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("data", avisoService.salasOcupadas(tipo));
        return ResponseEntity.ok(body);
    }

    // ══ Operador (verificação) ══════════════════════════════════

    /**
     * Retorna o aviso de verificação pendente de ciência para o operador logado
     * na sala informada. Se não houver, retorna data=null.
     */
    @GetMapping("/api/forms/checklist/aviso-pendente")
    public ResponseEntity<?> avisoPendente(
            @RequestParam("sala_id") Integer salaId,
            @AuthenticationPrincipal UserPrincipal principal) {
        var aviso = avisoService.buscarPendenteVerificacao(salaId, principal.getId(), PapelPessoa.OPERADOR);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("data", aviso.orElse(null));
        return ResponseEntity.ok(body);
    }

    @PostMapping("/api/forms/checklist/aviso/{cadastroId}/ciencia")
    public ResponseEntity<?> registrarCiencia(
            @PathVariable("cadastroId") String cadastroId,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal UserPrincipal principal) {
        Integer salaId = body != null ? asInt(body.get("sala_id")) : null;
        avisoService.registrarCiencia(cadastroId, salaId, principal.getId(), PapelPessoa.OPERADOR);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ══ Helpers de parsing ══════════════════════════════════════

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) return null;
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception e) { return null; }
    }

    private String asStr(Object o) {
        return o == null ? null : o.toString();
    }

    private Boolean asBool(Object o) {
        if (o == null) return null;
        if (o instanceof Boolean b) return b;
        String s = o.toString().trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    private Integer asInt(Object o) {
        if (o == null) return null;
        try { return Integer.valueOf(o.toString().trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private List<String> strList(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        List<String> r = new ArrayList<>();
        for (Object x : l) if (x != null) r.add(x.toString());
        return r;
    }

    private List<Integer> intList(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        List<Integer> r = new ArrayList<>();
        for (Object x : l) {
            if (x == null) continue;
            try { r.add(Integer.valueOf(x.toString().trim())); }
            catch (NumberFormatException ignored) { /* ignora item inválido */ }
        }
        return r;
    }
}
