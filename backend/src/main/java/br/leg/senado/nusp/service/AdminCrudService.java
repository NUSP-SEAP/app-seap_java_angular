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

    @Value("${app.admin.master-username}")
    private String masterUsername;

    // ══ Criação de Operador ═════════════════════════════════════

    @Transactional
    public Map<String, Object> criarOperador(String nomeCompleto, String nomeExibicao,
                                              String email, String username, String senha,
                                              MultipartFile foto, boolean plenarioPrincipal) {
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

        boolean emailExists = operadorRepo.findByEmail(email).isPresent();
        boolean usernameExists = operadorRepo.findByUsername(username).isPresent();
        if (emailExists || usernameExists) {
            String msg;
            if (emailExists && usernameExists) msg = "E-mail e usuário já cadastrados";
            else if (emailExists) msg = "E-mail já cadastrado";
            else msg = "Nome de usuário já cadastrado";
            throw new ServiceValidationException("conflict", HttpStatus.CONFLICT,
                    Map.of("message", msg));
        }

        String fotoUrl = "";
        if (foto != null && !foto.isEmpty()) {
            fotoUrl = salvarFoto(username, foto);
        }

        Operador op = new Operador();
        op.setNomeCompleto(nomeCompleto.strip());
        op.setNomeExibicao(nomeExibicao.strip());
        op.setEmail(email);
        op.setUsername(username);
        op.setPasswordHash(passwordEncoder.encode(senha));
        op.setFotoUrl(fotoUrl.isEmpty() ? null : fotoUrl);
        op.setPlenarioPrincipal(plenarioPrincipal);
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

    private String salvarFoto(String username, MultipartFile foto) {
        String ext = extractExtension(foto);
        long ts = System.currentTimeMillis();
        String filename = username + "_" + ts + "." + ext;

        Path saveDir = Paths.get(filesDir, operadoresDirname);
        try {
            Files.createDirectories(saveDir);
            foto.transferTo(saveDir.resolve(filename).toFile());
        } catch (IOException e) {
            log.error("Erro ao salvar foto do operador: {}", e.getMessage());
            throw new ServiceValidationException("Erro ao salvar foto",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }

        return filesUrlPrefix.replaceAll("/$", "") + "/" + operadoresDirname + "/" + filename;
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

        boolean emailExists = administradorRepo.findByEmail(email).isPresent();
        boolean usernameExists = administradorRepo.findByUsername(username).isPresent();
        if (emailExists || usernameExists) {
            String msg;
            if (emailExists && usernameExists) msg = "E-mail e usuário já cadastrados";
            else if (emailExists) msg = "E-mail já cadastrado";
            else msg = "Nome de usuário já cadastrado";
            throw new ServiceValidationException("conflict", HttpStatus.CONFLICT,
                    Map.of("message", msg));
        }

        Administrador admin = new Administrador();
        admin.setNomeCompleto(nomeCompleto.strip());
        admin.setEmail(email);
        admin.setUsername(username);
        admin.setPasswordHash(passwordEncoder.encode(senha));
        admin = administradorRepo.save(admin);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", admin.getId());
        result.put("nome_completo", admin.getNomeCompleto());
        result.put("email", admin.getEmail());
        result.put("username", admin.getUsername());
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
        operadorRepo.save(op);
        return novo;
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

    // ══ Helpers ═════════════════════════════════════════════════

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
