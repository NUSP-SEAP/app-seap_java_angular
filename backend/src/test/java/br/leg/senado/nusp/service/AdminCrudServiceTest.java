package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.Administrador;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminCrudServiceTest {

    @Mock private OperadorRepository operadorRepo;
    @Mock private AdministradorRepository administradorRepo;
    @Mock private SalaRepository salaRepo;
    @Mock private ComissaoRepository comissaoRepo;
    @Mock private ChecklistItemTipoRepository itemTipoRepo;
    @Mock private ChecklistSalaConfigRepository salaConfigRepo;
    @Mock private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AdminCrudService service;

    private void setMasterUsername(String username) {
        ReflectionTestUtils.setField(service, "masterUsername", username);
    }

    // ══ Criar Operador ══════════════════════════════════════════

    @Nested
    @DisplayName("criarOperador")
    class CriarOperador {

        @Test
        @DisplayName("campos faltando → erro 400 com lista de missing")
        void missingFields_throws400() {
            ServiceValidationException ex = assertThrows(
                    ServiceValidationException.class,
                    () -> service.criarOperador(null, null, null, null, null, null));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("invalid_payload", ex.getMessage());
            assertNotNull(ex.getExtraFields());
            assertTrue(ex.getExtraFields().get("missing").toString().contains("nome_completo"));
        }

        @Test
        @DisplayName("email duplicado → erro 409")
        void duplicateEmail_throws409() {
            when(operadorRepo.findByEmail("joao@senado.leg.br")).thenReturn(Optional.of(new Operador()));
            when(operadorRepo.findByUsername("joao")).thenReturn(Optional.empty());

            ServiceValidationException ex = assertThrows(
                    ServiceValidationException.class,
                    () -> service.criarOperador("João", "Joãozinho", "joao@senado.leg.br", "joao", "123456", null));

            assertEquals(HttpStatus.CONFLICT, ex.getStatus());
            assertTrue(ex.getExtraFields().get("message").toString().contains("E-mail já cadastrado"));
        }

        @Test
        @DisplayName("username duplicado → erro 409")
        void duplicateUsername_throws409() {
            when(operadorRepo.findByEmail("novo@senado.leg.br")).thenReturn(Optional.empty());
            when(operadorRepo.findByUsername("joao")).thenReturn(Optional.of(new Operador()));

            ServiceValidationException ex = assertThrows(
                    ServiceValidationException.class,
                    () -> service.criarOperador("João", "Joãozinho", "novo@senado.leg.br", "joao", "123456", null));

            assertEquals(HttpStatus.CONFLICT, ex.getStatus());
            assertTrue(ex.getExtraFields().get("message").toString().contains("usuário já cadastrado"));
        }

        @Test
        @DisplayName("dados válidos → cria operador e retorna mapa")
        void validData_success() {
            when(operadorRepo.findByEmail("novo@senado.leg.br")).thenReturn(Optional.empty());
            when(operadorRepo.findByUsername("novo")).thenReturn(Optional.empty());
            when(passwordEncoder.encode("Senha@2026")).thenReturn("$2a$10$hash");

            Operador saved = new Operador();
            saved.setId("uuid-new");
            saved.setNomeCompleto("Novo Operador");
            saved.setNomeExibicao("Novo");
            saved.setEmail("novo@senado.leg.br");
            saved.setUsername("novo");
            when(operadorRepo.save(any())).thenReturn(saved);

            Map<String, Object> result = service.criarOperador(
                    "Novo Operador", "Novo", "novo@senado.leg.br", "novo", "Senha@2026", null);

            assertEquals("uuid-new", result.get("id"));
            assertEquals("Novo Operador", result.get("nome_completo"));
            verify(passwordEncoder).encode("Senha@2026");
        }
    }

    // ══ Criar Administrador ═════════════════════════════════════

    @Nested
    @DisplayName("criarAdministrador")
    class CriarAdministrador {

        @Test
        @DisplayName("usuário sem permissão → erro 403")
        void forbidden_throws403() {
            setMasterUsername("douglas.antunes");

            ServiceValidationException ex = assertThrows(
                    ServiceValidationException.class,
                    () -> service.criarAdministrador("Admin", "admin@senado.leg.br", "admin", "123", "outro.usuario"));

            assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        }

        @Test
        @DisplayName("master user com dados válidos → cria admin")
        void masterUser_success() {
            setMasterUsername("douglas.antunes");
            when(administradorRepo.findByEmail("new@senado.leg.br")).thenReturn(Optional.empty());
            when(administradorRepo.findByUsername("newadmin")).thenReturn(Optional.empty());
            when(passwordEncoder.encode("Admin@2026")).thenReturn("$2a$10$hash");

            Administrador saved = new Administrador();
            saved.setId("uuid-admin");
            saved.setNomeCompleto("Novo Admin");
            saved.setEmail("new@senado.leg.br");
            saved.setUsername("newadmin");
            when(administradorRepo.save(any())).thenReturn(saved);

            Map<String, Object> result = service.criarAdministrador(
                    "Novo Admin", "new@senado.leg.br", "newadmin", "Admin@2026", "douglas.antunes");

            assertEquals("uuid-admin", result.get("id"));
            assertEquals("newadmin", result.get("username"));
        }

        @Test
        @DisplayName("campos faltando → erro 400")
        void missingFields_throws400() {
            setMasterUsername("douglas.antunes");

            ServiceValidationException ex = assertThrows(
                    ServiceValidationException.class,
                    () -> service.criarAdministrador("", "", "", "", "douglas.antunes"));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        }
    }

    // ══ Form Edit ═══════════════════════════════════════════════

    @Nested
    @DisplayName("listFormEditItems")
    class ListFormEdit {

        @Test
        @DisplayName("entidade inválida → erro 400")
        void invalidEntity_throws400() {
            ServiceValidationException ex = assertThrows(
                    ServiceValidationException.class,
                    () -> service.listFormEditItems("invalida"));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertTrue(ex.getMessage().contains("ENTIDADE_INVALIDA"));
        }
    }
}
