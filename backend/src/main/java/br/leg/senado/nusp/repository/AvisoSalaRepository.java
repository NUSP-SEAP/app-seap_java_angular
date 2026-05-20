package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.AvisoSala;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AvisoSalaRepository extends JpaRepository<AvisoSala, String> {

    /** Aviso ativo (não desativado e não expirado) de uma sala, se existir. */
    @Query("""
        SELECT a FROM AvisoSala a
         WHERE a.salaId = :salaId
           AND a.ativo = true
           AND a.expiraEm > :agora
        """)
    Optional<AvisoSala> findAtivoBySala(@Param("salaId") Integer salaId,
                                       @Param("agora") LocalDateTime agora);

    /** Listagem global (todos os status), ordenada do mais recente para o mais antigo. */
    @Query("SELECT a FROM AvisoSala a ORDER BY a.criadoEm DESC")
    List<AvisoSala> findAllOrderByCriadoEmDesc();
}
