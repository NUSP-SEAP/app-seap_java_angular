package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.AvisoSalaCiencia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AvisoSalaCienciaRepository extends JpaRepository<AvisoSalaCiencia, String> {

    Optional<AvisoSalaCiencia> findByAvisoIdAndOperadorId(String avisoId, String operadorId);

    /** Cientes confirmados (ciente_em != null), ordenados pela data de ciência. */
    @Query("""
        SELECT c FROM AvisoSalaCiencia c
         WHERE c.avisoId = :avisoId AND c.cienteEm IS NOT NULL
         ORDER BY c.cienteEm
        """)
    List<AvisoSalaCiencia> findCientesByAvisoId(@Param("avisoId") String avisoId);
}
