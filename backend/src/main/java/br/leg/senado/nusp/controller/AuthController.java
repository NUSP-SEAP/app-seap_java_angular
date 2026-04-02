package br.leg.senado.nusp.controller;

import br.leg.senado.nusp.config.JwtConfig;
import br.leg.senado.nusp.repository.AuthSessionRepository;
import br.leg.senado.nusp.security.JwtTokenProvider;
import br.leg.senado.nusp.security.UserPrincipal;
import br.leg.senado.nusp.service.AuthService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuthSessionRepository authSessionRepository;
    private final JwtConfig jwtConfig;

    @Value("${app.session.touch-max-age-seconds}")
    private int maxAgeSeconds;

    // =========================================================================
    // POST /api/login
    // Equivale ao login_view() do Python
    // =========================================================================
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body,
                                   HttpServletResponse response) {
        String usuario = (body.getOrDefault("usuario", "")).strip();
        String senha   = (body.getOrDefault("senha",   "")).strip();

        if (usuario.isEmpty() || senha.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Credenciais inválidas"));
        }

        Map<String, String> user = authService.findUserForLogin(usuario);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Credenciais inválidas"));
        }

        if (!authService.verifyPassword(senha, user.get("password_hash"))) {
            return ResponseEntity.status(401).body(Map.of("error", "Credenciais inválidas"));
        }

        Long sid = authService.createSession(user.get("id"));
        Map<String, Object> claims = jwtTokenProvider.buildClaims(
                user.get("id"), user.get("perfil"), user.get("username"),
                user.get("nome_completo"), user.get("email"), sid
        );
        String token = jwtTokenProvider.generateToken(claims);

        setAuthCookie(response, token);

        return ResponseEntity.ok(Map.of(
                "token", token,
                "user", Map.of(
                        "id",       user.get("id"),
                        "role",     user.get("perfil"),
                        "username", user.get("username"),
                        "nome",     user.get("nome_completo"),
                        "email",    user.get("email")
                )
        ));
    }

    // =========================================================================
    // GET /api/whoami
    // Equivale ao whoami_view() do Python
    // =========================================================================
    @GetMapping("/whoami")
    public ResponseEntity<?> whoami(@AuthenticationPrincipal UserPrincipal principal) {
        String fotoUrl = authService.getFotoUrl(principal.getId(), principal.getRole());
        return ResponseEntity.ok(Map.of(
                "ok",   true,
                "user", Map.of(
                        "id",       principal.getId(),
                        "username", principal.getUsername(),
                        "name",     principal.getName(),
                        "email",    principal.getEmail(),
                        "foto_url", fotoUrl != null ? fotoUrl : ""
                ),
                "role", principal.getRole(),
                "exp",  principal.getExp()
        ));
    }

    // =========================================================================
    // POST /api/auth/logout
    // Equivale ao logout_view() do Python
    // =========================================================================
    @PostMapping("/auth/logout")
    public ResponseEntity<?> logout(@AuthenticationPrincipal UserPrincipal principal,
                                    HttpServletResponse response) {
        int revoked = authService.revokeSession(principal.getSid(), principal.getId());
        clearAuthCookie(response);
        return ResponseEntity.ok(Map.of("ok", true, "revoked", revoked > 0));
    }

    // =========================================================================
    // POST /api/auth/refresh
    // Equivale ao refresh_view() do Python
    // =========================================================================
    @PostMapping("/auth/refresh")
    public ResponseEntity<?> refresh(@AuthenticationPrincipal UserPrincipal principal,
                                     HttpServletResponse response) {
        Map<String, Object> claims = jwtTokenProvider.buildClaims(
                principal.getId(), principal.getRole(), principal.getUsername(),
                principal.getName(), principal.getEmail(), principal.getSid()
        );
        String token = jwtTokenProvider.generateToken(claims);
        setAuthCookie(response, token);

        return ResponseEntity.ok(Map.of(
                "ok",    true,
                "token", token,
                "exp",   claims.get("exp")
        ));
    }

    // =========================================================================
    // GET /api/auth/html-guard
    // Equivale ao html_guard_view() do Python (lê JWT do cookie, usado pelo Nginx)
    // =========================================================================
    @GetMapping("/auth/html-guard")
    public ResponseEntity<?> htmlGuard(HttpServletRequest request) {
        String token = Arrays.stream(request.getCookies() != null ? request.getCookies() : new Cookie[0])
                .filter(c -> jwtConfig.getCookieName().equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);

        if (token == null) {
            return ResponseEntity.status(401).body(Map.of("ok", false, "error", "not_authenticated"));
        }

        Claims claims;
        try {
            claims = jwtTokenProvider.parseToken(token);
        } catch (JwtException e) {
            return ResponseEntity.status(401).body(Map.of("ok", false, "error", "invalid_token"));
        }

        String sidStr = claims.get("sid", String.class);
        String sub    = claims.get("sub", String.class);
        if (sidStr == null || sub == null) {
            return ResponseEntity.status(401).body(Map.of("ok", false, "error", "invalid_token"));
        }

        Long sid;
        try {
            sid = Long.parseLong(sidStr);
        } catch (NumberFormatException e) {
            return ResponseEntity.status(401).body(Map.of("ok", false, "error", "invalid_token"));
        }

        int touched = authSessionRepository.touchSession(sid, sub, maxAgeSeconds);
        if (touched == 0) {
            return ResponseEntity.status(401).body(Map.of("ok", false, "error", "not_authenticated"));
        }

        return ResponseEntity.ok(Map.of(
                "ok", true,
                "user", Map.of(
                        "id",       sub,
                        "role",     claims.get("perfil", String.class),
                        "username", claims.get("username", String.class),
                        "nome",     claims.get("nome", String.class),
                        "email",    claims.get("email", String.class)
                ),
                "exp", claims.get("exp")
        ));
    }

    // =========================================================================
    // Helpers de cookie
    // =========================================================================
    private void setAuthCookie(HttpServletResponse response, String token) {
        String domain = jwtConfig.getCookieDomain();
        String cookieHeader = String.format(
                "%s=%s; Max-Age=%d; Path=/; HttpOnly; SameSite=Lax%s%s",
                jwtConfig.getCookieName(),
                token,
                jwtConfig.getTtlSeconds(),
                domain.isEmpty() ? "" : "; Domain=" + domain,
                ""  // Secure apenas em produção; configurar via profile se necessário
        );
        response.addHeader("Set-Cookie", cookieHeader);
    }

    private void clearAuthCookie(HttpServletResponse response) {
        String domain = jwtConfig.getCookieDomain();
        String cookieHeader = String.format(
                "%s=; Max-Age=0; Path=/; HttpOnly; SameSite=Lax%s",
                jwtConfig.getCookieName(),
                domain.isEmpty() ? "" : "; Domain=" + domain
        );
        response.addHeader("Set-Cookie", cookieHeader);
    }
}
