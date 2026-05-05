package com.wiz.persistence;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public record TableSchema(
        String namespace,
        String tableName,
        String idColumn,
        Map<String, String> columns,
        Set<String> privateColumns) {

    public TableSchema {
        validateIdentifier(tableName);
        validateIdentifier(idColumn);
        LinkedHashMap<String, String> orderedColumns = new LinkedHashMap<>(columns);
        orderedColumns.keySet().forEach(TableSchema::validateIdentifier);
        if (!orderedColumns.containsKey(idColumn)) {
            throw new IllegalArgumentException("Schema id column is not defined: " + idColumn);
        }
        columns = Collections.unmodifiableMap(orderedColumns);
        privateColumns = Set.copyOf(privateColumns);
    }

    public boolean hasColumn(String name) {
        return columns.containsKey(name);
    }

    public static void validateIdentifier(String value) {
        if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid SQL identifier: " + value);
        }
    }
}