package br.leg.senado.nusp.controller;

import br.leg.senado.nusp.security.UserPrincipal;
import br.leg.senado.nusp.service.*;
import br.leg.senado.nusp.service.DashboardQueryHelper.PagedResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Endpoints do dashboard do operador — equivale a operador_dashboard.py do Python.
 * 7 endpoints: meus-checklists, minhas-operacoes, detalhes, relatórios.
 */
@RestController
@RequestMapping("/api/operador")
@RequiredArgsConstructor
public class OperadorDashboardController {

    private final OperadorDashboardService dashboardService;
    private final ReportService reportService;
    private final ReportPdfService pdfService;
    private final ObjectMapper objectMapper;

    private static final int REPORT_LIMIT = 100000;

    private int getInt(String val, int def) {
        try { return val != null ? Integer.parseInt(val) : def; } catch (Exception e) { return def; }
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) return null;
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception e) { return null; }
    }

    private ResponseEntity<?> pagedResponse(PagedResult result, int page, int limit) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("data", result.data());
        body.put("meta", Map.of("page", page, "limit", limit,
                "total", result.total(), "pages", (result.total() + limit - 1) / limit,
                "distinct", result.distinct()));
        return ResponseEntity.ok(body);
    }

    // ══ Meus Checklists ═══════════════════════════════════════

    @GetMapping("/meus-checklists")
    public ResponseEntity<?> meusChecklists(
            @RequestParam(defaultValue = "1") String page,
            @RequestParam(defaultValue = "25") String limit,
            @RequestParam(defaultValue = "data") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) String filters,
            @AuthenticationPrincipal UserPrincipal principal) {
        int p = getInt(page, 1), l = getInt(limit, 25);
        return pagedResponse(dashboardService.listMeusChecklists(principal.getId(), p, l, sort, direction, parseJson(filters)), p, l);
    }

    @GetMapping("/meus-checklists/relatorio")
    public ResponseEntity<?> meusChecklistsRelatorio(
            @RequestParam(defaultValue = "pdf") String format,
            @RequestParam(defaultValue = "data") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) String filters,
            @AuthenticationPrincipal UserPrincipal principal) {
        List<Map<String, Object>> rows = dashboardService.listMeusChecklists(
                principal.getId(), 1, REPORT_LIMIT, sort, direction, parseJson(filters)).data();
        return reportService.respondPdf(
                pdfService.gerarRelatorioMeusChecklists(rows), "relatorio_verificacao_salas");
    }

    @GetMapping("/checklist/detalhe")
    public ResponseEntity<?> meuChecklistDetalhe(
            @RequestParam("checklist_id") long checklistId,
            @AuthenticationPrincipal UserPrincipal principal) {
        Map<String, Object> data = dashboardService.getMeuChecklistDetalhe(checklistId, principal.getId());
        if (data == null) return ResponseEntity.status(404).body(Map.of("ok", false, "error", "not_found"));
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    // ══ Minhas Operações ══════════════════════════════════════

    @GetMapping("/minhas-operacoes")
    public ResponseEntity<?> minhasOperacoes(
            @RequestParam(defaultValue = "1") String page,
            @RequestParam(defaultValue = "25") String limit,
            @RequestParam(defaultValue = "data") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) String filters,
            @AuthenticationPrincipal UserPrincipal principal) {
        int p = getInt(page, 1), l = getInt(limit, 25);
        return pagedResponse(dashboardService.listMinhasOperacoes(principal.getId(), p, l, sort, direction, parseJson(filters)), p, l);
    }

    @GetMapping("/minhas-operacoes/relatorio")
    public ResponseEntity<?> minhasOperacoesRelatorio(
            @RequestParam(defaultValue = "pdf") String format,
            @RequestParam(defaultValue = "data") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) String filters,
            @AuthenticationPrincipal UserPrincipal principal) {
        List<Map<String, Object>> rows = dashboardService.listMinhasOperacoes(
                principal.getId(), 1, REPORT_LIMIT, sort, direction, parseJson(filters)).data();
        return reportService.respondPdf(
                pdfService.gerarRelatorioMinhasOperacoes(rows), "relatorio_operacoes_audio");
    }

    @GetMapping("/operacao/detalhe")
    public ResponseEntity<?> minhaOperacaoDetalhe(
            @RequestParam("entrada_id") long entradaId,
            @AuthenticationPrincipal UserPrincipal principal) {
        Map<String, Object> data = dashboardService.getMinhaOperacaoDetalhe(entradaId, principal.getId());
        if (data == null) return ResponseEntity.status(404).body(Map.of("ok", false, "error", "not_found"));
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    // ══ Minha Anormalidade ════════════════════════════════════

    @GetMapping("/anormalidade/detalhe")
    public ResponseEntity<?> minhaAnormalidadeDetalhe(
            @RequestParam("id") long anomId,
            @AuthenticationPrincipal UserPrincipal principal) {
        Map<String, Object> data = dashboardService.getMinhaAnormalidadeDetalhe(anomId, principal.getId());
        if (data == null) return ResponseEntity.status(404).body(Map.of("ok", false, "error", "not_found"));
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }
}
