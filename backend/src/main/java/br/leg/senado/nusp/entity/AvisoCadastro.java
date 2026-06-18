package br.leg.senado.nusp.entity;

import br.leg.senado.nusp.enums.StatusAviso;
import br.leg.senado.nusp.enums.TipoAviso;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** FRM_AVISO_CADASTRO — cabeçalho de um cadastro de aviso (1 linha por cadastro do admin). */
@Entity
@Table(name = "FRM_AVISO_CADASTRO")
@Getter @Setter
public class AvisoCadastro extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** Identificador amigável (sequence SEQ_FRM_AVISO_CADASTRO). Atribuído no service. */
    @Column(name = "NUMERO", nullable = false, unique = true)
    private Long numero;

    @Column(name = "TIPO", nullable = false)
    private TipoAviso tipo;

    @Column(name = "PERMANENTE", nullable = false)
    private Boolean permanente = true;

    /** NULL quando permanente. */
    @Column(name = "DURACAO_DIAS")
    private Integer duracaoDias;

    @Column(name = "MANTER_APOS_CIENCIA", nullable = false)
    private Boolean manterAposCiencia = false;

    @Column(name = "STATUS", nullable = false)
    private StatusAviso status = StatusAviso.ATIVO;

    @Column(name = "CRIADO_POR_ID", nullable = false)
    private String criadoPorId;

    /** criado_em + duracao_dias. NULL quando permanente. */
    @Column(name = "EXPIRA_EM")
    private LocalDateTime expiraEm;

    @Column(name = "DESATIVADO_EM")
    private LocalDateTime desativadoEm;
}
