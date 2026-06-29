package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.PontoLote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PontoLoteRepository extends JpaRepository<PontoLote, String> {

    /** Lotes mais recentes primeiro (lista do admin). */
    List<PontoLote> findAllByOrderByCriadoEmDesc();
}
