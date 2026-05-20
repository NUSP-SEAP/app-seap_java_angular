package br.leg.senado.nusp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** FRM_AVISO_SALA — aviso exibido ao operador antes da Verificação de Plenários. */
@Entity
@Table(name = "FRM_AVISO_SALA")
@Getter @Setter
public class AvisoSala extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** Identificador amigável (sequence SEQ_FRM_AVISO_SALA). Atribuído no service. */
    @Column(name = "NUMERO", nullable = false, unique = true)
    private Long numero;

    @Column(name = "SALA_ID", nullable = false)
    private Integer salaId;

    @Column(name = "MENSAGEM", nullable = false, columnDefinition = "CLOB")
    private String mensagem;

    @Column(name = "DURACAO_DIAS", nullable = false)
    private Integer duracaoDias;

    @Column(name = "EXPIRA_EM", nullable = false)
    private LocalDateTime expiraEm;

    @Column(name = "CRIADO_POR")
    private String criadoPor;

    @Column(name = "ATIVO", nullable = false)
    private Boolean ativo = true;
}
