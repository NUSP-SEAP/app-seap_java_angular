package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.AvisoCadastro;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AvisoCadastroRepository extends JpaRepository<AvisoCadastro, String> {

    Optional<AvisoCadastro> findByNumero(Long numero);
}
