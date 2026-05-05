package com.wiz.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public class OrmModel {

    private final RepositoryAdapter adapter;
    private final TableSchema schema;
    private final java.nio.file.Path databasePath;

    OrmModel(RepositoryAdapter adapter, java.nio.file.Path databasePath) {
        this.adapter = adapter;
        this.schema = adapter.schema();
        this.databasePath = databasePath;
        createTable();
    }

    public java.nio.file.Path databasePath() {
        return databasePath;
    }

    public void createTable() {
        String columns = schema.columns().entrySet().stream()
                .map(entry -> quote(entry.getKey()) + " " + entry.getValue())
                .collect(java.util.stream.Collectors.joining(", "));
        execute("CREATE TABLE IF NOT EXISTS " + quote(schema.tableName()) + " (" + columns + ")", List.of());
    }

    public Map<String, Object> get(String key, Object value) {
        return get(Map.of(key, value));
    }

    public Map<String, Object> get(Map<String, Object> where) {
        List<Map<String, Object>> rows = rows(RowsQuery.builder().where(where).page(1).dump(1).build());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public int count(Map<String, Object> where) {
        return count(RowsQuery.where(where));
    }

    public int count(RowsQuery query) {
        SqlParts parts = whereClause(query);
        String sql = "SELECT COUNT(" + quote(schema.idColumn()) + ") AS cnt FROM " + quote(schema.tableName()) + parts.sql();
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parts.parameters());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt("cnt") : 0;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to count " + schema.tableName(), exception);
        }
    }

    public List<Map<String, Object>> rows(RowsQuery query) {
        SqlParts where = whereClause(query);
        ArrayList<Object> parameters = new ArrayList<>(where.parameters());
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(selectFields(query.fields()))
                .append(" FROM ").append(quote(schema.tableName()))
                .append(where.sql())
                .append(orderBy(query.orderBy(), query.order()));
        if (query.page() != null) {
            int dump = query.dump() == null || query.dump() < 1 ? 10 : query.dump();
            int page = Math.max(1, query.page());
            sql.append(" LIMIT ? OFFSET ?");
            parameters.add(dump);
            parameters.add((page - 1) * dump);
        }
        return select(sql.toString(), parameters);
    }

    public String insert(Map<String, Object> data) {
        LinkedHashMap<String, Object> item = writableData(data);
        if (!item.containsKey(schema.idColumn()) || item.get(schema.idColumn()) == null || item.get(schema.idColumn()).toString().isBlank()) {
            item.put(schema.idColumn(), nextId());
        }
        if (get(schema.idColumn(), item.get(schema.idColumn())) != null) {
            throw new IllegalArgumentException("wizdb Error: Duplicated");
        }
        String columns = item.keySet().stream().map(this::quote).collect(java.util.stream.Collectors.joining(", "));
        String marks = item.keySet().stream().map(ignored -> "?").collect(java.util.stream.Collectors.joining(", "));
        execute("INSERT INTO " + quote(schema.tableName()) + " (" + columns + ") VALUES (" + marks + ")", new ArrayList<>(item.values()));
        return item.get(schema.idColumn()).toString();
    }

    public int update(Map<String, Object> data, Map<String, Object> where) {
        if (count(where) > 20) {
            throw new IllegalArgumentException("wizdb Error: update too many items");
        }
        LinkedHashMap<String, Object> item = writableData(data);
        if (item.isEmpty()) {
            return 0;
        }
        SqlParts whereParts = whereClause(RowsQuery.where(where));
        ArrayList<Object> parameters = new ArrayList<>(item.values());
        parameters.addAll(whereParts.parameters());
        String assignments = item.keySet().stream().map(key -> quote(key) + " = ?").collect(java.util.stream.Collectors.joining(", "));
        return execute("UPDATE " + quote(schema.tableName()) + " SET " + assignments + whereParts.sql(), parameters);
    }

    public int delete(Map<String, Object> where) {
        SqlParts whereParts = whereClause(RowsQuery.where(where));
        return execute("DELETE FROM " + quote(schema.tableName()) + whereParts.sql(), whereParts.parameters());
    }

    public String upsert(Map<String, Object> data, String keys) {
        LinkedHashMap<String, Object> where = new LinkedHashMap<>();
        for (String key : split(keys)) {
            if (!data.containsKey(key)) {
                throw new IllegalArgumentException("Missing upsert key: " + key);
            }
            where.put(key, data.get(key));
        }
        Map<String, Object> existing = get(where);
        if (existing == null) {
            return insert(data);
        }
        update(data, where);
        Object id = existing.get(schema.idColumn());
        return id == null ? null : id.toString();
    }

    public Map<String, Object> toDto(Map<String, Object> row) {
        return adapter.toDto(row);
    }

    public List<Map<String, Object>> toDtos(List<Map<String, Object>> rows) {
        return rows.stream().map(this::toDto).toList();
    }

    private String nextId() {
        String id;
        do {
            id = Long.toString(Instant.now().toEpochMilli() * 1000) + randomLowerAlphaNumeric(16);
        } while (get(schema.idColumn(), id) != null);
        return id;
    }

    private String randomLowerAlphaNumeric(int length) {
        String pool = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder builder = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            builder.append(pool.charAt(ThreadLocalRandom.current().nextInt(pool.length())));
        }
        return builder.toString();
    }

    private LinkedHashMap<String, Object> writableData(Map<String, Object> data) {
        LinkedHashMap<String, Object> item = new LinkedHashMap<>();
        data.forEach((key, value) -> {
            if (schema.hasColumn(key)) {
                item.put(key, value);
            }
        });
        return item;
    }

    private SqlParts whereClause(RowsQuery query) {
        ArrayList<String> clauses = new ArrayList<>();
        ArrayList<Object> parameters = new ArrayList<>();
        Set<String> likeColumns = Arrays.stream((query.like() == null ? "" : query.like()).split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        query.where().forEach((key, value) -> {
            if (!schema.hasColumn(key)) {
                return;
            }
            ArrayList<String> options = new ArrayList<>();
            for (Object item : values(value)) {
                if (item == null) {
                    options.add(quote(key) + " IS NULL");
                } else if (likeColumns.contains(key)) {
                    options.add(quote(key) + " LIKE ?");
                    parameters.add("%" + item + "%");
                } else {
                    options.add(quote(key) + " = ?");
                    parameters.add(item);
                }
            }
            if (!options.isEmpty()) {
                clauses.add("(" + String.join(" OR ", options) + ")");
            }
        });
        return new SqlParts(clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses), parameters);
    }

    private List<Object> values(Object value) {
        if (value instanceof Collection<?> collection && !(value instanceof String)) {
            return new ArrayList<>(collection);
        }
        ArrayList<Object> values = new ArrayList<>();
        values.add(value);
        return values;
    }

    private String selectFields(String fields) {
        List<String> selected = split(fields).stream().filter(schema::hasColumn).map(this::quote).toList();
        if (selected.isEmpty()) {
            return "*";
        }
        return String.join(", ", selected);
    }

    private String orderBy(String orderBy, String order) {
        List<String> selected = split(orderBy).stream().filter(schema::hasColumn).map(this::quote).toList();
        if (selected.isEmpty()) {
            return "";
        }
        String direction = "DESC".equalsIgnoreCase(order) ? " DESC" : " ASC";
        return " ORDER BY " + String.join(direction + ", ", selected) + direction;
    }

    private List<String> split(String values) {
        if (values == null || values.isBlank()) {
            return List.of();
        }
        return Arrays.stream(values.split(",")).map(String::trim).filter(value -> !value.isBlank()).toList();
    }

    private List<Map<String, Object>> select(String sql, List<Object> parameters) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                ArrayList<Map<String, Object>> rows = new ArrayList<>();
                ResultSetMetaData metadata = resultSet.getMetaData();
                while (resultSet.next()) {
                    LinkedHashMap<String, Object> row = new LinkedHashMap<>();
                    for (int index = 1; index <= metadata.getColumnCount(); index++) {
                        row.put(metadata.getColumnLabel(index), resultSet.getObject(index));
                    }
                    rows.add(Collections.unmodifiableMap(row));
                }
                return List.copyOf(rows);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to select " + schema.tableName(), exception);
        }
    }

    private int execute(String sql, List<Object> parameters) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to execute " + schema.tableName() + " SQL", exception);
        }
    }

    private void bind(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            statement.setObject(index + 1, parameters.get(index));
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + databasePath);
    }

    private String quote(String identifier) {
        TableSchema.validateIdentifier(identifier);
        return "\"" + identifier.toLowerCase(Locale.ROOT) + "\"";
    }

    private record SqlParts(String sql, List<Object> parameters) {
    }
}