package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.AuthSession;
import br.leg.senado.nusp.repository.AdministradorRepository;
import br.leg.senado.nusp.repository.AuthSessionRepository;
import br.leg.senado.nusp.repository.OperadorRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final OperadorRepository operadorRepository;
    private final AdministradorRepository administradorRepository;
    private final AuthSessionRepository authSessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final EntityManager entityManager;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * Busca usuário por username ou email em ambas as tabelas (administrador e operador).
     * Equivale ao get_user_for_login() do Python (UNION ALL).
     *
     * @return Map com campos: id, perfil, nome_completo, username, email, password_hash
     *         ou null se não encontrado.
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> findUserForLogin(String usuario) {
        String sql = """
                SELECT 'administrador' AS PERFIL, ID, NOME_COMPLETO, USERNAME, EMAIL, PASSWORD_HASH
                FROM PES_ADMINISTRADOR
                WHERE USERNAME = :usuario OR EMAIL = :usuario
                UNION ALL
                SELECT 'operador' AS PERFIL, ID, NOME_COMPLETO, USERNAME, EMAIL, PASSWORD_HASH
                FROM PES_OPERADOR
                WHERE USERNAME = :usuario OR EMAIL = :usuario
                FETCH FIRST 1 ROWS ONLY
                """;

        List<Object[]> rows = entityManager
                .createNativeQuery(sql)
                .setParameter("usuario", usuario)
                .getResultList();

        if (rows.isEmpty()) return null;

        Object[] row = rows.get(0);
        Map<String, String> user = new HashMap<>();
        user.put("perfil",        String.valueOf(row[0]));
        user.put("id",            String.valueOf(row[1]));
        user.put("nome_completo", String.valueOf(row[2]));
        user.put("username",      String.valueOf(row[3]));
        user.put("email",         String.valueOf(row[4]));
        user.put("password_hash", String.valueOf(row[5]));
        return user;
    }

    /**
     * Verifica a senha com BCrypt.
     * Equivale ao bcrypt.checkpw() do Python.
     */
    public boolean verifyPassword(String rawPassword, String hash) {
        try {
            return passwordEncoder.matches(rawPassword, hash);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Cria sessão em PES_AUTH_SESSION.
     * Equivale ao create_session() do Python.
     * @return sid (id da sessão criada)
     */
    @Transactional
    public Long createSession(String userId) {
        // 128 bits de entropia — equivale ao secrets.token_hex(16) do Python
        byte[] bytes = new byte[16];
        SECURE_RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        String refreshTokenHash = sb.toString();

        AuthSession session = new AuthSession();
        session.setUserId(userId);
        session.setRefreshTokenHash(refreshTokenHash);
        session.setRevoked(false);
        return authSessionRepository.save(session).getId();
    }

    /**
     * Revoga a sessão. Equivale ao revoke_session() do Python.
     * @return número de linhas afetadas (1 se revogou, 0 se já estava revogada)
     */
    @Transactional
    public int revokeSession(Long sid, String userId) {
        return authSessionRepository.revokeSession(sid, userId);
    }

    /**
     * Retorna a foto_url de um operador.
     * Equivale ao get_foto_url_by_id() do Python.
     */
    public String getFotoUrl(String userId, String role) {
        if (!"operador".equals(role)) return "";
        return operadorRepository.findFotoUrlById(userId).orElse("");
    }
}
