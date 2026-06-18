package br.leg.senado.nusp.enums;

/**
 * Papel da pessoa que interage com um aviso. Não é persistido diretamente:
 * o AvisoService o usa para decidir qual coluna preencher/consultar em
 * FRM_AVISO_CIENCIA (OPERADOR_ID ou TECNICO_ID).
 */
public enum PapelPessoa {
    OPERADOR,
    TECNICO
}
