package br.leg.senado.nusp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** FRM_AVISO_SALA_CIENCIA — registro de visualização/ciência de um aviso pelo operador. */
@Entity
@Table(name = "FRM_AVISO_SALA_CIENCIA")
@Getter @Setter
public class AvisoSalaCiencia extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "AVISO_ID", nullable = false)
    private String avisoId;

    @Column(name = "OPERADOR_ID", nullable = false)
    private String operadorId;

    @Column(name = "VISUALIZADO_EM", nullable = false)
    private LocalDateTime visualizadoEm;

    @Column(name = "CIENTE_EM")
    private LocalDateTime cienteEm;
}
