package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.Administrador;
import br.leg.senado.nusp.entity.MetabaseUser;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.AdministradorRepository;
import br.leg.senado.nusp.repository.MetabaseUserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * SSO "caseiro" com Metabase OSS.
 *
 * Fluxo: ao chamar {@link #getLoginCookieFor(Administrador)}:
 *   1) Verifica se o admin já tem registro em INT_METABASE_USER
 *   2) Se não tem: faz login como admin do Metabase, cria o user via API,
 *      gera senha aleatória, salva criptografada
 *   3) Faz login no Metabase com email+senha do user, retorna o cookie
 *      metabase.SESSION para o controller propagar via Set-Cookie
 *
 * O cookie de admin do Metabase (usado nas operações de gerenciamento)
 * é cacheado em memória; é renovado quando expira ou em caso de 401.
 *
 * Segurança: senhas armazenadas com AES-GCM (chave em
 * {@code app.metabase.encrypt-key}). A chave NUNCA vai pro Git.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MetabaseSsoService {

    private final AdministradorRepository administradorRepository;
    private final MetabaseUserRepository metabaseUserRepository;

    @Value("${app.metabase.enabled:true}")
    private boolean enabled;

    @Value("${app.metabase.url:}")
    private String metabaseUrl;

    @Value("${app.metabase.admin-email:}")
    private String adminEmail;

    @Value("${app.metabase.admin-password:}")
    private String adminPassword;

    @Value("${app.metabase.encrypt-key:}")
    private String encryptKey;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RNG = new SecureRandom();
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    /** Cache do cookie admin do Metabase (renovado em demanda). */
    private volatile String adminSessionCookie = null;
    private volatile Instant adminSessionExpiresAt = Instant.EPOCH;

    public boolean isEnabled() {
        return enabled && !metabaseUrl.isBlank() && !adminEmail.isBlank()
                && !adminPassword.isBlank() && !encryptKey.isBlank();
    }

    /** Retorna a URL pública do Metabase para o frontend abrir. */
    public String getMetabaseUrl() {
        return metabaseUrl;
    }

    /**
     * Garante que o admin tem um usuário no Metabase e retorna um cookie
     * de sessão válido para esse usuário. O controller deve propagar
     * esse cookie como Set-Cookie com Domain=.senado-nusp.cloud.
     */
    @Transactional
    public String getLoginCookieFor(Administrador admin) {
        if (!isEnabled()) {
            throw new ServiceValidationException(
                    "Integração com Metabase não está configurada no servidor.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        MetabaseUser link = metabaseUserRepository.findByAdminId(admin.getId())
                .orElseGet(() -> provisionMetabaseUser(admin));
        String password = decrypt(link.getSenhaCifrada());
        try {
            return loginAsUser(link.getEmail(), password);
        } catch (RuntimeException e) {
            // Talvez a senha tenha ficado out-of-sync (admin trocou no Metabase, etc).
            // Re-provisiona: reseta a senha do user via API admin e refaz o link.
            log.warn("Falha ao logar no Metabase como {}; recriando senha", link.getEmail(), e);
            String newPwd = resetUserPassword(link.getMetabaseUserId());
            link.setSenhaCifrada(encrypt(newPwd));
            metabaseUserRepository.save(link);
            return loginAsUser(link.getEmail(), newPwd);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Provisionamento / API admin do Metabase
    // ─────────────────────────────────────────────────────────────────

    private MetabaseUser provisionMetabaseUser(Administrador admin) {
        if (admin.getEmail() == null || admin.getEmail().isBlank()) {
            throw new ServiceValidationException(
                    "Administrador sem e-mail não pode acessar o Metabase.",
                    HttpStatus.BAD_REQUEST);
        }
        String email = admin.getEmail();
        String firstName = firstName(admin.getNomeCompleto());
        String lastName = lastName(admin.getNomeCompleto());
        String password = generateRandomPassword(24);

        Integer mbUserId = createOrFindMetabaseUser(email, firstName, lastName, password);

        MetabaseUser link = new MetabaseUser();
        link.setAdminId(admin.getId());
        link.setEmail(email);
        link.setSenhaCifrada(encrypt(password));
        link.setMetabaseUserId(mbUserId);
        return metabaseUserRepository.save(link);
    }

    /** Cria o user no Metabase OU localiza o existente e reseta a senha. */
    private Integer createOrFindMetabaseUser(String email, String first, String last, String password) {
        String adminCookie = getAdminSession();
        // 1) Tenta criar
        String body = jsonOf(Map.of(
                "first_name", first,
                "last_name", last,
                "email", email,
                "password", password
        ));
        HttpRequest req = HttpRequest.newBuilder(URI.create(metabaseUrl + "/api/user"))
                .header("Content-Type", "application/json")
                .header("X-Metabase-Session", adminCookie)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = send(req);
        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
            return readIntField(resp.body(), "id");
        }
        // 2) Se já existe (400 com "Email address already in use"): busca o id e reseta a senha
        if (resp.statusCode() == 400 && resp.body() != null && resp.body().contains("already in use")) {
            Integer id = findUserIdByEmail(email);
            resetUserPasswordById(id, password);
            return id;
        }
        throw httpError("criar usuário no Metabase", resp);
    }

    private Integer findUserIdByEmail(String email) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(metabaseUrl + "/api/user?include_deactivated=true"))
                .header("X-Metabase-Session", getAdminSession())
                .GET()
                .build();
        HttpResponse<String> resp = send(req);
        if (resp.statusCode() != 200) {
            throw httpError("listar usuários do Metabase", resp);
        }
        try {
            JsonNode root = JSON.readTree(resp.body());
            JsonNode arr = root.has("data") ? root.get("data") : root;
            for (JsonNode u : arr) {
                if (email.equalsIgnoreCase(u.path("email").asText())) {
                    return u.path("id").asInt();
                }
            }
        } catch (Exception e) {
            throw new ServiceValidationException("Resposta inválida do Metabase ao listar usuários.",
                    HttpStatus.BAD_GATEWAY);
        }
        throw new ServiceValidationException("Usuário não encontrado no Metabase: " + email,
                HttpStatus.NOT_FOUND);
    }

    /** Reseta a senha do user e retorna a nova. */
    private String resetUserPassword(Integer mbUserId) {
        String newPwd = generateRandomPassword(24);
        resetUserPasswordById(mbUserId, newPwd);
        return newPwd;
    }

    private void resetUserPasswordById(Integer mbUserId, String newPassword) {
        String body = jsonOf(Map.of("password", newPassword));
        HttpRequest req = HttpRequest.newBuilder(URI.create(metabaseUrl + "/api/user/" + mbUserId + "/password"))
                .header("Content-Type", "application/json")
                .header("X-Metabase-Session", getAdminSession())
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = send(req);
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw httpError("resetar senha do usuário " + mbUserId, resp);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Sessões (admin master e por usuário)
    // ─────────────────────────────────────────────────────────────────

    private synchronized String getAdminSession() {
        if (adminSessionCookie != null && Instant.now().isBefore(adminSessionExpiresAt)) {
            return adminSessionCookie;
        }
        String cookie = login(adminEmail, adminPassword);
        adminSessionCookie = cookie;
        // Metabase mantém sessão por 14 dias por default; renovamos a cada 1h por segurança.
        adminSessionExpiresAt = Instant.now().plus(Duration.ofHours(1));
        return cookie;
    }

    private String loginAsUser(String email, String password) {
        return login(email, password);
    }

    private String login(String email, String password) {
        String body = jsonOf(Map.of("username", email, "password", password));
        HttpRequest req = HttpRequest.newBuilder(URI.create(metabaseUrl + "/api/session"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = send(req);
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw httpError("login no Metabase como " + email, resp);
        }
        String cookie = readStringField(resp.body(), "id");
        if (cookie == null || cookie.isBlank()) {
            throw new ServiceValidationException(
                    "Metabase não retornou cookie de sessão.", HttpStatus.BAD_GATEWAY);
        }
        return cookie;
    }

    // ─────────────────────────────────────────────────────────────────
    // Cripto (AES-GCM) e utilidades
    // ─────────────────────────────────────────────────────────────────

    private String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            RNG.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, keySpec(), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao cifrar senha do Metabase", e);
        }
    }

    private String decrypt(String b64) {
        try {
            byte[] all = Base64.getDecoder().decode(b64);
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(all, 0, iv, 0, GCM_IV_LENGTH);
            byte[] ct = new byte[all.length - GCM_IV_LENGTH];
            System.arraycopy(all, GCM_IV_LENGTH, ct, 0, ct.length);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, keySpec(), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao decifrar senha do Metabase", e);
        }
    }

    private SecretKeySpec keySpec() {
        // Deriva 32 bytes (AES-256) a partir da chave informada. Se a chave já tem 32 bytes,
        // usa direto; senão preenche com zeros ou trunca.
        byte[] key = new byte[32];
        byte[] src = encryptKey.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(src, 0, key, 0, Math.min(src.length, 32));
        return new SecretKeySpec(key, "AES");
    }

    private static String generateRandomPassword(int len) {
        // Senha alfanumérica (compatível com regras default do Metabase).
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(chars.charAt(RNG.nextInt(chars.length())));
        // Garante pelo menos 1 minúscula, 1 maiúscula, 1 dígito (Metabase exige).
        sb.setCharAt(0, 'A');
        sb.setCharAt(1, 'a');
        sb.setCharAt(2, '0');
        return sb.toString();
    }

    private static String firstName(String nomeCompleto) {
        if (nomeCompleto == null || nomeCompleto.isBlank()) return "Usuário";
        String[] parts = nomeCompleto.trim().split("\\s+");
        return parts[0];
    }

    private static String lastName(String nomeCompleto) {
        if (nomeCompleto == null || nomeCompleto.isBlank()) return "NUSP";
        String[] parts = nomeCompleto.trim().split("\\s+");
        return parts.length > 1 ? parts[parts.length - 1] : "NUSP";
    }

    // ─────────────────────────────────────────────────────────────────
    // HTTP helpers
    // ─────────────────────────────────────────────────────────────────

    private static HttpResponse<String> send(HttpRequest req) {
        try {
            return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new ServiceValidationException(
                    "Falha ao chamar o Metabase: " + e.getMessage(),
                    HttpStatus.BAD_GATEWAY);
        }
    }

    private static String jsonOf(Map<String, ?> map) {
        try {
            return JSON.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Integer readIntField(String body, String field) {
        try {
            JsonNode n = JSON.readTree(body).path(field);
            return n.isNumber() ? n.intValue() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String readStringField(String body, String field) {
        try {
            return JSON.readTree(body).path(field).asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static ServiceValidationException httpError(String acao, HttpResponse<String> resp) {
        String preview = resp.body() == null ? "(vazio)" :
                resp.body().substring(0, Math.min(200, resp.body().length()));
        return new ServiceValidationException(
                "Erro ao " + acao + " (HTTP " + resp.statusCode() + "): " + preview,
                HttpStatus.BAD_GATEWAY);
    }
}
