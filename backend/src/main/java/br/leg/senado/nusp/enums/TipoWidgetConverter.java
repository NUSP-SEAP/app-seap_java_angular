package br.leg.senado.nusp.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class TipoWidgetConverter implements AttributeConverter<TipoWidget, String> {

    @Override
    public String convertToDatabaseColumn(TipoWidget tipo) {
        return tipo == null ? null : tipo.getValor();
    }

    @Override
    public TipoWidget convertToEntityAttribute(String dbValue) {
        if (dbValue == null || dbValue.isBlank()) return null;
        return TipoWidget.fromValor(dbValue);
    }
}
