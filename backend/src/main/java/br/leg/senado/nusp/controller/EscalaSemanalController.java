package br.leg.senado.nusp.controller;

import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.security.UserPrincipal;
import br.leg.senado.nusp.service.EscalaSemanalService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/**
 * Endpoints da Escala Semanal.
 * Admin: CRUD completo em /api/admin/escala/**
 * Operador: consulta da própria escala em /api/escala/minha
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Escala Semanal", description = "Gerenciamento da escala semanal de operadores por plenário")
public class EscalaSemanalController {

    private final EscalaSemanalService escalaService;
    private final OperadorRepository operadorRepo;

    // ══ Admin — Listar ══════════════════════════════════════════

    @GetMapping("/api/admin/escala/list")
    public ResponseEntity<?> listar(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit) {
        var r = escalaService.listarEscalasPaginado(page, limit);
        return ResponseEntity.ok(Map.of("ok", true, "data", r.get("data"), "meta", r.get("meta")));
    }

    // ══ Admin — Obter ═══════════════════════════════════════════

    @GetMapping("/api/admin/escala/{id}")
    public ResponseEntity<?> obter(@PathVariable Long id) {
        return ResponseEntity.ok(Map.of("ok", true, "data", escalaService.obterEscala(id)));
    }

    // ══ Admin — Salvar (criar ou atualizar) ═════════════════════

    @PostMapping("/api/admin/escala/save")
    public ResponseEntity<?> salvar(
            @RequestBody Map<String, Object> payload,
            @AuthenticationPrincipal UserPrincipal principal) {

        Long id = payload.get("id") != null ? Long.valueOf(payload.get("id").toString()) : null;
        LocalDate dataInicio = LocalDate.parse(payload.get("data_inicio").toString());
        LocalDate dataFim = LocalDate.parse(payload.get("data_fim").toString());

        // Converter salas: { "3": ["uuid1","uuid2"], "4": ["uuid3"] }
        Map<Integer, List<String>> salasOperadores = new LinkedHashMap<>();
        Object salasRaw = payload.get("salas");
        if (salasRaw instanceof Map<?, ?> salasMap) {
            for (var entry : salasMap.entrySet()) {
                int salaId = Integer.parseInt(entry.getKey().toString());
                @SuppressWarnings("unchecked")
                List<String> ops = (List<String>) entry.getValue();
                salasOperadores.put(salaId, ops);
            }
        }

        var result = escalaService.salvarEscala(id, dataInicio, dataFim, salasOperadores, principal.getUsername());
        HttpStatus status = id != null ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(Map.of("ok", true, "data", result));
    }

    // ══ Admin — Excluir ═════════════════════════════════════════

    @DeleteMapping("/api/admin/escala/{id}")
    public ResponseEntity<?> excluir(@PathVariable Long id) {
        escalaService.excluirEscala(id);
        return ResponseEntity.ok(Map.of("ok", true, "message", "Escala excluída com sucesso."));
    }

    // ══ Admin — Gerar escala por rodízio ════════════════════════

    @PostMapping("/api/admin/escala/rodizio")
    public ResponseEntity<?> gerarRodizio(
            @RequestBody Map<String, Object> payload,
            @AuthenticationPrincipal UserPrincipal principal) {

        LocalDate dataInicio = LocalDate.parse(payload.get("data_inicio").toString());
        LocalDate dataFim = LocalDate.parse(payload.get("data_fim").toString());
        var result = escalaService.gerarEscalaRodizio(dataInicio, dataFim, principal.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("ok", true, "data", result));
    }

    // ══ Admin — Operadores escalados hoje (por sala) ═════════════

    @GetMapping("/api/admin/escala/operadores-hoje")
    public ResponseEntity<?> operadoresHoje() {
        return ResponseEntity.ok(Map.of("ok", true, "data", escalaService.operadoresEscaladosHoje()));
    }

    // ══ Operador — Listar escalas (somente leitura) ══════════════

    @GetMapping("/api/escala/list")
    public ResponseEntity<?> listarParaOperador(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit) {
        var r = escalaService.listarEscalasPaginado(page, limit);
        return ResponseEntity.ok(Map.of("ok", true, "data", r.get("data"), "meta", r.get("meta")));
    }

    @GetMapping("/api/escala/{id}")
    public ResponseEntity<?> obterParaOperador(@PathVariable Long id) {
        return ResponseEntity.ok(Map.of("ok", true, "data", escalaService.obterEscala(id)));
    }

    // ══ Operador — Minha escala de hoje ═════════════════════════

    @GetMapping("/api/escala/minha")
    public ResponseEntity<?> minhaEscala(@AuthenticationPrincipal UserPrincipal principal) {
        var resultado = escalaService.minhaEscalaHoje(principal.getId());
        boolean plenarioPrincipal = operadorRepo.findById(principal.getId())
                .map(op -> Boolean.TRUE.equals(op.getPlenarioPrincipal()))
                .orElse(false);
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "data", resultado,
                "plenario_principal", plenarioPrincipal));
    }
}
