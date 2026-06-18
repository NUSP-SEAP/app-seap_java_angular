package br.leg.senado.nusp.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class TipoAvisoConverter implements AttributeConverter<TipoAviso, String> {

    @Override
    public String convertToDatabaseColumn(TipoAviso v) {
        return v == null ? null : v.name();
    }

    @Override
    public TipoAviso convertToEntityAttribute(String dbValue) {
        if (dbValue == null || dbValue.isBlank()) return null;
        return TipoAviso.valueOf(dbValue.trim().toUpperCase());
    }
}
