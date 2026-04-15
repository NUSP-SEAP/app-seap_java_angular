package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.EscalaOperador;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EscalaOperadorRepository extends JpaRepository<EscalaOperador, Long> {

    List<EscalaOperador> findByEscalaId(Long escalaId);

    void deleteByEscalaId(Long escalaId);

    List<EscalaOperador> findByEscalaIdAndOperadorId(Long escalaId, String operadorId);
}
