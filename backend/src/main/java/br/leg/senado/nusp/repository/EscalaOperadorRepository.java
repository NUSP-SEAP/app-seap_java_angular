package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.EscalaOperador;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface EscalaOperadorRepository extends JpaRepository<EscalaOperador, Long> {

    List<EscalaOperador> findByEscalaId(Long escalaId);

    void deleteByEscalaId(Long escalaId);

    List<EscalaOperador> findByEscalaIdAndOperadorId(Long escalaId, String operadorId);

    /** Retorna linhas [operadorId, salaId, count] agregando aparições por (operador, sala) no histórico. */
    @Query("SELECT eo.operadorId, eo.salaId, COUNT(eo) FROM EscalaOperador eo GROUP BY eo.operadorId, eo.salaId")
    List<Object[]> countAparicoesPorOperadorSala();
}
