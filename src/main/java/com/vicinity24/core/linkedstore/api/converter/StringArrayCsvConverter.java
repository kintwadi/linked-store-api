package com.vicinity24.core.linkedstore.api.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Converter
public class StringArrayCsvConverter implements AttributeConverter<String[], String> {

    @Override
    public String convertToDatabaseColumn(String[] attribute) {
        if (attribute == null || attribute.length == 0) return "";
        List<String> parts = new ArrayList<>(attribute.length);
        for (String item : attribute) {
            if (item == null) {
                parts.add("");
            } else {
                parts.add(item.replace(",", "\\,").replace("\\", "\\\\"));
            }
        }
        return String.join(",", parts);
    }

    @Override
    public String[] convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isEmpty()) return new String[0];
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < dbData.length(); i++) {
            char c = dbData.charAt(i);
            if (c == '\\' && i + 1 < dbData.length()) {
                char next = dbData.charAt(i + 1);
                cur.append(next);
                i++;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }
}
