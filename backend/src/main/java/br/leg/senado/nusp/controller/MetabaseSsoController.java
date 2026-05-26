package br.leg.senado.nusp.controller;

import br.leg.senado.nusp.entity.Administrador;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.AdministradorRepository;
import br.leg.senado.nusp.security.UserPrincipal;
import br.leg.senado.nusp.service.MetabaseSsoService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * SSO caseiro com o Metabase.
 *
 * Endpoint único: GET /api/admin/metabase/link
 *  - Garante que o admin logado tem usuário no Metabase
 *  - Faz login programático e captura cookie metabase.SESSION
 *  - Devolve via Set-Cookie com Domain=.senado-nusp.cloud para que o
 *    navegador o envie automaticamente quando o usuário abrir
 *    https://bi.senado-nusp.cloud
 *  - Body: { "url": "https://bi.senado-nusp.cloud" }
 *
 * O frontend chama esse endpoint ao clicar em "Análise de Dados" e
 * abre {url} em nova aba.
 */
@RestController
@Slf4j
@RequiredArgsConstructor
@Tag(name = "Metabase SSO", description = "SSO caseiro com a instância única do Metabase (BI)")
public class MetabaseSsoController {

    private final MetabaseSsoService metabaseSsoService;
    private final AdministradorRepository administradorRepository;

    @Value("${app.metabase.cookie-domain:.senado-nusp.cloud}")
    private String cookieDomain;

    @GetMapping("/api/admin/metabase/link")
    public ResponseEntity<?> getLink(@AuthenticationPrincipal UserPrincipal user,
                                     HttpServletResponse response) {
        if (user == null || !"administrador".equals(user.getRole())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Acesso restrito a administradores."));
        }
        if (!metabaseSsoService.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Integração com Metabase indisponível."));
        }
        Administrador admin = administradorRepository.findById(user.getId())
                .orElseThrow(() -> new ServiceValidationException(
                        "Administrador não encontrado.", HttpStatus.NOT_FOUND));

        String session = metabaseSsoService.getLoginCookieFor(admin);
        // metabase.SESSION é HttpOnly + Secure + Lax + Domain=.senado-nusp.cloud
        // para ser enviado nos requests para bi.senado-nusp.cloud.
        ResponseCookie cookie = ResponseCookie.from("metabase.SESSION", session)
                .domain(cookieDomain)
                .path("/")
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .maxAge(60L * 60 * 24 * 14) // 14 dias (default Metabase)
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
        return ResponseEntity.ok(Map.of(
                "url", metabaseSsoService.getMetabaseUrl()
        ));
    }
}
