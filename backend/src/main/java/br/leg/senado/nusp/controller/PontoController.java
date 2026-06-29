package br.leg.senado.nusp.controller;

import br.leg.senado.nusp.security.UserPrincipal;
import br.leg.senado.nusp.service.PontoService;
import br.leg.senado.nusp.service.PontoService.ArquivoPonto;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * Ponto e Banco de Horas.
 * Admin:            upload/separação/vínculo/publicação em /api/admin/ponto/**
 * Operador/Técnico: listagem e download da própria folha em /api/ponto/**
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Ponto e Banco",
     description = "Cartões-ponto: upload/separação/vínculo (admin) e download individual (operador/técnico)")
public class PontoController {

    private final PontoService pontoService;

    // ══ Admin ═══════════════════════════════════════════════════

    @PostMapping("/api/admin/ponto/upload")
    public ResponseEntity<?> upload(
            @RequestParam("arquivo") MultipartFile arquivo,
            @RequestParam("tipo") String tipo,
            @RequestParam("data_inicio") String dataInicio,
            @RequestParam("data_fim") String dataFim,
            @AuthenticationPrincipal UserPrincipal principal) {
        Map<String, Object> data = pontoService.upload(arquivo, tipo, dataInicio, dataFim, principal.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("ok", true, "data", data));
    }

    @GetMapping("/api/admin/ponto/lotes")
    public ResponseEntity<?> lotes() {
        return ResponseEntity.ok(Map.of("ok", true, "data", pontoService.listarLotes()));
    }

    @GetMapping("/api/admin/ponto/lote/{id}")
    public ResponseEntity<?> lote(@PathVariable String id) {
        return ResponseEntity.ok(Map.of("ok", true, "data", pontoService.obterLote(id)));
    }

    @GetMapping("/api/admin/ponto/pessoas")
    public ResponseEntity<?> pessoas() {
        return ResponseEntity.ok(Map.of("ok", true, "data", pontoService.listarPessoas()));
    }

    @PatchMapping("/api/admin/ponto/lote/{loteId}/pagina/{paginaId}")
    public ResponseEntity<?> vincular(
            @PathVariable String loteId,
            @PathVariable String paginaId,
            @RequestBody(required = false) Map<String, Object> body) {
        String pessoaId = body != null && body.get("pessoa_id") != null ? body.get("pessoa_id").toString() : null;
        String pessoaTipo = body != null && body.get("pessoa_tipo") != null ? body.get("pessoa_tipo").toString() : null;
        Map<String, Object> data = pontoService.atualizarVinculo(loteId, paginaId, pessoaId, pessoaTipo);
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    @PostMapping("/api/admin/ponto/lote/{id}/publicar")
    public ResponseEntity<?> publicar(@PathVariable String id,
                                      @RequestBody(required = false) Map<String, Object> body) {
        // Emitir aviso por padrão; só não emite se vier explicitamente emitir_aviso=false.
        boolean emitirAviso = body == null || !Boolean.FALSE.equals(body.get("emitir_aviso"));
        return ResponseEntity.ok(Map.of("ok", true, "data", pontoService.publicar(id, emitirAviso)));
    }

    @GetMapping("/api/admin/ponto/pagina/{id}/preview")
    public ResponseEntity<?> preview(@PathVariable String id) {
        return streamPdf(pontoService.previewPagina(id), false);
    }

    // ══ Operador / Técnico ══════════════════════════════════════

    @GetMapping("/api/ponto/minhas-folhas")
    public ResponseEntity<?> minhasFolhas(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(Map.of("ok", true, "data", pontoService.minhasFolhas(principal.getId())));
    }

    @GetMapping("/api/ponto/folha/{paginaId}/download")
    public ResponseEntity<?> download(@PathVariable String paginaId,
                                      @AuthenticationPrincipal UserPrincipal principal) {
        return streamPdf(pontoService.baixarFolha(paginaId, principal.getId(), principal.getRole()), true);
    }

    /** Dados parseados da folha (7 colunas por dia) para a tela de retificação. */
    @GetMapping("/api/ponto/folha/{paginaId}/dados")
    public ResponseEntity<?> dadosFolha(@PathVariable String paginaId,
                                        @AuthenticationPrincipal UserPrincipal principal) {
        Map<String, Object> data = pontoService.dadosFolha(paginaId, principal.getId(), principal.getRole());
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    // ══ Helper ══════════════════════════════════════════════════

    private ResponseEntity<ByteArrayResource> streamPdf(ArquivoPonto arq, boolean attachment) {
        ContentDisposition cd = (attachment ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(arq.nomeArquivo()).build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .body(new ByteArrayResource(arq.conteudo()));
    }
}
