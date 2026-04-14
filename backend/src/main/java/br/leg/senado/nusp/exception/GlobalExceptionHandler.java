package br.leg.senado.nusp.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** Equivale ao service_error_response() do Python — converte exceções em JSON. */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ServiceValidationException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(ServiceValidationException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("error", ex.getMessage());
        if (ex.getExtraFields() != null) {
            body.putAll(ex.getExtraFields());
        }
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception ex) {
        log.error("Erro não tratado em {}", ex.getClass().getSimpleName(), ex);
        return ResponseEntity.internalServerError()
                .body(Map.of("ok", false, "error", "Erro interno do servidor"));
    }
}
