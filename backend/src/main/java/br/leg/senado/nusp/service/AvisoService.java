package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.AvisoAlvo;
import br.leg.senado.nusp.entity.AvisoCadastro;
import br.leg.senado.nusp.entity.AvisoCiencia;
import br.leg.senado.nusp.entity.AvisoMensagem;
import br.leg.senado.nusp.enums.AlvoTipoAviso;
import br.leg.senado.nusp.enums.PapelPessoa;
import br.leg.senado.nusp.enums.StatusAviso;
import br.leg.senado.nusp.enums.TipoAviso;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Serviço único de avisos. Genérico para todos os tipos (VERIFICACAO, ESCALA,
 * PESSOAL, AGENDA, GERAL) e todos os públicos (sala / operador / técnico /
 * coletivos). Nesta entrega o frontend só exercita o tipo VERIFICACAO com
 * público SALA, mas a camada de serviço já aceita todas as variantes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AvisoService {

    private final AvisoCadastroRepository cadastroRepo;
    private final AvisoMensagemRepository mensagemRepo;
    private final AvisoAlvoRepository alvoRepo;
    private final AvisoCienciaRepository cienciaRepo;
    private final SalaRepository salaRepo;
    private final OperadorRepository operadorRepo;
    private final TecnicoRepository tecnicoRepo;
    private final AdministradorRepository adminRepo;
    private final AvisoCienciaWriter cienciaWriter;
    private final EntityManager entityManager;

    private static final int MAX_MENSAGENS = 10;
    private static final int MAX_DURACAO_DIAS = 30;

    /** Payload de criação (montado pelo controller a partir do JSON snake_case). */
    public record CriarAvisoRequest(
            String tipo,
            Boolean permanente,
            Integer duracaoDias,
            Boolean manterAposCiencia,
            List<String> mensagens,
            String alvoTipo,
            List<Integer> salaIds,
            List<String> operadorIds,
            List<String> tecnicoIds) {}

    // ═══ Cadastro (admin) ═══════════════════════════════════════

    @Transactional
    public Map<String, Object> criar(CriarAvisoRequest req, String criadoPorId) {
        // ── Tipo ──
        TipoAviso tipo = parseTipo(req.tipo());

        // ── Mensagens ──
        List<String> mensagens = (req.mensagens() == null ? List.<String>of() : req.mensagens())
                .stream().map(m -> m == null ? "" : m.trim()).toList();
        if (mensagens.isEmpty()) throw new ServiceValidationException("Informe ao menos uma mensagem.");
        if (mensagens.size() > MAX_MENSAGENS)
            throw new ServiceValidationException("Máximo de " + MAX_MENSAGENS + " avisos por cadastro.");
        if (mensagens.stream().anyMatch(String::isBlank))
            throw new ServiceValidationException("Todas as mensagens devem ser preenchidas.");

        // ── Permanente / duração ──
        boolean permanente = req.permanente() == null || req.permanente(); // default true
        Integer duracao = permanente ? null : req.duracaoDias();
        if (!permanente && (duracao == null || duracao < 1 || duracao > MAX_DURACAO_DIAS))
            throw new ServiceValidationException("A duração deve estar entre 1 e " + MAX_DURACAO_DIAS + " dias.");
        boolean manter = req.manterAposCiencia() != null && req.manterAposCiencia();

        // ── Público (alvo) ──
        AlvoTipoAviso alvoTipo = parseAlvoTipo(req.alvoTipo());
        // Deduplica para não gerar linhas de alvo repetidas (sem UNIQUE no schema).
        List<Integer> salaIds = (req.salaIds() == null ? List.<Integer>of() : req.salaIds()).stream().distinct().toList();
        List<String> operadorIds = (req.operadorIds() == null ? List.<String>of() : req.operadorIds()).stream().distinct().toList();
        List<String> tecnicoIds = (req.tecnicoIds() == null ? List.<String>of() : req.tecnicoIds()).stream().distinct().toList();
        validarAlvo(alvoTipo, salaIds, operadorIds, tecnicoIds);

        // Regra: no máximo 1 aviso ativo por sala (para o mesmo tipo).
        if (alvoTipo == AlvoTipoAviso.SALA) validarSalasLivres(tipo, salaIds);

        // ── Autor (FK) ──
        adminRepo.findById(criadoPorId).orElseThrow(() ->
                new ServiceValidationException("Administrador inválido.", HttpStatus.NOT_FOUND));

        LocalDateTime agora = LocalDateTime.now();
        Number numero = (Number) entityManager
                .createNativeQuery("SELECT SEQ_FRM_AVISO_CADASTRO.NEXTVAL FROM DUAL")
                .getSingleResult();

        // ── Cadastro ──
        AvisoCadastro cad = new AvisoCadastro();
        cad.setNumero(numero.longValue());
        cad.setTipo(tipo);
        cad.setPermanente(permanente);
        cad.setDuracaoDias(duracao);
        cad.setManterAposCiencia(manter);
        cad.setStatus(StatusAviso.ATIVO);
        cad.setCriadoPorId(criadoPorId);
        cad.setExpiraEm(permanente ? null : agora.plusDays(duracao));
        cad = cadastroRepo.save(cad);

        // ── Mensagens ──
        int ordem = 1;
        for (String texto : mensagens) {
            AvisoMensagem m = new AvisoMensagem();
            m.setCadastroId(cad.getId());
            m.setOrdem(ordem++);
            m.setTexto(texto);
            mensagemRepo.save(m);
        }

        // ── Alvos ──
        List<AvisoAlvo> alvos = montarAlvos(cad.getId(), alvoTipo, salaIds, operadorIds, tecnicoIds);
        alvoRepo.saveAll(alvos);

        log.info("Aviso cadastro #{} criado (tipo={}, {} mensagens, alvo={}, {} alvo(s)) por {}",
                cad.getNumero(), tipo, mensagens.size(), alvoTipo, alvos.size(), criadoPorId);
        return toResumo(cad);
    }

    @Transactional
    public void desativar(String id) {
        AvisoCadastro cad = cadastroRepo.findById(id).orElseThrow(() ->
                new ServiceValidationException("Aviso não encontrado.", HttpStatus.NOT_FOUND));
        if (cad.getStatus() == StatusAviso.DESATIVADO)
            throw new ServiceValidationException("Aviso já está desativado.");
        cad.setStatus(StatusAviso.DESATIVADO);
        cad.setDesativadoEm(LocalDateTime.now());
        cadastroRepo.save(cad);
        log.info("Aviso cadastro #{} desativado", cad.getNumero());
    }

    // ═══ Listagem (admin) ═══════════════════════════════════════

    private static final String TIPO_EXPR =
            "CASE c.TIPO " +
            "WHEN 'VERIFICACAO' THEN 'Verificação' " +
            "WHEN 'ESCALA' THEN 'Escala' " +
            "WHEN 'PESSOAL' THEN 'Pessoal' " +
            "WHEN 'AGENDA' THEN 'Agenda' " +
            "WHEN 'GERAL' THEN 'Geral' END";

    private static final Map<String, String> SORT;
    private static final Map<String, String> COL_MAP;
    private static final Map<String, String> COL_TYPES;
    static {
        SORT = new LinkedHashMap<>();
        SORT.put("data", "c.CRIADO_EM");
        SORT.put("numero", "c.NUMERO");
        SORT.put("tipo", TIPO_EXPR);
        SORT.put("expira", "c.EXPIRA_EM");
        SORT.put("status", "c.STATUS");
        SORT.put("criado_por", "ad.NOME_COMPLETO");

        COL_MAP = new LinkedHashMap<>();
        COL_MAP.put("tipo", TIPO_EXPR);
        COL_MAP.put("data", "c.CRIADO_EM");
        COL_MAP.put("expira", "c.EXPIRA_EM");
        COL_MAP.put("status", "c.STATUS");
        COL_MAP.put("criado_por", "ad.NOME_COMPLETO");

        COL_TYPES = new LinkedHashMap<>();
        COL_TYPES.put("tipo", "text");
        COL_TYPES.put("data", "date");
        COL_TYPES.put("expira", "date");
        COL_TYPES.put("status", "text");
        COL_TYPES.put("criado_por", "text");
    }

    public DashboardQueryHelper.PagedResult listarTodosPaginado(int page, int limit,
            String search, String sort, String direction, Map<String, Object> filters) {
        if (page < 1) page = 1;
        if (limit < 1) limit = 10;
        String selectCols =
                "c.ID, c.NUMERO, " + TIPO_EXPR + " AS tipo, " +
                "c.CRIADO_EM AS criado_em, ad.NOME_COMPLETO AS criado_por, " +
                "c.EXPIRA_EM AS expira_em, c.STATUS AS status, c.PERMANENTE AS permanente";
        String fromJoins =
                "FROM FRM_AVISO_CADASTRO c " +
                "LEFT JOIN PES_ADMINISTRADOR ad ON ad.ID = c.CRIADO_POR_ID";
        return DashboardQueryHelper.executePagedQuery(entityManager,
                selectCols, fromJoins,
                null, SORT,
                List.of("ad.NOME_COMPLETO", "TO_CHAR(c.NUMERO)"),
                COL_MAP, COL_TYPES,
                page, limit, search, sort, direction, null, filters,
                "c.ID DESC");
    }

    /** Detalhe completo de um cadastro (cabeçalho + mensagens + alvos + cientes). */
    public Map<String, Object> obterDetalhe(String id) {
        AvisoCadastro cad = cadastroRepo.findById(id).orElseThrow(() ->
                new ServiceValidationException("Aviso não encontrado.", HttpStatus.NOT_FOUND));
        Map<String, Object> m = toResumo(cad);
        m.put("mensagens", mensagemRepo.findByCadastroIdOrderByOrdem(id).stream()
                .map(this::mensagemToMap).toList());
        m.put("alvos", alvoRepo.findByCadastroId(id).stream()
                .map(this::alvoToMap).toList());
        m.put("cientes", cad.getTipo() != null && cad.getTipo().exigeCiencia()
                ? cienciaRepo.findByCadastroIdOrderByCienteEm(id).stream().map(this::cienciaToMap).toList()
                : List.of());
        return m;
    }

    // ═══ Consulta pelo destinatário (verificação) ═══════════════

    /**
     * Retorna o aviso ativo de tipo VERIFICACAO pendente para a pessoa na sala
     * informada, ou vazio. "Pendente" = aviso ativo cuja sala-alvo contém salaId
     * e que (a) tem manter_apos_ciencia=1 — sempre reaparece — ou (b) a pessoa
     * ainda não marcou ciência. Se houver mais de um candidato, retorna o mais
     * antigo (FIFO); os demais aparecem nas próximas entradas.
     */
    @Transactional
    public Optional<Map<String, Object>> buscarPendenteVerificacao(Integer salaId, String pessoaId, PapelPessoa papel) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT c.ID, c.MANTER_APOS_CIENCIA
                  FROM FRM_AVISO_CADASTRO c
                  JOIN FRM_AVISO_ALVO al ON al.CADASTRO_ID = c.ID
                 WHERE c.TIPO = 'VERIFICACAO'
                   AND c.STATUS = 'Ativo'
                   AND (c.PERMANENTE = 1 OR c.EXPIRA_EM IS NULL OR c.EXPIRA_EM > SYSTIMESTAMP)
                   AND al.ALVO_TIPO = 'SALA'
                   AND al.SALA_ID = ?
                 ORDER BY c.CRIADO_EM
                """).setParameter(1, salaId).getResultList();

        for (Object[] r : rows) {
            String cadastroId = (String) r[0];
            boolean manter = ((Number) r[1]).intValue() == 1;
            boolean jaCiente = temCiencia(cadastroId, salaId, pessoaId, papel);
            if (!manter && jaCiente) continue; // já marcou NESTA sala e não é pra manter → pula
            return Optional.of(montarPayloadPendente(cadastroId, manter));
        }
        return Optional.empty();
    }

    /**
     * Registra a ciência da pessoa no cadastro. Só a primeira ciência conta:
     * se já existe, não faz nada (cenário normal de manter_apos_ciencia=1).
     * O índice único parcial no banco é a rede de segurança contra corrida.
     */
    @Transactional
    public void registrarCiencia(String cadastroId, Integer salaId, String pessoaId, PapelPessoa papel) {
        AvisoCadastro cad = cadastroRepo.findById(cadastroId).orElseThrow(() ->
                new ServiceValidationException("Aviso não encontrado.", HttpStatus.NOT_FOUND));
        if (cad.getTipo() == null || !cad.getTipo().exigeCiencia())
            throw new ServiceValidationException("Este tipo de aviso não registra ciência.");
        // Tipos atuais que exigem ciência são por sala (VERIFICACAO); a sala é obrigatória.
        if (salaId == null)
            throw new ServiceValidationException("Sala é obrigatória para registrar ciência.");
        // Aviso não mais ativo (expirou/desativou entre a exibição e o clique):
        // nada a registrar, mas não bloqueia o destinatário.
        if (cad.getStatus() != StatusAviso.ATIVO) return;
        if (temCiencia(cadastroId, salaId, pessoaId, papel)) return;

        AvisoCiencia c = new AvisoCiencia();
        c.setCadastroId(cadastroId);
        c.setSalaId(salaId);
        if (papel == PapelPessoa.OPERADOR) c.setOperadorId(pessoaId);
        else c.setTecnicoId(pessoaId);
        c.setCienteEm(LocalDateTime.now());
        try {
            cienciaWriter.inserir(c); // REQUIRES_NEW: isola eventual violação de unicidade em corrida
            log.info("Ciência registrada: cadastro #{} sala {} por {} ({})", cad.getNumero(), salaId, pessoaId, papel);
        } catch (DataIntegrityViolationException e) {
            // Corrida: outra requisição já gravou a ciência. Operação idempotente — ignora.
            log.debug("Ciência concorrente já registrada: cadastro #{} sala {} por {} ({})", cad.getNumero(), salaId, pessoaId, papel);
        }
    }

    // ═══ Rotina de expiração ════════════════════════════════════

    /** A cada 15 minutos, marca como Expirado os avisos ativos não-permanentes vencidos. */
    @Scheduled(cron = "0 */15 * * * *")
    @Transactional
    public void expirarAvisos() {
        int n = entityManager.createNativeQuery("""
                UPDATE FRM_AVISO_CADASTRO
                   SET STATUS = 'Expirado'
                 WHERE STATUS = 'Ativo'
                   AND PERMANENTE = 0
                   AND EXPIRA_EM IS NOT NULL
                   AND EXPIRA_EM < SYSTIMESTAMP
                """).executeUpdate();
        if (n > 0) log.info("[avisos] {} aviso(s) marcado(s) como Expirado.", n);
    }

    // ═══ Helpers ════════════════════════════════════════════════

    private TipoAviso parseTipo(String v) {
        if (v == null || v.isBlank()) throw new ServiceValidationException("Tipo de aviso é obrigatório.");
        try { return TipoAviso.fromString(v); }
        catch (IllegalArgumentException e) { throw new ServiceValidationException("Tipo de aviso inválido."); }
    }

    private AlvoTipoAviso parseAlvoTipo(String v) {
        if (v == null || v.isBlank()) throw new ServiceValidationException("Tipo de público é obrigatório.");
        try { return AlvoTipoAviso.fromString(v); }
        catch (IllegalArgumentException e) { throw new ServiceValidationException("Tipo de público inválido."); }
    }

    private void validarAlvo(AlvoTipoAviso alvoTipo, List<Integer> salaIds,
                             List<String> operadorIds, List<String> tecnicoIds) {
        switch (alvoTipo) {
            case SALA -> {
                if (salaIds.isEmpty()) throw new ServiceValidationException("Selecione ao menos um local.");
                if (!operadorIds.isEmpty() || !tecnicoIds.isEmpty())
                    throw new ServiceValidationException("Público por sala não aceita operadores/técnicos individuais.");
                for (Integer sid : salaIds)
                    salaRepo.findById(sid).orElseThrow(() ->
                            new ServiceValidationException("Local inválido: " + sid, HttpStatus.NOT_FOUND));
            }
            case OPERADOR -> {
                if (operadorIds.isEmpty()) throw new ServiceValidationException("Selecione ao menos um operador.");
                if (!salaIds.isEmpty() || !tecnicoIds.isEmpty())
                    throw new ServiceValidationException("Público por operador não aceita salas/técnicos.");
                for (String oid : operadorIds)
                    operadorRepo.findById(oid).orElseThrow(() ->
                            new ServiceValidationException("Operador inválido: " + oid, HttpStatus.NOT_FOUND));
            }
            case TECNICO -> {
                if (tecnicoIds.isEmpty()) throw new ServiceValidationException("Selecione ao menos um técnico.");
                if (!salaIds.isEmpty() || !operadorIds.isEmpty())
                    throw new ServiceValidationException("Público por técnico não aceita salas/operadores.");
                for (String tid : tecnicoIds)
                    tecnicoRepo.findById(tid).orElseThrow(() ->
                            new ServiceValidationException("Técnico inválido: " + tid, HttpStatus.NOT_FOUND));
            }
            case TODOS_OPERADORES, TODOS_TECNICOS, TODOS -> {
                if (!salaIds.isEmpty() || !operadorIds.isEmpty() || !tecnicoIds.isEmpty())
                    throw new ServiceValidationException("Público coletivo não aceita seleção individual.");
            }
        }
    }

    /** Rejeita criação se alguma sala já tem aviso ativo do mesmo tipo (1 aviso ativo por sala). */
    private void validarSalasLivres(TipoAviso tipo, List<Integer> salaIds) {
        for (Integer sid : salaIds) {
            List<?> ocup = entityManager.createNativeQuery("""
                    SELECT c.NUMERO
                      FROM FRM_AVISO_ALVO al
                      JOIN FRM_AVISO_CADASTRO c ON c.ID = al.CADASTRO_ID
                     WHERE al.ALVO_TIPO = 'SALA'
                       AND al.SALA_ID = ?
                       AND c.TIPO = ?
                       AND c.STATUS = 'Ativo'
                     FETCH FIRST 1 ROW ONLY
                    """).setParameter(1, sid).setParameter(2, tipo.name()).getResultList();
            if (!ocup.isEmpty()) {
                long numero = ((Number) ocup.get(0)).longValue();
                String nome = salaRepo.findById(sid).map(s -> s.getNome()).orElse("Sala " + sid);
                throw new ServiceValidationException(
                        nome + " já possui um aviso ativo (cadastro nº " + numero + "). Desative-o antes de cadastrar outro.");
            }
        }
    }

    /** Salas com aviso ativo do tipo informado → [{sala_id, numero}]. Alimenta o bloqueio no form do admin. */
    public List<Map<String, Object>> salasOcupadas(String tipoStr) {
        TipoAviso tipo = parseTipo(tipoStr);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT al.SALA_ID, c.NUMERO
                  FROM FRM_AVISO_ALVO al
                  JOIN FRM_AVISO_CADASTRO c ON c.ID = al.CADASTRO_ID
                 WHERE al.ALVO_TIPO = 'SALA'
                   AND c.TIPO = ?
                   AND c.STATUS = 'Ativo'
                 ORDER BY al.SALA_ID
                """).setParameter(1, tipo.name()).getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sala_id", ((Number) r[0]).intValue());
            m.put("numero", ((Number) r[1]).longValue());
            out.add(m);
        }
        return out;
    }

    private List<AvisoAlvo> montarAlvos(String cadastroId, AlvoTipoAviso alvoTipo,
                                        List<Integer> salaIds, List<String> operadorIds, List<String> tecnicoIds) {
        List<AvisoAlvo> alvos = new ArrayList<>();
        switch (alvoTipo) {
            case SALA -> { for (Integer sid : salaIds) {
                AvisoAlvo a = new AvisoAlvo(); a.setCadastroId(cadastroId); a.setAlvoTipo(AlvoTipoAviso.SALA); a.setSalaId(sid); alvos.add(a);
            } }
            case OPERADOR -> { for (String oid : operadorIds) {
                AvisoAlvo a = new AvisoAlvo(); a.setCadastroId(cadastroId); a.setAlvoTipo(AlvoTipoAviso.OPERADOR); a.setOperadorId(oid); alvos.add(a);
            } }
            case TECNICO -> { for (String tid : tecnicoIds) {
                AvisoAlvo a = new AvisoAlvo(); a.setCadastroId(cadastroId); a.setAlvoTipo(AlvoTipoAviso.TECNICO); a.setTecnicoId(tid); alvos.add(a);
            } }
            case TODOS_OPERADORES, TODOS_TECNICOS, TODOS -> {
                AvisoAlvo a = new AvisoAlvo(); a.setCadastroId(cadastroId); a.setAlvoTipo(alvoTipo); alvos.add(a);
            }
        }
        return alvos;
    }

    private boolean temCiencia(String cadastroId, Integer salaId, String pessoaId, PapelPessoa papel) {
        return papel == PapelPessoa.OPERADOR
                ? cienciaRepo.findByCadastroIdAndSalaIdAndOperadorId(cadastroId, salaId, pessoaId).isPresent()
                : cienciaRepo.findByCadastroIdAndSalaIdAndTecnicoId(cadastroId, salaId, pessoaId).isPresent();
    }

    private Map<String, Object> montarPayloadPendente(String cadastroId, boolean manter) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cadastro_id", cadastroId);
        m.put("manter_apos_ciencia", manter);
        m.put("mensagens", mensagemRepo.findByCadastroIdOrderByOrdem(cadastroId).stream()
                .map(this::mensagemToMap).toList());
        return m;
    }

    private Map<String, Object> mensagemToMap(AvisoMensagem x) {
        Map<String, Object> mm = new LinkedHashMap<>();
        mm.put("ordem", x.getOrdem());
        mm.put("texto", x.getTexto());
        return mm;
    }

    private Map<String, Object> alvoToMap(AvisoAlvo a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("alvo_tipo", a.getAlvoTipo().name());
        String desc = switch (a.getAlvoTipo()) {
            case SALA -> salaRepo.findById(a.getSalaId()).map(s -> s.getNome()).orElse("Sala " + a.getSalaId());
            case OPERADOR -> operadorRepo.findById(a.getOperadorId()).map(o -> o.getNomeCompleto()).orElse(a.getOperadorId());
            case TECNICO -> tecnicoRepo.findById(a.getTecnicoId()).map(t -> t.getNomeCompleto()).orElse(a.getTecnicoId());
            case TODOS_OPERADORES -> "Todos os operadores";
            case TODOS_TECNICOS -> "Todos os técnicos";
            case TODOS -> "Todos";
        };
        m.put("descricao", desc);
        return m;
    }

    private Map<String, Object> cienciaToMap(AvisoCiencia c) {
        Map<String, Object> m = new LinkedHashMap<>();
        String nome;
        String papel;
        if (c.getOperadorId() != null) {
            papel = "Operador";
            nome = operadorRepo.findById(c.getOperadorId()).map(o -> o.getNomeCompleto()).orElse(c.getOperadorId());
        } else {
            papel = "Técnico";
            nome = tecnicoRepo.findById(c.getTecnicoId()).map(t -> t.getNomeCompleto()).orElse(c.getTecnicoId());
        }
        m.put("nome", nome);
        m.put("papel", papel);
        m.put("ciente_em", c.getCienteEm() != null ? c.getCienteEm().toString() : null);
        return m;
    }

    private Map<String, Object> toResumo(AvisoCadastro c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("numero", c.getNumero());
        m.put("tipo", c.getTipo() != null ? c.getTipo().name() : null);
        m.put("tipo_label", c.getTipo() != null ? c.getTipo().getLabel() : null);
        m.put("permanente", Boolean.TRUE.equals(c.getPermanente()));
        m.put("duracao_dias", c.getDuracaoDias());
        m.put("manter_apos_ciencia", Boolean.TRUE.equals(c.getManterAposCiencia()));
        m.put("status", c.getStatus() != null ? c.getStatus().getValor() : null);
        m.put("criado_em", c.getCriadoEm() != null ? c.getCriadoEm().toString() : null);
        m.put("expira_em", c.getExpiraEm() != null ? c.getExpiraEm().toString() : null);
        m.put("criado_por", adminRepo.findById(c.getCriadoPorId())
                .map(a -> a.getNomeCompleto()).orElse(c.getCriadoPorId()));
        return m;
    }
}
