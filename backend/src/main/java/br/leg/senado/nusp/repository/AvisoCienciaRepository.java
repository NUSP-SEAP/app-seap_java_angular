package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.AvisoCiencia;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AvisoCienciaRepository extends JpaRepository<AvisoCiencia, String> {

    Optional<AvisoCiencia> findByCadastroIdAndSalaIdAndOperadorId(String cadastroId, Integer salaId, String operadorId);

    Optional<AvisoCiencia> findByCadastroIdAndSalaIdAndTecnicoId(String cadastroId, Integer salaId, String tecnicoId);

    List<AvisoCiencia> findByCadastroIdOrderByCienteEm(String cadastroId);
}
