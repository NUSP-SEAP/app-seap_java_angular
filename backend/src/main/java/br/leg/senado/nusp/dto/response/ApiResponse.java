package br.leg.senado.nusp.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Wrapper genérico de resposta — equivale ao padrão {"ok": true, "data": ...} do Python.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean ok, T data, String error) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(false, null, message);
    }
}
