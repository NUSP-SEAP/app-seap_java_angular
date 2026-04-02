package br.leg.senado.nusp.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class TipoEventoConverter implements AttributeConverter<TipoEvento, String> {

    @Override
    public String convertToDatabaseColumn(TipoEvento tipo) {
        return tipo == null ? null : tipo.getValor();
    }

    @Override
    public TipoEvento convertToEntityAttribute(String dbValue) {
        if (dbValue == null || dbValue.isBlank()) return null;
        return TipoEvento.fromValor(dbValue);
    }
}
