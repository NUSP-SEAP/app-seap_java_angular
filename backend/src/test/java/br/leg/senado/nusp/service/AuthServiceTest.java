package br.leg.senado.nusp.service;

import br.leg.senado.nusp.repository.AdministradorRepository;
import br.leg.senado.nusp.repository.AuthSessionRepository;
import br.leg.senado.nusp.repository.OperadorRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private OperadorRepository operadorRepository;
    @Mock private AdministradorRepository administradorRepository;
    @Mock private AuthSessionRepository authSessionRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private EntityManager entityManager;

    @InjectMocks
    private AuthService service;

    @Test
    @DisplayName("findUserForLogin — usuário encontrado retorna mapa com dados")
    void findUser_found() {
        Query mockQuery = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);
        when(mockQuery.setParameter(eq("usuario"), anyString())).thenReturn(mockQuery);

        Object[] row = {"operador", "uuid-123", "João da Silva", "joao", "joao@senado.leg.br", "$2a$10$hash"};
        List<Object[]> resultList = new java.util.ArrayList<>();
        resultList.add(row);
        when(mockQuery.getResultList()).thenReturn(resultList);

        Map<String, String> user = service.findUserForLogin("joao");

        assertNotNull(user);
        assertEquals("operador", user.get("perfil"));
        assertEquals("uuid-123", user.get("id"));
        assertEquals("João da Silva", user.get("nome_completo"));
        assertEquals("joao", user.get("username"));
    }

    @Test
    @DisplayName("findUserForLogin — usuário não encontrado retorna null")
    void findUser_notFound() {
        Query mockQuery = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);
        when(mockQuery.setParameter(eq("usuario"), anyString())).thenReturn(mockQuery);
        when(mockQuery.getResultList()).thenReturn(List.of());

        assertNull(service.findUserForLogin("inexistente"));
    }

    @Test
    @DisplayName("verifyPassword — senha correta retorna true")
    void verifyPassword_correct() {
        when(passwordEncoder.matches("Teste@2026", "$2a$10$hash")).thenReturn(true);
        assertTrue(service.verifyPassword("Teste@2026", "$2a$10$hash"));
    }

    @Test
    @DisplayName("verifyPassword — senha incorreta retorna false")
    void verifyPassword_incorrect() {
        when(passwordEncoder.matches("errada", "$2a$10$hash")).thenReturn(false);
        assertFalse(service.verifyPassword("errada", "$2a$10$hash"));
    }

    @Test
    @DisplayName("getFotoUrl — operador retorna foto")
    void getFotoUrl_operador() {
        when(operadorRepository.findFotoUrlById("uuid-1")).thenReturn(Optional.of("/files/operadores/foto.jpg"));
        assertEquals("/files/operadores/foto.jpg", service.getFotoUrl("uuid-1", "operador"));
    }

    @Test
    @DisplayName("getFotoUrl — administrador retorna vazio")
    void getFotoUrl_admin() {
        assertEquals("", service.getFotoUrl("uuid-1", "administrador"));
    }
}
