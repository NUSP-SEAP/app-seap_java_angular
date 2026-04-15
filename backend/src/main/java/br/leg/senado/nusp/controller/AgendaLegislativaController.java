package br.leg.senado.nusp.controller;

import br.leg.senado.nusp.service.AgendaLegislativaService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * Endpoints da Agenda Legislativa — dados em tempo real do Senado Federal.
 */
@RestController
@RequestMapping("/api/agenda")
@RequiredArgsConstructor
@Tag(name = "Agenda Legislativa", description = "Agenda de reuniões do Senado Federal em tempo real")
public class AgendaLegislativaController {

    private final AgendaLegislativaService agendaService;

    /** Reuniões de comissões de hoje filtradas por sala */
    @GetMapping("/hoje")
    public ResponseEntity<?> agendaHoje(@RequestParam(required = false) Integer sala_id) {
        var data = sala_id != null
                ? agendaService.getAgendaPorSala(sala_id)
                : agendaService.getAgendaComissoes();
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    /** Sessões plenárias de hoje (Plenário Principal) */
    @GetMapping("/plenario")
    public ResponseEntity<?> agendaPlenario() {
        return ResponseEntity.ok(Map.of("ok", true, "data", agendaService.getAgendaPlenario()));
    }

    /** SSE stream — recebe atualizações em tempo real */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam(required = false) Integer sala_id,
            @RequestParam(required = false, defaultValue = "false") boolean plenario_principal) {
        return agendaService.subscribe(sala_id, plenario_principal);
    }
}
