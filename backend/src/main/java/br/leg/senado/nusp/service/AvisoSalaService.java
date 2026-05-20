package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.AvisoSala;
import br.leg.senado.nusp.entity.AvisoSalaCiencia;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.AdministradorRepository;
import br.leg.senado.nusp.repository.AvisoSalaCienciaRepository;
import br.leg.senado.nusp.repository.AvisoSalaRepository;
import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.repository.SalaRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AvisoSalaService {

    private final AvisoSalaRepository avisoRepo;
    private final AvisoSalaCienciaRepository cienciaRepo;
    private final SalaRepository salaRepo;
    private final AdministradorRepository adminRepo;
    private final OperadorRepository operadorRepo;
    private final EntityManager entityManager;

    // ═══ Cadastro (admin) ═══════════════════════════════════════

    @Transactional
    public Map<String, Object> criar(Integer salaId, String mensagem, Integer duracaoDias, String criadoPor) {
        if (salaId == null) throw new ServiceValidationException("Local é obrigatório.");
        if (mensagem == null || mensagem.isBlank()) throw new ServiceValidationException("Mensagem é obrigatória.");
        if (duracaoDias == null || duracaoDias < 1 || duracaoDias > 30) {
            throw new ServiceValidationException("Duração deve estar entre 1 e 30 dias.");
        }
        salaRepo.findById(salaId).orElseThrow(() ->
                new ServiceValidationException("Local inválido.", HttpStatus.NOT_FOUND));

        LocalDateTime agora = LocalDateTime.now();
        if (avisoRepo.findAtivoBySala(salaId, agora).isPresent()) {
            throw new ServiceValidationException(
                    "Já existe um aviso ativo para este local. Desative o aviso atual antes de criar outro.");
        }

        Number numero = (Number) entityManager
                .createNativeQuery("SELECT SEQ_FRM_AVISO_SALA.NEXTVAL FROM DUAL")
                .getSingleResult();

        var aviso = new AvisoSala();
        aviso.setNumero(numero.longValue());
        aviso.setSalaId(salaId);
        aviso.setMensagem(mensagem.trim());
        aviso.setDuracaoDias(duracaoDias);
        aviso.setExpiraEm(agora.plusDays(duracaoDias));
        aviso.setCriadoPor(criadoPor);
        aviso.setAtivo(true);
        aviso = avisoRepo.save(aviso);

        log.info("Aviso #{} criado para sala {} (duração {}d) por {}",
                aviso.getNumero(), salaId, duracaoDias, criadoPor);
        return toMap(aviso, agora);
    }

    @Transactional
    public void desativar(String id) {
        var aviso = avisoRepo.findById(id)
                .orElseThrow(() -> new ServiceValidationException("Aviso não encontrado.", HttpStatus.NOT_FOUND));
        if (!Boolean.TRUE.equals(aviso.getAtivo())) {
            throw new ServiceValidationException("Aviso já está desativado.");
        }
        aviso.setAtivo(false);
        avisoRepo.save(aviso);
        log.info("Aviso #{} desativado", aviso.getNumero());
    }

    // ═══ Listagem (admin) ═══════════════════════════════════════

    private static final String STATUS_EXPR =
            "CASE WHEN a.ATIVO = 0 THEN 'Desativado' " +
            "     WHEN a.EXPIRA_EM < SYSTIMESTAMP THEN 'Expirado' " +
            "     ELSE 'Ativo' END";

    private static final Map<String, String> AVISO_SORT;
    static {
        AVISO_SORT = new LinkedHashMap<>();
        AVISO_SORT.put("data", "a.CRIADO_EM");
        AVISO_SORT.put("numero", "a.NUMERO");
        AVISO_SORT.put("sala", "s.NOME");
        AVISO_SORT.put("expira", "a.EXPIRA_EM");
        AVISO_SORT.put("status", STATUS_EXPR);
    }

    private static final Map<String, String> AVISO_COL_MAP;
    static {
        AVISO_COL_MAP = new LinkedHashMap<>();
        AVISO_COL_MAP.put("sala", "s.NOME");
        AVISO_COL_MAP.put("data", "a.CRIADO_EM");
        AVISO_COL_MAP.put("expira", "a.EXPIRA_EM");
        AVISO_COL_MAP.put("status", STATUS_EXPR);
    }

    private static final Map<String, String> AVISO_COL_TYPES = Map.of(
            "sala", "text", "data", "date", "expira", "date", "status", "text");

    public DashboardQueryHelper.PagedResult listarTodosPaginado(int page, int limit,
            String search, String sort, String direction, Map<String, Object> filters) {
        if (page < 1) page = 1;
        if (limit < 1) limit = 10;
        String selectCols =
                "a.ID, a.NUMERO, a.SALA_ID, s.NOME AS sala_nome, " +
                "a.MENSAGEM, a.DURACAO_DIAS, " +
                "a.CRIADO_EM AS criado_em, a.EXPIRA_EM AS expira_em, " +
                STATUS_EXPR + " AS status, " +
                "ad.NOME_COMPLETO AS criado_por";
        String fromJoins =
                "FROM FRM_AVISO_SALA a " +
                "JOIN CAD_SALA s ON s.ID = a.SALA_ID " +
                "LEFT JOIN PES_ADMINISTRADOR ad ON ad.ID = a.CRIADO_POR";
        return DashboardQueryHelper.executePagedQuery(entityManager,
                selectCols, fromJoins,
                null, AVISO_SORT,
                List.of("a.MENSAGEM"),
                AVISO_COL_MAP, AVISO_COL_TYPES,
                page, limit, search, sort, direction, null, filters,
                "a.ID DESC");
    }

    // ═══ Consulta pelo operador (wizard) ════════════════════════

    /**
     * Retorna o aviso ativo da sala que o operador ainda não marcou como ciente.
     * Registra a visualização (insert ou upsert simples). Se já houver ciência, retorna vazio.
     */
    @Transactional
    public Optional<Map<String, Object>> buscarPendenteParaOperador(Integer salaId, String operadorId) {
        LocalDateTime agora = LocalDateTime.now();
        var opt = avisoRepo.findAtivoBySala(salaId, agora);
        if (opt.isEmpty()) return Optional.empty();
        var aviso = opt.get();

        var existente = cienciaRepo.findByAvisoIdAndOperadorId(aviso.getId(), operadorId);
        if (existente.isPresent() && existente.get().getCienteEm() != null) {
            return Optional.empty();
        }

        // Registra/atualiza visualização (mantém a primeira data se já existir)
        if (existente.isEmpty()) {
            var c = new AvisoSalaCiencia();
            c.setAvisoId(aviso.getId());
            c.setOperadorId(operadorId);
            c.setVisualizadoEm(agora);
            cienciaRepo.save(c);
        }
        return Optional.of(toMapSimples(aviso));
    }

    /** Lista os operadores que marcaram ciência num aviso, em ordem de ciência. */
    public List<Map<String, Object>> listarCientes(String avisoId) {
        avisoRepo.findById(avisoId)
                .orElseThrow(() -> new ServiceValidationException("Aviso não encontrado.", HttpStatus.NOT_FOUND));
        return cienciaRepo.findCientesByAvisoId(avisoId).stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("operador_id", c.getOperadorId());
            m.put("operador_nome", operadorRepo.findById(c.getOperadorId())
                    .map(o -> o.getNomeCompleto())
                    .orElse(c.getOperadorId()));
            m.put("ciente_em", c.getCienteEm() != null ? c.getCienteEm().toString() : null);
            return m;
        }).collect(Collectors.toList());
    }

    @Transactional
    public void registrarCiencia(String avisoId, String operadorId) {
        var aviso = avisoRepo.findById(avisoId)
                .orElseThrow(() -> new ServiceValidationException("Aviso não encontrado.", HttpStatus.NOT_FOUND));
        LocalDateTime agora = LocalDateTime.now();
        var ciencia = cienciaRepo.findByAvisoIdAndOperadorId(aviso.getId(), operadorId)
                .orElseGet(() -> {
                    var nova = new AvisoSalaCiencia();
                    nova.setAvisoId(aviso.getId());
                    nova.setOperadorId(operadorId);
                    nova.setVisualizadoEm(agora);
                    return nova;
                });
        if (ciencia.getCienteEm() == null) {
            ciencia.setCienteEm(agora);
            cienciaRepo.save(ciencia);
            log.info("Operador {} marcou ciência no aviso #{}", operadorId, aviso.getNumero());
        }
    }

    // ═══ Helpers ════════════════════════════════════════════════

    private Map<String, Object> toMap(AvisoSala a, LocalDateTime agora) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", a.getId());
        map.put("numero", a.getNumero());
        map.put("sala_id", a.getSalaId());
        map.put("sala_nome", salaRepo.findById(a.getSalaId()).map(s -> s.getNome()).orElse(null));
        map.put("mensagem", a.getMensagem());
        map.put("duracao_dias", a.getDuracaoDias());
        map.put("criado_em", a.getCriadoEm() != null ? a.getCriadoEm().toString() : null);
        map.put("expira_em", a.getExpiraEm() != null ? a.getExpiraEm().toString() : null);

        String status;
        if (Boolean.FALSE.equals(a.getAtivo())) status = "Desativado";
        else if (a.getExpiraEm() != null && a.getExpiraEm().isBefore(agora)) status = "Expirado";
        else status = "Ativo";
        map.put("status", status);

        String nomeCriador = a.getCriadoPor();
        if (nomeCriador != null) {
            nomeCriador = adminRepo.findById(nomeCriador)
                    .map(ad -> ad.getNomeCompleto())
                    .orElse(a.getCriadoPor());
        }
        map.put("criado_por", nomeCriador);
        return map;
    }

    /** Payload enxuto para o wizard do operador. */
    private Map<String, Object> toMapSimples(AvisoSala a) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", a.getId());
        map.put("numero", a.getNumero());
        map.put("mensagem", a.getMensagem());
        return map;
    }
}
