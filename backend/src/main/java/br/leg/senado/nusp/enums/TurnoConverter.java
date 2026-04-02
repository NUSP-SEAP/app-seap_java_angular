package br.leg.senado.nusp.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class TurnoConverter implements AttributeConverter<Turno, String> {

    @Override
    public String convertToDatabaseColumn(Turno turno) {
        return turno == null ? null : turno.getValor();
    }

    @Override
    public Turno convertToEntityAttribute(String dbValue) {
        if (dbValue == null || dbValue.isBlank()) return null;
        return Turno.fromValor(dbValue);
    }
}
