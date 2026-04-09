package br.leg.senado.nusp.controller;

import br.leg.senado.nusp.security.UserPrincipal;
import br.leg.senado.nusp.service.*;
import br.leg.senado.nusp.service.DashboardQueryHelper.PagedResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Endpoints de dashboard admin — equivale a api/views/admin.py do Python.
 * Listagens paginadas, detalhes e observações de anormalidades.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final AdminDashboardService dashboardService;
    private final ReportService reportService;
    private final ReportPdfService pdfService;
    private final ReportDocxService docxService;
    private final RdsXlsxService rdsService;
    private final ObjectMapper objectMapper;

    @Value("${app.admin.supervisor-username}")
    private String supervisorUsername;
    @Value("${app.admin.chefe-username}")
    private String chefeUsername;

    // ══ Helpers comuns ════════════════════════════════════════

    private String calcDuracao(String inicio, String termino) {
        try {
            if (inicio == null || termino == null || inicio.isEmpty() || termino.isEmpty()) return "";
            String[] ip = inicio.split(":"); String[] tp = termino.split(":");
            int iSec = Integer.parseInt(ip[0]) * 3600 + Integer.parseInt(ip[1]) * 60 + (ip.length > 2 ? Integer.parseInt(ip[2]) : 0);
            int tSec = Integer.parseInt(tp[0]) * 3600 + Integer.parseInt(tp[1]) * 60 + (tp.length > 2 ? Integer.parseInt(tp[2]) : 0);
            int diff = tSec - iSec;
            if (diff <= 0) return "";
            return String.format("%d:%02d:%02d", diff / 3600, (diff % 3600) / 60, diff % 60);
        } catch (Exception e) { return ""; }
    }

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
        body.put("meta", Map.of(
                "distinct", result.distinct(),
                "page", page, "limit", limit,
                "total", result.total(),
                "pages", (result.total() + limit - 1) / limit
        ));
        return ResponseEntity.ok(body);
    }

    // ══ Operadores ════════════════════════════════════════════

    @GetMapping("/dashboard/operadores")
    public ResponseEntity<?> operadores(
            @RequestParam(defaultValue = "1") String page,
            @RequestParam(defaultValue = "25") String limit,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "nome") String sort,
            @RequestParam(defaultValue = "asc") String direction,
            @RequestParam(required = false) String filters) {
        int p = getInt(page, 1), l = getInt(limit, 25);
        return pagedResponse(dashboardService.listOperadores(p, l, search, sort, direction, parseJson(filters)), p, l);
    }

    // ══ Checklists ════════════════════════════════════════════

    @GetMapping("/dashboard/checklists")
    public ResponseEntity<?> checklists(
            @RequestParam(defaultValue = "1") String page,
            @RequestParam(defaultValue = "25") String limit,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "data") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) String periodo,
            @RequestParam(required = false) String filters) {
        int p = getInt(page, 1), l = getInt(limit, 25);
        return pagedResponse(dashboardService.listChecklists(p, l, search, sort, direction, parseJson(periodo), parseJson(filters)), p, l);
    }

    @GetMapping("/checklist/detalhe")
    public ResponseEntity<?> checklistDetalhe(@RequestParam("checklist_id") long checklistId) {
        Map<String, Object> data = dashboardService.getChecklistDetalhe(checklistId);
        if (data == null) return ResponseEntity.status(404).body(Map.of("ok", false, "error", "not_found"));
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    // ══ Operações (sessões) ═══════════════════════════════════

    @GetMapping("/dashboard/operacoes")
    public ResponseEntity<?> operacoes(
            @RequestParam(defaultValue = "1") String page,
            @RequestParam(defaultValue = "25") String limit,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "data") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) String periodo,
            @RequestParam(required = false) String filters) {
        int p = getInt(page, 1), l = getInt(limit, 25);
        return pagedResponse(dashboardService.listOperacoes(p, l, search, sort, direction, parseJson(periodo), parseJson(filters)), p, l);
    }

    @GetMapping("/dashboard/operacoes/entradas")
    public ResponseEntity<?> operacoesEntradas(
            @RequestParam(defaultValue = "1") String page,
            @RequestParam(defaultValue = "25") String limit,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "data") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) String periodo,
            @RequestParam(required = false) String filters) {
        int p = getInt(page, 1), l = getInt(limit, 25);
        return pagedResponse(dashboardService.listOperacoesEntradas(p, l, search, sort, direction, parseJson(periodo), parseJson(filters)), p, l);
    }

    @GetMapping("/dashboard/operacoes/entradas-sessao")
    public ResponseEntity<?> entradasDeSessao(@RequestParam("registro_id") long registroId) {
        Map<String, Object> result = dashboardService.listEntradasDeSessao(registroId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("data", result.get("entradas"));
        response.put("is_plenario_principal", result.get("is_plenario_principal"));
        return ResponseEntity.ok(response);
    }

    @GetMapping("/operacao/detalhe")
    public ResponseEntity<?> operacaoDetalhe(@RequestParam("entrada_id") long entradaId) {
        Map<String, Object> data = dashboardService.getEntradaDetalhe(entradaId);
        if (data == null) return ResponseEntity.status(404).body(Map.of("ok", false, "error", "not_found"));
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    // ══ Anormalidades ═════════════════════════════════════════

    @GetMapping("/dashboard/anormalidades/salas")
    public ResponseEntity<?> anormalidadesSalas(@RequestParam(defaultValue = "") String search) {
        return ResponseEntity.ok(Map.of("ok", true, "data", dashboardService.listSalasComAnormalidades(search)));
    }

    @GetMapping("/dashboard/anormalidades/lista")
    public ResponseEntity<?> anormalidadesLista(
            @RequestParam(defaultValue = "1") String page,
            @RequestParam(defaultValue = "25") String limit,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "data") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) String periodo,
            @RequestParam(required = false) String filters,
            @RequestParam(required = false) Integer sala_id) {
        int p = getInt(page, 1), l = getInt(limit, 25);
        return pagedResponse(dashboardService.listAnormalidades(p, l, search, sort, direction, parseJson(periodo), parseJson(filters), sala_id), p, l);
    }

    @GetMapping("/anormalidade/detalhe")
    public ResponseEntity<?> anormalidadeDetalhe(@RequestParam("id") long anomId) {
        Map<String, Object> data = dashboardService.getAnormalidadeDetalhe(anomId);
        if (data == null) return ResponseEntity.status(404).body(Map.of("ok", false, "error", "not_found"));
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    // ══ Relatórios (PDF/DOCX) ════════════════════════════════

    private static final int REPORT_LIMIT = 100000;

    @GetMapping("/dashboard/operadores/relatorio")
    public ResponseEntity<?> operadoresRelatorio(
            @RequestParam(defaultValue = "pdf") String format,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "nome") String sort,
            @RequestParam(defaultValue = "asc") String direction,
            @RequestParam(required = false) String filters) {
        List<Map<String, Object>> rows = dashboardService.listOperadores(1, REPORT_LIMIT, search, sort, direction, parseJson(filters)).data();
        return reportService.respond(format, "relatorio_operadores_audio",
                () -> pdfService.gerarRelatorioOperadores(rows),
                () -> docxService.gerarRelatorioOperadores(rows));
    }

    @GetMapping("/dashboard/checklists/relatorio")
    public ResponseEntity<?> checklistsRelatorio(
            @RequestParam(defaultValue = "pdf") String format,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "data") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) String periodo,
            @RequestParam(required = false) String filters) {
        List<Map<String, Object>> rawRows = dashboardService.listChecklists(1, REPORT_LIMIT, search, sort, direction, parseJson(periodo), parseJson(filters)).data();
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (Map<String, Object> r : rawRows) {
            Map<String, Object> m = new java.util.LinkedHashMap<>(r);
            m.put("operador", r.getOrDefault("operador_nome", "--"));
            m.put("inicio", r.getOrDefault("hora_inicio_testes", ""));
            m.put("termino", r.getOrDefault("hora_termino_testes", ""));
            // Calcular duração
            String ini = String.valueOf(r.getOrDefault("hora_inicio_testes", ""));
            String ter = String.valueOf(r.getOrDefault("hora_termino_testes", ""));
            m.put("duracao", calcDuracao(ini, ter));
            // Carregar itens
            Object id = r.get("id");
            if (id != null) {
                Map<String, Object> det = dashboardService.getChecklistDetalhe(((Number) id).longValue());
                if (det != null) m.put("itens", det.get("itens"));
            }
            rows.add(m);
        }
        return reportService.respond(format, "relatorio_checklists",
                () -> pdfService.gerarRelatorioChecklists(rows),
                () -> docxService.gerarRelatorioChecklists(rows));
    }

    @GetMapping("/dashboard/operacoes/relatorio")
    public ResponseEntity<?> operacoesRelatorio(
            @RequestParam(defaultValue = "pdf") String format,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "data") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) String periodo,
            @RequestParam(required = false) String filters) {
        List<Map<String, Object>> rawRows = dashboardService.listOperacoes(1, REPORT_LIMIT, search, sort, direction, parseJson(periodo), parseJson(filters)).data();
        // Enrich rows for PDF/DOCX: map field names and load entries
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (Map<String, Object> r : rawRows) {
            Map<String, Object> m = new java.util.LinkedHashMap<>(r);
            m.put("sala", r.getOrDefault("sala_nome", "--"));
            Object chk = r.get("checklist_do_dia_ok");
            m.put("verificacao", (chk != null && ((Number) chk).intValue() == 1) ? "Realizado" : "Não Realizado");
            // Evento formatado (sigla comissão + nome_evento)
            String comNome = r.get("comissao_nome") != null ? r.get("comissao_nome").toString() : "";
            String ultEvento = r.get("ultimo_evento") != null ? r.get("ultimo_evento").toString() : "";
            if (!comNome.isEmpty() && !ultEvento.isEmpty()) {
                int idx2 = comNome.indexOf(" - ");
                String sigla = idx2 >= 0 ? comNome.substring(0, idx2).trim() : comNome.trim();
                m.put("evento_display", sigla + " - " + ultEvento);
            } else {
                m.put("evento_display", ultEvento);
            }
            Object id = r.get("id");
            if (id != null) {
                Map<String, Object> sessaoData = dashboardService.listEntradasDeSessao(((Number) id).longValue());
                m.put("entradas", sessaoData.get("entradas"));
                m.put("is_plenario_principal", sessaoData.get("is_plenario_principal"));
            }
            rows.add(m);
        }
        return reportService.respond(format, "relatorio_operacoes_sessoes",
                () -> pdfService.gerarRelatorioOperacoesSessoes(rows),
                () -> docxService.gerarRelatorioOperacoesSessoes(rows));
    }

    @GetMapping("/dashboard/operacoes/entradas/relatorio")
    public ResponseEntity<?> operacoesEntradasRelatorio(
            @RequestParam(defaultValue = "pdf") String format,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "data") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) String periodo,
            @RequestParam(required = false) String filters) {
        List<Map<String, Object>> rawRows = dashboardService.listOperacoesEntradas(1, REPORT_LIMIT, search, sort, direction, parseJson(periodo), parseJson(filters)).data();
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (Map<String, Object> r : rawRows) {
            Map<String, Object> m = new java.util.LinkedHashMap<>(r);
            m.put("sala", r.getOrDefault("sala_nome", "--"));
            m.put("operador", r.getOrDefault("operador_nome", "--"));
            m.put("tipo", r.getOrDefault("tipo_evento", "--"));
            m.put("evento", r.getOrDefault("nome_evento", "--"));
            m.put("pauta", r.getOrDefault("horario_pauta", ""));
            m.put("inicio", r.getOrDefault("horario_inicio", ""));
            m.put("fim", r.getOrDefault("horario_termino", ""));
            m.put("anormalidade", r.getOrDefault("houve_anormalidade", false));
            rows.add(m);
        }
        return reportService.respond(format, "relatorio_operacoes_entradas",
                () -> pdfService.gerarRelatorioOperacoesEntradas(rows),
                () -> docxService.gerarRelatorioOperacoesEntradas(rows));
    }

    @GetMapping("/dashboard/anormalidades/lista/relatorio")
    public ResponseEntity<?> anormalidadesRelatorio(
            @RequestParam(defaultValue = "pdf") String format,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "data") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) String periodo,
            @RequestParam(required = false) String filters,
            @RequestParam(required = false) Integer sala_id) {
        List<Map<String, Object>> rawRows = dashboardService.listAnormalidades(1, REPORT_LIMIT, search, sort, direction, parseJson(periodo), parseJson(filters), sala_id).data();
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (Map<String, Object> r : rawRows) {
            Map<String, Object> m = new java.util.LinkedHashMap<>(r);
            m.put("sala", r.getOrDefault("sala_nome", "--"));
            m.put("descricao", r.getOrDefault("descricao_anormalidade", ""));
            Object resolvida = r.get("resolvida_pelo_operador");
            m.put("solucionada", resolvida != null && ((Number) resolvida).intValue() == 1);
            rows.add(m);
        }
        return reportService.respond(format, "relatorio_anormalidades",
                () -> pdfService.gerarRelatorioAnormalidades(rows),
                () -> docxService.gerarRelatorioAnormalidades(rows));
    }

    // ══ RDS (XLSX) ════════════════════════════════════════════

    @GetMapping("/operacoes/rds/anos")
    public ResponseEntity<?> rdsAnos() {
        return ResponseEntity.ok(Map.of("ok", true, "data", dashboardService.listRdsAnos()));
    }

    @GetMapping("/operacoes/rds/meses")
    public ResponseEntity<?> rdsMeses(@RequestParam("ano") int ano) {
        return ResponseEntity.ok(Map.of("ok", true, "data", dashboardService.listRdsMeses(ano)));
    }

    @GetMapping("/operacoes/rds/gerar")
    public ResponseEntity<?> rdsGerar(@RequestParam("ano") int ano, @RequestParam("mes") int mes) {
        if (ano < 1900 || mes < 1 || mes > 12) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Parâmetros 'ano'/'mes' inválidos"));
        }
        List<Map<String, Object>> rows = dashboardService.fetchRdsRows(ano, mes);
        byte[] xlsx = rdsService.gerarRdsXlsx(ano, mes, rows);
        String filename = String.format("RDS %d-%02d", ano, mes);
        return reportService.respondXlsx(xlsx, filename);
    }

    // ══ Observações de anormalidade ═══════════════════════════

    @PostMapping("/anormalidade/observacao-supervisor")
    public ResponseEntity<?> observacaoSupervisor(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (!supervisorUsername.equalsIgnoreCase(principal.getUsername()))
            return ResponseEntity.status(403).body(Map.of("ok", false, "error", "forbidden"));

        return salvarObservacao(body, principal, true);
    }

    @PostMapping("/anormalidade/observacao-chefe")
    public ResponseEntity<?> observacaoChefe(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (!chefeUsername.equalsIgnoreCase(principal.getUsername()))
            return ResponseEntity.status(403).body(Map.of("ok", false, "error", "forbidden"));

        return salvarObservacao(body, principal, false);
    }

    private ResponseEntity<?> salvarObservacao(Map<String, Object> body, UserPrincipal principal, boolean isSupervisor) {
        Object anomIdRaw = body.get("id");
        String observacao = body.get("observacao") != null ? body.get("observacao").toString().strip() : "";
        if (anomIdRaw == null) return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "id_obrigatorio"));
        if (observacao.isEmpty()) return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "observacao_obrigatoria"));

        long anomId;
        try { anomId = Long.parseLong(anomIdRaw.toString()); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "id_invalido")); }

        if (isSupervisor) dashboardService.salvarObservacaoSupervisor(anomId, observacao, principal.getId());
        else dashboardService.salvarObservacaoChefe(anomId, observacao, principal.getId());

        return ResponseEntity.ok(Map.of("ok", true));
    }
}
