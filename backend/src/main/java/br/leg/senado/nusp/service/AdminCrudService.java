package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.*;
import br.leg.senado.nusp.enums.TipoWidget;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Lógica de negócio do Admin CRUD — equivale a api/views/admin.py +
 * api/db/pessoa.py + api/db/form_edit.py do Python.
 */
@Service
@RequiredArgsConstructor
public class AdminCrudService {

    private static final Logger log = LoggerFactory.getLogger(AdminCrudService.class);

    private final OperadorRepository operadorRepo;
    private final AdministradorRepository administradorRepo;
    private final TecnicoRepository tecnicoRepo;
    private final SalaRepository salaRepo;
    private final ComissaoRepository comissaoRepo;
    private final ChecklistItemTipoRepository itemTipoRepo;
    private final ChecklistSalaConfigRepository salaConfigRepo;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.files.dir}")
    private String filesDir;

    @Value("${app.files.url-prefix}")
    private String filesUrlPrefix;

    @Value("${app.files.operadores-dirname}")
    private String operadoresDirname;

    @Value("${app.files.tecnicos-dirname}")
    private String tecnicosDirname;

    @Value("${app.admin.master-username}")
    private String masterUsername;

    // ══ Criação de Operador ═════════════════════════════════════

    @Transactional
    public Map<String, Object> criarOperador(String nomeCompleto, String nomeExibicao,
                                              String email, String username, String senha,
                                              MultipartFile foto, boolean plenarioPrincipal,
                                              boolean plenarioPrincipalFixo) {
        List<String> faltantes = new ArrayList<>();
        if (isBlank(nomeCompleto)) faltantes.add("nome_completo");
        if (isBlank(nomeExibicao)) faltantes.add("nome_exibicao");
        if (isBlank(email))        faltantes.add("email");
        if (isBlank(username))     faltantes.add("username");
        if (isBlank(senha))        faltantes.add("senha");
        if (!faltantes.isEmpty()) {
            throw new ServiceValidationException("invalid_payload", HttpStatus.BAD_REQUEST,
                    Map.of("missing", String.join(", ", faltantes)));
        }

        email = email.strip().toLowerCase();
        username = username.strip().toLowerCase();
        validateUsername(username);
        verificarConflitoUsernameEmail(email, username);

        String fotoUrl = "";
        if (foto != null && !foto.isEmpty()) {
            fotoUrl = salvarFoto(username, foto, operadoresDirname);
        }

        Operador op = new Operador();
        op.setNomeCompleto(nomeCompleto.strip());
        op.setNomeExibicao(nomeExibicao.strip());
        op.setEmail(email);
        op.setUsername(username);
        op.setPasswordHash(passwordEncoder.encode(senha));
        op.setFotoUrl(fotoUrl.isEmpty() ? null : fotoUrl);
        op.setPlenarioPrincipal(plenarioPrincipal);
        op.setPlenarioPrincipalFixo(plenarioPrincipalFixo);
        op = operadorRepo.save(op);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", op.getId());
        result.put("nome_completo", op.getNomeCompleto());
        result.put("nome_exibicao", op.getNomeExibicao());
        result.put("email", op.getEmail());
        result.put("username", op.getUsername());
        result.put("foto_url", op.getFotoUrl() != null ? op.getFotoUrl() : "");
        return result;
    }

    private String salvarFoto(String username, MultipartFile foto, String dirname) {
        String ext = extractExtension(foto);
        long ts = System.currentTimeMillis();
        String filename = username + "_" + ts + "." + ext;

        Path saveDir = Paths.get(filesDir, dirname);
        try {
            Files.createDirectories(saveDir);
            foto.transferTo(saveDir.resolve(filename).toFile());
        } catch (IOException e) {
            log.error("Erro ao salvar foto: {}", e.getMessage());
            throw new ServiceValidationException("Erro ao salvar foto",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }

        return filesUrlPrefix.replaceAll("/$", "") + "/" + dirname + "/" + filename;
    }

    /**
     * Apaga o arquivo físico correspondente a uma fotoUrl (ex.: "/files/operadores/x.jpg").
     * Best-effort: nunca lança — apenas loga em caso de erro. Protegido contra path traversal.
     */
    private void apagarFotoFisica(String fotoUrl) {
        if (isBlank(fotoUrl)) return;
        try {
            String prefix = filesUrlPrefix.replaceAll("/$", "");
            String rel = fotoUrl.startsWith(prefix) ? fotoUrl.substring(prefix.length()) : fotoUrl;
            rel = rel.replaceFirst("^/+", "");
            Path base = Paths.get(filesDir).toAbsolutePath().normalize();
            Path alvo = base.resolve(rel).normalize();
            if (!alvo.startsWith(base)) {
                log.warn("Ignorando remoção de foto fora do diretório de arquivos: {}", fotoUrl);
                return;
            }
            Files.deleteIfExists(alvo);
        } catch (Exception e) {
            log.warn("Não foi possível apagar a foto antiga ({}): {}", fotoUrl, e.getMessage());
        }
    }

    private String extractExtension(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name != null && name.contains(".")) {
            return name.substring(name.lastIndexOf('.') + 1).toLowerCase();
        }
        String ct = file.getContentType();
        if (ct != null) {
            if (ct.contains("png")) return "png";
            if (ct.contains("gif")) return "gif";
            if (ct.contains("webp")) return "webp";
        }
        return "jpg";
    }

    // Rejeita caracteres que permitem path traversal ou confundem storage/URL.
    private static final java.util.regex.Pattern USERNAME_PATTERN =
            java.util.regex.Pattern.compile("^[a-z0-9._-]{3,64}$");

    private static void validateUsername(String username) {
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new ServiceValidationException("invalid_username", HttpStatus.BAD_REQUEST,
                    Map.of("message",
                            "Username deve conter apenas letras minúsculas, números, ponto, traço ou underscore (3 a 64 caracteres)."));
        }
    }

    /**
     * Garante unicidade global de username e email entre as três tabelas de
     * usuários (PES_OPERADOR, PES_ADMINISTRADOR, PES_TECNICO). A unicidade
     * dentro de cada tabela é garantida pelo schema; entre tabelas, é validada
     * aqui na aplicação.
     */
    private void verificarConflitoUsernameEmail(String email, String username) {
        boolean emailExists = operadorRepo.findByEmail(email).isPresent()
                || administradorRepo.findByEmail(email).isPresent()
                || tecnicoRepo.findByEmail(email).isPresent();
        boolean usernameExists = operadorRepo.findByUsername(username).isPresent()
                || administradorRepo.findByUsername(username).isPresent()
                || tecnicoRepo.findByUsername(username).isPresent();
        if (emailExists || usernameExists) {
            String msg;
            if (emailExists && usernameExists) msg = "E-mail e usuário já cadastrados";
            else if (emailExists) msg = "E-mail já cadastrado";
            else msg = "Nome de usuário já cadastrado";
            throw new ServiceValidationException("conflict", HttpStatus.CONFLICT,
                    Map.of("message", msg));
        }
    }

    // ══ Criação de Administrador ════════════════════════════════

    @Transactional
    public Map<String, Object> criarAdministrador(String nomeCompleto, String email,
                                                   String username, String senha,
                                                   String callerUsername) {
        if (!masterUsername.equalsIgnoreCase(callerUsername)) {
            throw new ServiceValidationException("forbidden", HttpStatus.FORBIDDEN);
        }

        List<String> faltantes = new ArrayList<>();
        if (isBlank(nomeCompleto)) faltantes.add("nome_completo");
        if (isBlank(email))        faltantes.add("email");
        if (isBlank(username))     faltantes.add("username");
        if (isBlank(senha))        faltantes.add("senha");
        if (!faltantes.isEmpty()) {
            throw new ServiceValidationException("invalid_payload", HttpStatus.BAD_REQUEST,
                    Map.of("missing", String.join(", ", faltantes)));
        }

        email = email.strip().toLowerCase();
        username = username.strip().toLowerCase();
        validateUsername(username);
        verificarConflitoUsernameEmail(email, username);

        Administrador admin = new Administrador();
        admin.setNomeCompleto(nomeCompleto.strip());
        admin.setEmail(email);
        admin.setUsername(username);
        admin.setPasswordHash(passwordEncoder.encode(senha));
        admin.setSenhaProvisoria(true);
        admin = administradorRepo.save(admin);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", admin.getId());
        result.put("nome_completo", admin.getNomeCompleto());
        result.put("email", admin.getEmail());
        result.put("username", admin.getUsername());
        return result;
    }

    // ══ Criação de Técnico ══════════════════════════════════════

    @Transactional
    public Map<String, Object> criarTecnico(String nomeCompleto, String email,
                                             String username, String senha,
                                             MultipartFile foto) {
        List<String> faltantes = new ArrayList<>();
        if (isBlank(nomeCompleto)) faltantes.add("nome_completo");
        if (isBlank(email))        faltantes.add("email");
        if (isBlank(username))     faltantes.add("username");
        if (isBlank(senha))        faltantes.add("senha");
        if (!faltantes.isEmpty()) {
            throw new ServiceValidationException("invalid_payload", HttpStatus.BAD_REQUEST,
                    Map.of("missing", String.join(", ", faltantes)));
        }

        email = email.strip().toLowerCase();
        username = username.strip().toLowerCase();
        validateUsername(username);
        verificarConflitoUsernameEmail(email, username);

        String fotoUrl = "";
        if (foto != null && !foto.isEmpty()) {
            fotoUrl = salvarFoto(username, foto, tecnicosDirname);
        }

        Tecnico tec = new Tecnico();
        tec.setNomeCompleto(nomeCompleto.strip());
        tec.setEmail(email);
        tec.setUsername(username);
        tec.setPasswordHash(passwordEncoder.encode(senha));
        tec.setFotoUrl(fotoUrl.isEmpty() ? null : fotoUrl);
        tec = tecnicoRepo.save(tec);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", tec.getId());
        result.put("nome_completo", tec.getNomeCompleto());
        result.put("email", tec.getEmail());
        result.put("username", tec.getUsername());
        result.put("foto_url", tec.getFotoUrl() != null ? tec.getFotoUrl() : "");
        return result;
    }

    // ══ Form Edit — Listar ══════════════════════════════════════

    public Map<String, Object> listFormEditItems(String entidade) {
        List<Map<String, Object>> items = switch (entidade) {
            case "salas" -> listSalas();
            case "comissoes" -> listComissoes();
            default -> throw new ServiceValidationException(
                    "ENTIDADE_INVALIDA", HttpStatus.BAD_REQUEST,
                    Map.of("message", "Entidade inválida: '" + entidade + "'"));
        };

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entity", entidade);
        result.put("items", items);
        return result;
    }

    private List<Map<String, Object>> listSalas() {
        return salaRepo.findAllOrdered().stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("nome", s.getNome());
            m.put("ordem", Boolean.TRUE.equals(s.getAtivo()) ? s.getOrdem() : null);
            m.put("ativo", s.getAtivo());
            return m;
        }).toList();
    }

    private List<Map<String, Object>> listComissoes() {
        return comissaoRepo.findAllOrdered().stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("nome", c.getNome());
            m.put("ordem", Boolean.TRUE.equals(c.getAtivo()) ? c.getOrdem() : null);
            m.put("ativo", c.getAtivo());
            return m;
        }).toList();
    }

    // ══ Form Edit — Salvar ══════════════════════════════════════

    @Transactional
    public Map<String, Object> saveFormEditItems(String entidade, List<Map<String, Object>> items,
                                                  String userId) {
        if (!"salas".equals(entidade) && !"comissoes".equals(entidade)) {
            throw new ServiceValidationException("ENTIDADE_INVALIDA", HttpStatus.BAD_REQUEST,
                    Map.of("message", "Entidade inválida: '" + entidade + "'"));
        }

        int ordemCounter = 1;
        int created = 0, updated = 0;

        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            Map<String, Object> item = items.get(idx);
            Object rawId = item.get("id");
            String nome = item.get("nome") != null ? item.get("nome").toString().strip() : "";
            boolean ativo = Boolean.TRUE.equals(item.get("ativo"));

            if (nome.isEmpty()) {
                throw new ServiceValidationException("VALIDACAO", HttpStatus.BAD_REQUEST,
                        Map.of("message", "Nome não pode ser vazio (item na posição " + idx + ")."));
            }

            Integer ordem = ativo ? ordemCounter++ : null;

            if ("salas".equals(entidade)) {
                if (rawId == null) {
                    Sala s = new Sala();
                    s.setNome(nome);
                    s.setAtivo(ativo);
                    s.setOrdem(ordem);
                    salaRepo.save(s);
                    created++;
                } else {
                    int id = toInt(rawId);
                    Sala s = salaRepo.findById(id).orElseThrow(() ->
                            new ServiceValidationException("VALIDACAO", HttpStatus.BAD_REQUEST,
                                    Map.of("message", "Registro com id " + id + " não encontrado (posição " + idx + ").")));
                    s.setNome(nome);
                    s.setAtivo(ativo);
                    s.setOrdem(ordem);
                    salaRepo.save(s);
                    updated++;
                }
            } else {
                if (rawId == null) {
                    Comissao c = new Comissao();
                    c.setNome(nome);
                    c.setAtivo(ativo);
                    c.setOrdem(ordem);
                    c.setCriadoPor(userId);
                    c.setAtualizadoPor(userId);
                    comissaoRepo.save(c);
                    created++;
                } else {
                    long id = toLong(rawId);
                    Comissao c = comissaoRepo.findById(id).orElseThrow(() ->
                            new ServiceValidationException("VALIDACAO", HttpStatus.BAD_REQUEST,
                                    Map.of("message", "Registro com id " + id + " não encontrado (posição " + idx + ").")));
                    c.setNome(nome);
                    c.setAtivo(ativo);
                    c.setOrdem(ordem);
                    c.setAtualizadoPor(userId);
                    comissaoRepo.save(c);
                    updated++;
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entity", entidade);
        result.put("created", created);
        result.put("updated", updated);
        return result;
    }

    // ══ Sala Config — Listar ════════════════════════════════════

    public Map<String, Object> listSalaConfigItems(int salaId) {
        validateSalaId(salaId);

        List<Object[]> rows = salaConfigRepo.findConfigItemsBySalaId(salaId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ((Number) row[0]).intValue());
            m.put("item_tipo_id", ((Number) row[1]).intValue());
            m.put("nome", row[2] != null ? row[2].toString() : "");
            m.put("tipo_widget", row[3] != null ? row[3].toString() : "radio");
            m.put("ordem", row[4] != null ? ((Number) row[4]).intValue() : null);
            m.put("ativo", row[5] != null && ((Number) row[5]).intValue() == 1);
            items.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sala_id", salaId);
        result.put("items", items);
        return result;
    }

    // ══ Sala Config — Salvar ════════════════════════════════════

    @Transactional
    public Map<String, Object> saveSalaConfigItems(int salaId, List<Map<String, Object>> items) {
        validateSalaId(salaId);
        int[] counts = doSaveSalaConfig(salaId, items);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sala_id", salaId);
        result.put("created", counts[0]);
        result.put("updated", counts[1]);
        return result;
    }

    // ══ Sala Config — Aplicar a Todas ═══════════════════════════

    @Transactional
    public Map<String, Object> applySalaConfigToAll(int sourceSalaId, List<Map<String, Object>> items) {
        if (sourceSalaId <= 0) {
            throw new ServiceValidationException("LOCAL_ID_INVALIDO", HttpStatus.BAD_REQUEST,
                    Map.of("message", "O ID do local de origem deve ser um número válido."));
        }

        List<Sala> salasAtivas = salaRepo.findAtivasOrdenadas();
        int count = 0;
        for (Sala sala : salasAtivas) {
            // Aplicar somente nos plenários numerados (ex: "Plenário 02")
            if (sala.getNome() == null || !sala.getNome().matches("(?i)plenário \\d+")) continue;
            try {
                doSaveSalaConfig(sala.getId(), items);
                count++;
            } catch (Exception e) {
                log.error("Erro ao aplicar config na sala {}: {}", sala.getId(), e.getMessage());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source_sala_id", sourceSalaId);
        result.put("salas_atualizadas", count);
        return result;
    }

    // ══ Lógica compartilhada de sala config ═════════════════════

    /**
     * Lógica interna de save sala config, usada por saveSalaConfigItems e applySalaConfigToAll.
     * Retorna [created, updated].
     */
    private int[] doSaveSalaConfig(int salaId, List<Map<String, Object>> items) {
        // 1. Filtrar apenas itens ativos com nome
        int ordemCounter = 1;
        List<Map<String, Object>> cleaned = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String nome = item.get("nome") != null ? item.get("nome").toString().strip() : "";
            String tipoWidget = item.get("tipo_widget") != null ? item.get("tipo_widget").toString() : "radio";
            boolean ativo = item.get("ativo") == null || Boolean.TRUE.equals(item.get("ativo"));

            if (nome.isEmpty() || !ativo) continue;

            if (!"radio".equals(tipoWidget) && !"text".equals(tipoWidget)) {
                tipoWidget = "radio";
            }

            Map<String, Object> clean = new LinkedHashMap<>();
            clean.put("nome", nome);
            clean.put("tipo_widget", tipoWidget);
            clean.put("ordem", ordemCounter++);
            cleaned.add(clean);
        }

        // 2. Desativar todos os itens existentes desta sala
        salaConfigRepo.deactivateAllBySalaId(salaId);

        int created = 0, updated = 0;

        // 3. Para cada item ativo: find_or_create item_tipo + upsert config
        for (Map<String, Object> item : cleaned) {
            String nome = item.get("nome").toString();
            String tipoWidget = item.get("tipo_widget").toString();
            int ordem = (int) item.get("ordem");

            TipoWidget tw = "text".equals(tipoWidget) ? TipoWidget.TEXT : TipoWidget.RADIO;

            // Find or create item tipo
            ChecklistItemTipo itemTipo = itemTipoRepo.findByNomeAndTipoWidget(nome, tw)
                    .orElseGet(() -> {
                        ChecklistItemTipo novo = new ChecklistItemTipo();
                        novo.setNome(nome);
                        novo.setTipoWidget(tw);
                        return itemTipoRepo.save(novo);
                    });

            // Upsert config
            Optional<ChecklistSalaConfig> existing =
                    salaConfigRepo.findBySalaIdAndItemTipoId(salaId, itemTipo.getId());

            if (existing.isPresent()) {
                ChecklistSalaConfig config = existing.get();
                config.setAtivo(true);
                config.setOrdem(ordem);
                salaConfigRepo.save(config);
                updated++;
            } else {
                ChecklistSalaConfig config = new ChecklistSalaConfig();
                config.setSalaId(salaId);
                config.setItemTipoId(itemTipo.getId());
                config.setOrdem(ordem);
                config.setAtivo(true);
                salaConfigRepo.save(config);
                created++;
            }
        }

        return new int[]{created, updated};
    }

    // ══ Toggle Plenário Principal ═════════════════════════════════

    @Transactional
    public boolean togglePlenarioPrincipal(String operadorId) {
        Operador op = operadorRepo.findById(operadorId)
                .orElseThrow(() -> new ServiceValidationException("NOT_FOUND", HttpStatus.NOT_FOUND,
                        Map.of("message", "Operador não encontrado.")));
        boolean novo = !Boolean.TRUE.equals(op.getPlenarioPrincipal());
        op.setPlenarioPrincipal(novo);
        // Ao desmarcar "apto", deixa de fazer sentido manter como "fixo" do PP
        if (!novo) op.setPlenarioPrincipalFixo(false);
        operadorRepo.save(op);
        return novo;
    }

    @Transactional
    public boolean togglePlenarioPrincipalFixo(String operadorId) {
        Operador op = operadorRepo.findById(operadorId)
                .orElseThrow(() -> new ServiceValidationException("NOT_FOUND", HttpStatus.NOT_FOUND,
                        Map.of("message", "Operador não encontrado.")));
        boolean novo = !Boolean.TRUE.equals(op.getPlenarioPrincipalFixo());
        if (novo && !Boolean.TRUE.equals(op.getPlenarioPrincipal())) {
            throw new ServiceValidationException("INVALIDO", HttpStatus.BAD_REQUEST,
                    Map.of("message", "Operador precisa estar apto a Plenário Principal antes de ser marcado como fixo."));
        }
        op.setPlenarioPrincipalFixo(novo);
        operadorRepo.save(op);
        return novo;
    }

    // ══ Atualizar Turno ═══════════════════════════════════════════

    @Transactional
    public String setTurnoOperador(String operadorId, String turno) {
        if (!"M".equals(turno) && !"V".equals(turno)) {
            throw new ServiceValidationException("TURNO_INVALIDO", HttpStatus.BAD_REQUEST,
                    Map.of("message", "Turno deve ser 'M' (Matutino) ou 'V' (Vespertino)."));
        }
        Operador op = operadorRepo.findById(operadorId)
                .orElseThrow(() -> new ServiceValidationException("NOT_FOUND", HttpStatus.NOT_FOUND,
                        Map.of("message", "Operador não encontrado.")));
        op.setTurno(turno);
        operadorRepo.save(op);
        return turno;
    }

    // ══ Toggle Participa Escala ═══════════════════════════════════

    @Transactional
    public boolean toggleParticipaEscala(String operadorId) {
        Operador op = operadorRepo.findById(operadorId)
                .orElseThrow(() -> new ServiceValidationException("NOT_FOUND", HttpStatus.NOT_FOUND,
                        Map.of("message", "Operador não encontrado.")));
        boolean novo = !Boolean.TRUE.equals(op.getParticipaEscala());
        op.setParticipaEscala(novo);
        operadorRepo.save(op);
        return novo;
    }

    // ══ Alterar Senha de Operador ════════════════════════════════

    @Transactional
    public void changeOperadorPassword(String operadorId, String novaSenha) {
        Operador op = operadorRepo.findById(operadorId)
                .orElseThrow(() -> new ServiceValidationException("NOT_FOUND", HttpStatus.NOT_FOUND,
                        Map.of("message", "Operador não encontrado.")));
        op.setPasswordHash(passwordEncoder.encode(novaSenha));
        operadorRepo.save(op);
    }

    // ══ Perfil de Operador — Buscar ══════════════════════════════

    public Map<String, Object> getOperadorPerfil(String id) {
        Operador op = operadorRepo.findById(id)
                .orElseThrow(() -> new ServiceValidationException("NOT_FOUND", HttpStatus.NOT_FOUND,
                        Map.of("message", "Operador não encontrado.")));
        return operadorToMap(op);
    }

    // ══ Perfil de Operador — Atualizar ═══════════════════════════

    @Transactional
    public Map<String, Object> atualizarOperador(
            String id, String nomeCompleto, String nomeExibicao, String email, String turno,
            String cargaHorariaRaw, String horarioInicio, String horarioFim,
            boolean plenarioPrincipal, boolean plenarioPrincipalFixo, boolean participaEscala,
            MultipartFile foto) {

        Operador op = operadorRepo.findById(id)
                .orElseThrow(() -> new ServiceValidationException("NOT_FOUND", HttpStatus.NOT_FOUND,
                        Map.of("message", "Operador não encontrado.")));

        List<String> faltantes = new ArrayList<>();
        if (isBlank(nomeCompleto)) faltantes.add("nome_completo");
        if (isBlank(nomeExibicao)) faltantes.add("nome_exibicao");
        if (isBlank(email))        faltantes.add("email");
        if (!faltantes.isEmpty()) {
            throw new ServiceValidationException("invalid_payload", HttpStatus.BAD_REQUEST,
                    Map.of("missing", String.join(", ", faltantes)));
        }

        if (!"M".equals(turno) && !"V".equals(turno)) {
            throw new ServiceValidationException("TURNO_INVALIDO", HttpStatus.BAD_REQUEST,
                    Map.of("message", "Turno deve ser 'M' (Matutino) ou 'V' (Vespertino)."));
        }

        Integer cargaHoraria = parseCargaHoraria(cargaHorariaRaw);
        String horaInicio = normalizarHora(horarioInicio);
        String horaFim = normalizarHora(horarioFim);

        // "Fixo do PP" só faz sentido se o operador estiver apto ao PP
        if (plenarioPrincipalFixo && !plenarioPrincipal) {
            throw new ServiceValidationException("INVALIDO", HttpStatus.BAD_REQUEST,
                    Map.of("message", "Operador precisa estar apto a Plenário Principal antes de ser marcado como fixo."));
        }

        // E-mail: normaliza e, se mudou, valida conflito global (operador/admin/técnico)
        String novoEmail = email.strip().toLowerCase();
        if (!novoEmail.equals(op.getEmail())) {
            verificarConflitoEmail(novoEmail, "operador", id);
        }

        // Foto: só substitui se uma nova for enviada. Salva a nova primeiro
        // (se falhar, mantém a antiga) e só então apaga o arquivo anterior.
        if (foto != null && !foto.isEmpty()) {
            String urlAntiga = op.getFotoUrl();
            op.setFotoUrl(salvarFoto(op.getUsername(), foto, operadoresDirname));
            apagarFotoFisica(urlAntiga);
        }

        op.setNomeCompleto(nomeCompleto.strip());
        op.setNomeExibicao(nomeExibicao.strip());
        op.setEmail(novoEmail);
        op.setTurno(turno);
        op.setCargaHoraria(cargaHoraria);
        op.setHorarioTrabalhoInicio(horaInicio);
        op.setHorarioTrabalhoFim(horaFim);
        op.setPlenarioPrincipal(plenarioPrincipal);
        op.setPlenarioPrincipalFixo(plenarioPrincipal && plenarioPrincipalFixo);
        op.setParticipaEscala(participaEscala);
        op = operadorRepo.save(op);

        return operadorToMap(op);
    }

    private Map<String, Object> operadorToMap(Operador op) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", op.getId());
        m.put("nome_completo", op.getNomeCompleto());
        m.put("nome_exibicao", op.getNomeExibicao());
        m.put("email", op.getEmail());
        m.put("username", op.getUsername());
        m.put("foto_url", op.getFotoUrl() != null ? op.getFotoUrl() : "");
        m.put("turno", op.getTurno());
        m.put("carga_horaria", op.getCargaHoraria());
        m.put("horario_trabalho_inicio", op.getHorarioTrabalhoInicio());
        m.put("horario_trabalho_fim", op.getHorarioTrabalhoFim());
        m.put("plenario_principal", Boolean.TRUE.equals(op.getPlenarioPrincipal()));
        m.put("plenario_principal_fixo", Boolean.TRUE.equals(op.getPlenarioPrincipalFixo()));
        m.put("participa_escala", Boolean.TRUE.equals(op.getParticipaEscala()));
        return m;
    }

    // ══ Perfil de Técnico — Buscar ═══════════════════════════════

    public Map<String, Object> getTecnicoPerfil(String id) {
        Tecnico tec = tecnicoRepo.findById(id)
                .orElseThrow(() -> new ServiceValidationException("NOT_FOUND", HttpStatus.NOT_FOUND,
                        Map.of("message", "Técnico não encontrado.")));
        return tecnicoToMap(tec);
    }

    // ══ Perfil de Técnico — Atualizar ════════════════════════════

    @Transactional
    public Map<String, Object> atualizarTecnico(
            String id, String nomeCompleto, String email, String turno,
            String cargaHorariaRaw, String horarioInicio, String horarioFim,
            MultipartFile foto) {

        Tecnico tec = tecnicoRepo.findById(id)
                .orElseThrow(() -> new ServiceValidationException("NOT_FOUND", HttpStatus.NOT_FOUND,
                        Map.of("message", "Técnico não encontrado.")));

        List<String> faltantes = new ArrayList<>();
        if (isBlank(nomeCompleto)) faltantes.add("nome_completo");
        if (isBlank(email))        faltantes.add("email");
        if (!faltantes.isEmpty()) {
            throw new ServiceValidationException("invalid_payload", HttpStatus.BAD_REQUEST,
                    Map.of("missing", String.join(", ", faltantes)));
        }

        // Técnico: turno é OPCIONAL (pode ficar NULL)
        String turnoNorm = normalizarTurnoOpcional(turno);
        Integer cargaHoraria = parseCargaHoraria(cargaHorariaRaw);
        String horaInicio = normalizarHora(horarioInicio);
        String horaFim = normalizarHora(horarioFim);

        String novoEmail = email.strip().toLowerCase();
        if (!novoEmail.equals(tec.getEmail())) {
            verificarConflitoEmail(novoEmail, "tecnico", id);
        }

        // Foto: salva a nova primeiro e só então apaga a anterior (mesma lógica do operador)
        if (foto != null && !foto.isEmpty()) {
            String urlAntiga = tec.getFotoUrl();
            tec.setFotoUrl(salvarFoto(tec.getUsername(), foto, tecnicosDirname));
            apagarFotoFisica(urlAntiga);
        }

        tec.setNomeCompleto(nomeCompleto.strip());
        tec.setEmail(novoEmail);
        tec.setTurno(turnoNorm);
        tec.setCargaHoraria(cargaHoraria);
        tec.setHorarioTrabalhoInicio(horaInicio);
        tec.setHorarioTrabalhoFim(horaFim);
        tec = tecnicoRepo.save(tec);

        return tecnicoToMap(tec);
    }

    private Map<String, Object> tecnicoToMap(Tecnico tec) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", tec.getId());
        m.put("nome_completo", tec.getNomeCompleto());
        m.put("email", tec.getEmail());
        m.put("username", tec.getUsername());
        m.put("foto_url", tec.getFotoUrl() != null ? tec.getFotoUrl() : "");
        m.put("turno", tec.getTurno());
        m.put("carga_horaria", tec.getCargaHoraria());
        m.put("horario_trabalho_inicio", tec.getHorarioTrabalhoInicio());
        m.put("horario_trabalho_fim", tec.getHorarioTrabalhoFim());
        return m;
    }

    /**
     * Valida unicidade de e-mail entre as três tabelas (operador/admin/técnico),
     * ignorando o próprio registro (tipo + id) que está sendo editado.
     */
    private void verificarConflitoEmail(String email, String tipoIgnorar, String idIgnorar) {
        boolean emOperador = operadorRepo.findByEmail(email)
                .filter(o -> !("operador".equals(tipoIgnorar) && o.getId().equals(idIgnorar))).isPresent();
        boolean emAdmin = administradorRepo.findByEmail(email)
                .filter(a -> !("admin".equals(tipoIgnorar) && a.getId().equals(idIgnorar))).isPresent();
        boolean emTecnico = tecnicoRepo.findByEmail(email)
                .filter(t -> !("tecnico".equals(tipoIgnorar) && t.getId().equals(idIgnorar))).isPresent();
        if (emOperador || emAdmin || emTecnico) {
            throw new ServiceValidationException("conflict", HttpStatus.CONFLICT,
                    Map.of("message", "E-mail já cadastrado para outro usuário."));
        }
    }

    /** null/vazio → null; senão exige formato HH:MM (00:00–23:59). */
    private String normalizarHora(String raw) {
        if (isBlank(raw)) return null;
        String h = raw.strip();
        if (!HORA_PATTERN.matcher(h).matches()) {
            throw new ServiceValidationException("HORA_INVALIDA", HttpStatus.BAD_REQUEST,
                    Map.of("message", "Horário inválido: '" + h + "'. Use o formato HH:MM."));
        }
        return h;
    }

    /** null/vazio → null; senão exige 30 ou 40. */
    private Integer parseCargaHoraria(String raw) {
        if (isBlank(raw)) return null;
        int v;
        try {
            v = Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            throw new ServiceValidationException("CARGA_INVALIDA", HttpStatus.BAD_REQUEST,
                    Map.of("message", "Carga horária inválida."));
        }
        if (v != 30 && v != 40) {
            throw new ServiceValidationException("CARGA_INVALIDA", HttpStatus.BAD_REQUEST,
                    Map.of("message", "Carga horária deve ser 30 ou 40."));
        }
        return v;
    }

    /** null/vazio → null; senão exige 'M' ou 'V' (turno opcional — usado no técnico). */
    private String normalizarTurnoOpcional(String raw) {
        if (isBlank(raw)) return null;
        String t = raw.strip();
        if (!"M".equals(t) && !"V".equals(t)) {
            throw new ServiceValidationException("TURNO_INVALIDO", HttpStatus.BAD_REQUEST,
                    Map.of("message", "Turno deve ser 'M' (Matutino) ou 'V' (Vespertino)."));
        }
        return t;
    }

    // ══ Helpers ═════════════════════════════════════════════════

    private static final java.util.regex.Pattern HORA_PATTERN =
            java.util.regex.Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    private void validateSalaId(int salaId) {
        if (salaId <= 0) {
            throw new ServiceValidationException("LOCAL_ID_INVALIDO", HttpStatus.BAD_REQUEST,
                    Map.of("message", "O ID do local deve ser um número válido."));
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.strip().isEmpty();
    }

    private int toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        return Integer.parseInt(o.toString());
    }

    private long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(o.toString());
    }
}
