package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.MetabaseUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MetabaseUserRepository extends JpaRepository<MetabaseUser, String> {

    Optional<MetabaseUser> findByAdminId(String adminId);

    Optional<MetabaseUser> findByEmail(String email);
}
