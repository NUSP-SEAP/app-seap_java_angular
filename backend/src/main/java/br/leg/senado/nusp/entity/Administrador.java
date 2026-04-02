package br.leg.senado.nusp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** PES_ADMINISTRADOR — era pessoa.administrador */
@Entity
@Table(name = "PES_ADMINISTRADOR")
@Getter @Setter
public class Administrador extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "NOME_COMPLETO", nullable = false)
    private String nomeCompleto;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "PASSWORD_HASH", nullable = false)
    private String passwordHash;

    /** Substitui extensão citext — armazena sempre em lowercase */
    public void setEmail(String email) {
        this.email = email != null ? email.toLowerCase() : null;
    }

    /** Substitui extensão citext — armazena sempre em lowercase */
    public void setUsername(String username) {
        this.username = username != null ? username.toLowerCase() : null;
    }
}
