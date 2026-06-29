package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.PontoLotePagina;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PontoLotePaginaRepository extends JpaRepository<PontoLotePagina, String> {

    List<PontoLotePagina> findByLoteIdOrderByNumeroPagina(String loteId);

    /**
     * Folhas (páginas) de lotes PUBLICADOS pertencentes à pessoa, mais recentes primeiro.
     * Retorna pares [PontoLotePagina, PontoLote].
     */
    @Query("SELECT p, l FROM PontoLotePagina p, PontoLote l " +
           "WHERE l.id = p.loteId AND l.status = 'PUBLICADO' AND p.pessoaId = :pessoaId " +
           "ORDER BY l.dataInicio DESC, l.criadoEm DESC")
    List<Object[]> findFolhasPublicadasByPessoa(@Param("pessoaId") String pessoaId);
}
