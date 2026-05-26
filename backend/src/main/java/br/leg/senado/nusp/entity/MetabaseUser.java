package br.leg.senado.nusp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * INT_METABASE_USER — vínculo entre um administrador do NUSP e seu
 * usuário correspondente no Metabase (para SSO caseiro).
 *
 * Senha guardada criptografada (AES-GCM via {@code app.metabase.encrypt-key}),
 * usada exclusivamente pelo backend para autenticar no Metabase em nome
 * do admin. Nunca é a mesma senha de login do NUSP.
 */
@Entity
@Table(name = "INT_METABASE_USER")
@Getter @Setter
public class MetabaseUser extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "ADMIN_ID", nullable = false, unique = true)
    private String adminId;

    @Column(name = "EMAIL", nullable = false, unique = true)
    private String email;

    @Column(name = "SENHA_CIFRADA", nullable = false)
    private String senhaCifrada;

    @Column(name = "METABASE_USER_ID", nullable = false)
    private Integer metabaseUserId;
}
