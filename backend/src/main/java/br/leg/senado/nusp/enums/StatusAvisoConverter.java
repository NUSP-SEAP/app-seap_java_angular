package br.leg.senado.nusp.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class StatusAvisoConverter implements AttributeConverter<StatusAviso, String> {

    @Override
    public String convertToDatabaseColumn(StatusAviso status) {
        return status == null ? null : status.getValor();
    }

    @Override
    public StatusAviso convertToEntityAttribute(String dbValue) {
        if (dbValue == null || dbValue.isBlank()) return null;
        return StatusAviso.fromValor(dbValue);
    }
}
