package com.wiz.persistence;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class SampleRepositories {

    private SampleRepositories() {
    }

    static Map<String, RepositoryAdapter> adapters() {
        LinkedHashMap<String, RepositoryAdapter> adapters = new LinkedHashMap<>();
        add(adapters, schema("base", "user", Map.ofEntries(
                Map.entry("id", "TEXT PRIMARY KEY"),
                Map.entry("email", "TEXT NOT NULL UNIQUE"),
                Map.entry("password", "TEXT NOT NULL"),
                Map.entry("name", "TEXT NOT NULL"),
                Map.entry("mobile", "TEXT NOT NULL DEFAULT ''"),
                Map.entry("role", "TEXT NOT NULL DEFAULT 'user'"),
                Map.entry("created", "TEXT NOT NULL"),
                Map.entry("updated", "TEXT NOT NULL")), Set.of("password")));
        add(adapters, schema("post", "post", Map.ofEntries(
                Map.entry("id", "TEXT PRIMARY KEY"),
                Map.entry("title", "TEXT NOT NULL"),
                Map.entry("content", "TEXT NOT NULL DEFAULT ''"),
                Map.entry("category", "TEXT NOT NULL DEFAULT ''"),
                Map.entry("author_id", "TEXT NOT NULL DEFAULT ''"),
                Map.entry("author_name", "TEXT NOT NULL DEFAULT ''"),
                Map.entry("status", "TEXT NOT NULL DEFAULT 'draft'"),
                Map.entry("created", "TEXT NOT NULL"),
                Map.entry("updated", "TEXT NOT NULL")), Set.of()));
        add(adapters, schema("post", "comment", Map.ofEntries(
                Map.entry("id", "TEXT PRIMARY KEY"),
                Map.entry("post_id", "TEXT NOT NULL"),
                Map.entry("author_id", "TEXT NOT NULL DEFAULT ''"),
                Map.entry("author_name", "TEXT NOT NULL DEFAULT ''"),
                Map.entry("content", "TEXT NOT NULL DEFAULT ''"),
                Map.entry("created", "TEXT NOT NULL")), Set.of()));
        return Map.copyOf(adapters);
    }

    private static void add(Map<String, RepositoryAdapter> adapters, TableSchema schema) {
        adapters.put(schema.tableName(), () -> schema);
    }

    private static TableSchema schema(String namespace, String tableName, Map<String, String> columns, Set<String> privateColumns) {
        LinkedHashMap<String, String> orderedColumns = new LinkedHashMap<>();
        for (String key : java.util.List.of("id", "email", "password", "name", "mobile", "role", "title", "content", "category", "author_id", "author_name", "status", "post_id", "created", "updated")) {
            if (columns.containsKey(key)) {
                orderedColumns.put(key, columns.get(key));
            }
        }
        return new TableSchema(namespace, tableName, "id", orderedColumns, privateColumns);
    }
}