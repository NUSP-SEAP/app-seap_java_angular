package br.leg.senado.nusp.controller;

import br.leg.senado.nusp.security.UserPrincipal;
import br.leg.senado.nusp.service.AdminCrudService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Endpoints de CRUD admin — equivale à parte de criação de usuários
 * e form-edit de api/views/admin.py do Python.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminCrudController {

    private final AdminCrudService crudService;
    private final ObjectMapper objectMapper;

    // ══ Criação de Operador ═════════════════════════════════════

    @PostMapping("/operadores/novo")
    public ResponseEntity<?> operadorNovo(
            @RequestParam(value = "nome_completo", required = false) String nomeCompleto,
            @RequestParam(value = "nome_exibicao", required = false) String nomeExibicao,
            @RequestParam(value = "email", required = false) String email,
            @RequestParam(value = "username", required = false) String username,
            @RequestParam(value = "senha", required = false) String senha,
            @RequestParam(value = "foto", required = false) MultipartFile foto) {

        Map<String, Object> operador = crudService.criarOperador(
                nomeCompleto, nomeExibicao, email, username, senha, foto);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("operador", operador);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    // ══ Criação de Administrador ════════════════════════════════

    @PostMapping("/admins/novo")
    public ResponseEntity<?> adminNovo(
            @RequestBody Map<String, String> payload,
            @AuthenticationPrincipal UserPrincipal principal) {

        Map<String, Object> admin = crudService.criarAdministrador(
                payload.get("nome_completo"),
                payload.get("email"),
                payload.get("username"),
                payload.get("senha"),
                principal.getUsername());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("administrador", admin);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    // ══ Form Edit — Listar ══════════════════════════════════════

    @GetMapping("/form-edit/{entidade}/list")
    public ResponseEntity<?> formEditList(@PathVariable String entidade) {
        if ("checklist-itens".equals(entidade)) {
            return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                    "ok", false,
                    "error", "DEPRECATED",
                    "message", "Use /sala-config/<sala_id>/list ao invés."));
        }

        Map<String, Object> data = crudService.listFormEditItems(entidade);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.putAll(data);
        return ResponseEntity.ok(body);
    }

    // ══ Form Edit — Salvar ══════════════════════════════════════

    @PostMapping("/form-edit/{entidade}/save")
    public ResponseEntity<?> formEditSave(
            @PathVariable String entidade,
            @RequestBody Map<String, Object> payload,
            @AuthenticationPrincipal UserPrincipal principal) {

        if ("checklist-itens".equals(entidade)) {
            return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                    "ok", false,
                    "error", "DEPRECATED",
                    "message", "Use /sala-config/<sala_id>/save ao invés."));
        }

        Object itemsRaw = payload.get("items");
        if (!(itemsRaw instanceof List)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "ok", false,
                    "error", "PAYLOAD_INVALIDO",
                    "message", "Campo 'items' é obrigatório e deve ser uma lista."));
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) itemsRaw;

        Map<String, Object> data = crudService.saveFormEditItems(entidade, items, principal.getId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.putAll(data);
        return ResponseEntity.ok(body);
    }

    // ══ Sala Config — Listar ════════════════════════════════════

    @GetMapping("/form-edit/sala-config/{salaId}/list")
    public ResponseEntity<?> salaConfigList(@PathVariable String salaId) {
        int id = parseSalaId(salaId);
        Map<String, Object> data = crudService.listSalaConfigItems(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.putAll(data);
        return ResponseEntity.ok(body);
    }

    // ══ Sala Config — Salvar ════════════════════════════════════

    @PostMapping("/form-edit/sala-config/{salaId}/save")
    public ResponseEntity<?> salaConfigSave(
            @PathVariable String salaId,
            @RequestBody Map<String, Object> payload) {

        int id = parseSalaId(salaId);

        Object itemsRaw = payload.get("items");
        if (!(itemsRaw instanceof List)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "ok", false,
                    "error", "PAYLOAD_INVALIDO",
                    "message", "Campo 'items' é obrigatório e deve ser uma lista."));
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) itemsRaw;

        Map<String, Object> data = crudService.saveSalaConfigItems(id, items);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.putAll(data);
        return ResponseEntity.ok(body);
    }

    // ══ Sala Config — Aplicar a Todas ═══════════════════════════

    @PostMapping("/form-edit/sala-config/aplicar-todas")
    public ResponseEntity<?> salaConfigAplicarTodas(@RequestBody Map<String, Object> payload) {
        Object sourceSalaIdRaw = payload.get("source_sala_id");
        if (sourceSalaIdRaw == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "ok", false,
                    "error", "PAYLOAD_INVALIDO",
                    "message", "Campo 'source_sala_id' é obrigatório."));
        }

        int sourceSalaId = parseSalaId(sourceSalaIdRaw.toString());

        Object itemsRaw = payload.get("items");
        if (!(itemsRaw instanceof List)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "ok", false,
                    "error", "PAYLOAD_INVALIDO",
                    "message", "Campo 'items' é obrigatório e deve ser uma lista."));
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) itemsRaw;

        Map<String, Object> data = crudService.applySalaConfigToAll(sourceSalaId, items);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.putAll(data);
        return ResponseEntity.ok(body);
    }

    // ══ Helper ══════════════════════════════════════════════════

    private int parseSalaId(String raw) {
        try {
            int id = Integer.parseInt(raw);
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException e) {
            return -1; // O service vai validar e lançar erro
        }
    }
}
