package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.EscalaOperador;
import br.leg.senado.nusp.entity.EscalaSemanal;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.AdministradorRepository;
import br.leg.senado.nusp.repository.EscalaOperadorRepository;
import br.leg.senado.nusp.repository.EscalaSemanalRepository;
import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.repository.SalaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class EscalaSemanalService {

    private final EscalaSemanalRepository escalaRepo;
    private final EscalaOperadorRepository escalaOpRepo;
    private final SalaRepository salaRepo;
    private final OperadorRepository operadorRepo;
    private final AdministradorRepository adminRepo;

    // ══ Listar escalas ══════════════════════════════════════════

    public List<Map<String, Object>> listarEscalas() {
        var escalas = escalaRepo.findAllOrderByDataInicioDesc();
        return escalas.stream().map(this::toMap).collect(Collectors.toList());
    }

    /** Paginado — retorna {data, meta} compatível com o PaginationComponent do frontend. */
    public Map<String, Object> listarEscalasPaginado(int page, int limit) {
        if (page < 1) page = 1;
        if (limit < 1) limit = 10;
        var todas = listarEscalas();
        int total = todas.size();
        int pages = total == 0 ? 1 : (int) Math.ceil((double) total / limit);
        int fromIdx = Math.min((page - 1) * limit, total);
        int toIdx = Math.min(fromIdx + limit, total);
        return Map.of(
                "data", todas.subList(fromIdx, toIdx),
                "meta", Map.of("page", page, "limit", limit, "total", total, "pages", pages)
        );
    }

    // ══ Obter escala com operadores ═════════════════════════════

    public Map<String, Object> obterEscala(Long id) {
        var escala = escalaRepo.findById(id)
                .orElseThrow(() -> new ServiceValidationException("Escala não encontrada.", HttpStatus.NOT_FOUND));
        var result = toMap(escala);

        var vinculos = escalaOpRepo.findByEscalaId(id);
        // Agrupar por sala_id (IDs dos operadores — para edição)
        Map<Integer, List<String>> porSala = new LinkedHashMap<>();
        for (var v : vinculos) {
            porSala.computeIfAbsent(v.getSalaId(), k -> new ArrayList<>()).add(v.getOperadorId());
        }
        result.put("salas", porSala);

        // Resumo com nomes — para visualização expandida
        List<Map<String, Object>> resumo = new ArrayList<>();
        var salasOrdenadas = salaRepo.findAtivasOrdenadas();
        for (var sala : salasOrdenadas) {
            var ops = porSala.get(sala.getId());
            if (ops == null || ops.isEmpty()) continue;
            List<String> nomes = new ArrayList<>();
            for (String opId : ops) {
                operadorRepo.findById(opId).ifPresent(op -> nomes.add(op.getNomeExibicao()));
            }
            resumo.add(Map.of(
                    "sala_nome", sala.getNome(),
                    "operadores", String.join(", ", nomes)
            ));
        }
        result.put("resumo", resumo);
        return result;
    }

    // ══ Criar/Atualizar escala ══════════════════════════════════

    @Transactional
    public Map<String, Object> salvarEscala(Long id, LocalDate dataInicio, LocalDate dataFim,
                                            Map<Integer, List<String>> salasOperadores, String criadoPor) {
        if (dataInicio == null || dataFim == null) {
            throw new ServiceValidationException("Data início e data fim são obrigatórias.");
        }
        if (dataFim.isBefore(dataInicio)) {
            throw new ServiceValidationException("Data fim não pode ser anterior à data início.");
        }

        EscalaSemanal escala;
        if (id != null) {
            escala = escalaRepo.findById(id)
                    .orElseThrow(() -> new ServiceValidationException("Escala não encontrada.", HttpStatus.NOT_FOUND));
        } else {
            escala = new EscalaSemanal();
            escala.setCriadoPor(criadoPor);
        }

        escala.setDataInicio(dataInicio);
        escala.setDataFim(dataFim);
        escala = escalaRepo.save(escala);

        // Recriar vínculos
        escalaOpRepo.deleteByEscalaId(escala.getId());
        if (salasOperadores != null) {
            for (var entry : salasOperadores.entrySet()) {
                int salaId = entry.getKey();
                for (String operadorId : entry.getValue()) {
                    var eo = new EscalaOperador();
                    eo.setEscalaId(escala.getId());
                    eo.setSalaId(salaId);
                    eo.setOperadorId(operadorId);
                    escalaOpRepo.save(eo);
                }
            }
        }

        log.info("Escala #{} salva: {} a {} por {}", escala.getId(), dataInicio, dataFim, criadoPor);
        return obterEscala(escala.getId());
    }

    // ══ Excluir escala ══════════════════════════════════════════

    @Transactional
    public void excluirEscala(Long id) {
        var escala = escalaRepo.findById(id)
                .orElseThrow(() -> new ServiceValidationException("Escala não encontrada.", HttpStatus.NOT_FOUND));
        escalaRepo.delete(escala); // CASCADE deleta os vínculos
        log.info("Escala #{} excluída", id);
    }

    // ══ Minha escala (operador) ═════════════════════════════════

    /**
     * Retorna as salas em que o operador está escalado hoje.
     * Retorna lista de nomes de salas.
     */
    public List<Map<String, Object>> minhaEscalaHoje(String operadorId) {
        var hoje = LocalDate.now();
        var escalas = escalaRepo.findVigentesPorData(hoje);
        if (escalas.isEmpty()) return Collections.emptyList();

        List<Map<String, Object>> resultado = new ArrayList<>();
        for (var escala : escalas) {
            var vinculos = escalaOpRepo.findByEscalaIdAndOperadorId(escala.getId(), operadorId);
            for (var v : vinculos) {
                var sala = salaRepo.findById(v.getSalaId());
                if (sala.isPresent()) {
                    resultado.add(Map.of(
                            "sala_id", v.getSalaId(),
                            "sala_nome", sala.get().getNome(),
                            "escala_id", escala.getId(),
                            "data_inicio", escala.getDataInicio().toString(),
                            "data_fim", escala.getDataFim().toString()
                    ));
                }
            }
        }
        return resultado;
    }

    // ══ Gerar escala automática (rodízio) ═══════════════════════

    /**
     * Gera uma escala por rodízio e persiste.
     * Para cada plenário numerado (em ordem), escolhe 2 operadores participantes da escala
     * com menor contagem de aparições nesse plenário no histórico. Empates são desempatados
     * por sorteio aleatório.
     */
    @Transactional
    public Map<String, Object> gerarEscalaRodizio(LocalDate dataInicio, LocalDate dataFim, String criadoPor) {
        if (dataInicio == null || dataFim == null) {
            throw new ServiceValidationException("Data início e data fim são obrigatórias.");
        }
        if (dataFim.isBefore(dataInicio)) {
            throw new ServiceValidationException("Data fim não pode ser anterior à data início.");
        }

        // 1. Buscar operadores participantes
        List<String> operadoresDisponiveis = new ArrayList<>(
                operadorRepo.findParticipantesEscala().stream().map(o -> o.getId()).toList());
        if (operadoresDisponiveis.isEmpty()) {
            throw new ServiceValidationException("Nenhum operador marcado para participar da escala.");
        }

        // 2. Plenários numerados em ordem (P02, P03, P06, P07, P09, P13, P15, P19)
        List<Integer> plenariosIds = salaRepo.findAtivasOrdenadas().stream()
                .filter(s -> s.getNome() != null && s.getNome().matches("Plenário \\d+"))
                .sorted(Comparator.comparingInt(s -> extrairNumero(s.getNome())))
                .map(s -> s.getId())
                .toList();

        int slotsNecessarios = plenariosIds.size() * 2;
        if (operadoresDisponiveis.size() < slotsNecessarios) {
            throw new ServiceValidationException(
                    "Operadores insuficientes: são necessários " + slotsNecessarios +
                    " e há apenas " + operadoresDisponiveis.size() + " marcados.");
        }

        // 3. Carregar histórico agregado em memória: Map<operadorId, Map<salaId, count>>
        Map<String, Map<Integer, Integer>> historico = new HashMap<>();
        for (Object[] row : escalaOpRepo.countAparicoesPorOperadorSala()) {
            String opId = (String) row[0];
            int salaId = ((Number) row[1]).intValue();
            int count = ((Number) row[2]).intValue();
            historico.computeIfAbsent(opId, k -> new HashMap<>()).put(salaId, count);
        }

        // 4. Para cada plenário, escolher 2 operadores com menor count (empate → random)
        Random random = new Random();
        Map<Integer, List<String>> alocacao = new LinkedHashMap<>();

        for (int salaId : plenariosIds) {
            final int sId = salaId;
            // Ordena operadores disponíveis por (count ASC, random tiebreak)
            List<String> ordenados = new ArrayList<>(operadoresDisponiveis);
            Collections.shuffle(ordenados, random);
            ordenados.sort(Comparator.comparingInt(opId ->
                    historico.getOrDefault(opId, Collections.emptyMap()).getOrDefault(sId, 0)));

            List<String> escolhidos = new ArrayList<>(ordenados.subList(0, 2));
            alocacao.put(salaId, escolhidos);
            operadoresDisponiveis.removeAll(escolhidos);
        }

        // 5. Persistir a escala via o método existente (reaproveita validações + timestamps)
        return salvarEscala(null, dataInicio, dataFim, alocacao, criadoPor);
    }

    private int extrairNumero(String nomeSala) {
        try {
            return Integer.parseInt(nomeSala.replaceAll("\\D+", ""));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    // ══ Operadores escalados hoje (por sala) ══════════════════

    /**
     * Retorna mapa sala_id → lista de nome_exibicao dos operadores escalados hoje.
     */
    public Map<Integer, List<String>> operadoresEscaladosHoje() {
        var hoje = LocalDate.now();
        var escalas = escalaRepo.findVigentesPorData(hoje);
        Map<Integer, List<String>> resultado = new LinkedHashMap<>();
        for (var escala : escalas) {
            var vinculos = escalaOpRepo.findByEscalaId(escala.getId());
            for (var v : vinculos) {
                operadorRepo.findById(v.getOperadorId()).ifPresent(op ->
                    resultado.computeIfAbsent(v.getSalaId(), k -> new ArrayList<>())
                            .add(op.getNomeExibicao())
                );
            }
        }
        return resultado;
    }

    // ══ Helpers ═════════════════════════════════════════════════

    private Map<String, Object> toMap(EscalaSemanal e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", e.getId());
        map.put("data_inicio", e.getDataInicio().toString());
        map.put("data_fim", e.getDataFim().toString());
        // Buscar nome completo do admin pelo username
        String nomeCriador = e.getCriadoPor();
        if (nomeCriador != null) {
            nomeCriador = adminRepo.findByUsername(nomeCriador)
                    .map(a -> a.getNomeCompleto())
                    .orElse(e.getCriadoPor());
        }
        map.put("criado_por", nomeCriador);
        map.put("criado_em", e.getCriadoEm() != null ? e.getCriadoEm().toString() : null);
        return map;
    }
}
