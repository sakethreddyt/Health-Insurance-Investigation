package com.healthclaim.casedesk.config;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {
    @Override
    public String convertToDatabaseColumn(String attribute) {
        return (attribute == null || attribute.isEmpty()) ? null : CryptoService.get().encrypt(attribute);
    }
    @Override
    public String convertToEntityAttribute(String dbData) {
        return (dbData == null || dbData.isEmpty()) ? null : CryptoService.get().decrypt(dbData);
    }
}
