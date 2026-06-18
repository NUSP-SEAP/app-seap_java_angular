package br.leg.senado.nusp.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class AlvoTipoAvisoConverter implements AttributeConverter<AlvoTipoAviso, String> {

    @Override
    public String convertToDatabaseColumn(AlvoTipoAviso v) {
        return v == null ? null : v.name();
    }

    @Override
    public AlvoTipoAviso convertToEntityAttribute(String dbValue) {
        if (dbValue == null || dbValue.isBlank()) return null;
        return AlvoTipoAviso.valueOf(dbValue.trim().toUpperCase());
    }
}
