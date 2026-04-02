package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.Administrador;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdministradorRepository extends JpaRepository<Administrador, String> {

    Optional<Administrador> findByUsername(String username);
    Optional<Administrador> findByEmail(String email);
}
