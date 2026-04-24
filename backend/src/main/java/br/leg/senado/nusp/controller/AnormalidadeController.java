package br.leg.senado.nusp.controller;

import br.leg.senado.nusp.repository.RegistroOperacaoOperadorRepository;
import br.leg.senado.nusp.security.UserPrincipal;
import br.leg.senado.nusp.service.AnormalidadeService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Endpoint de Anormalidade — equivale a api/views/anormalidade.py do Python.
 *
 * GET  /api/operacao/anormalidade/registro?entrada_id=...  → busca para edição
 * POST /api/operacao/anormalidade/registro                 → cria ou atualiza
 */
@RestController
@RequestMapping("/api/operacao/anormalidade")
@RequiredArgsConstructor
@Tag(name = "Anormalidade", description = "Registro de anormalidades na operação de áudio (RAOA)")
public class AnormalidadeController {

    private final AnormalidadeService anormalidadeService;
    private final RegistroOperacaoOperadorRepository entradaRepo;

    /**
     * GET /api/operacao/anormalidade/registro?entrada_id=...
     * Busca o RAOA vinculado a uma entrada (para edição).
     */
    @GetMapping("/registro")
    public ResponseEntity<?> buscarPorEntrada(
            @RequestParam("entrada_id") long entradaId,
            @AuthenticationPrincipal UserPrincipal principal) {

        // Verifica existência da entrada (404 vs 403 para não vazar IDs)
        String entradaOwner = entradaRepo.findOperadorIdByEntradaId(entradaId).orElse(null);
        if (entradaOwner == null)
            return ResponseEntity.status(404).body(Map.of("ok", false, "error", "not_found"));

        // Ownership: dono principal OU co-operador em Plenário Principal (OPR_ENTRADA_OPERADOR)
        if (entradaRepo.countOperadorAcessoEntrada(entradaId, principal.getId()) == 0)
            return ResponseEntity.status(403).body(Map.of("ok", false, "error", "forbidden"));

        Map<String, Object> data = anormalidadeService.buscarPorEntrada(entradaId);
        if (data == null)
            return ResponseEntity.status(404).body(Map.of("ok", false, "error", "not_found"));

        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    /**
     * POST /api/operacao/anormalidade/registro
     * Cria ou atualiza um Registro de Anormalidade na Operação de Áudio.
     */
    @PostMapping("/registro")
    public ResponseEntity<?> registrar(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal UserPrincipal principal) {

        Map<String, Object> result = anormalidadeService.registrar(body, principal.getId());
        result.put("ok", true);
        return ResponseEntity.status(201).body(result);
    }
}
