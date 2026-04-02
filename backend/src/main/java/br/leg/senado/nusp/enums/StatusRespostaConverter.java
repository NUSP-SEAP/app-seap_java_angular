package br.leg.senado.nusp.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class StatusRespostaConverter implements AttributeConverter<StatusResposta, String> {

    @Override
    public String convertToDatabaseColumn(StatusResposta status) {
        return status == null ? null : status.getValor();
    }

    @Override
    public StatusResposta convertToEntityAttribute(String dbValue) {
        if (dbValue == null || dbValue.isBlank()) return null;
        return StatusResposta.fromValor(dbValue);
    }
}
