package br.leg.senado.nusp.enums;

/**
 * Papel da pessoa que interage com um aviso. Não é persistido diretamente:
 * o AvisoService o usa para decidir qual coluna preencher/consultar em
 * FRM_AVISO_CIENCIA (OPERADOR_ID, TECNICO_ID ou ADMIN_ID).
 */
public enum PapelPessoa {
    OPERADOR,
    TECNICO,
    ADMIN;

    /** Converte o role do UserPrincipal ("operador"/"tecnico"/"administrador") no papel; null se desconhecido. */
    public static PapelPessoa fromRole(String role) {
        if (role == null) return null;
        return switch (role.trim().toLowerCase()) {
            case "operador" -> OPERADOR;
            case "tecnico" -> TECNICO;
            case "administrador" -> ADMIN;
            default -> null;
        };
    }
}
