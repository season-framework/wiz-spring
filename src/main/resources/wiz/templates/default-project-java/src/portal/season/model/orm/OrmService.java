import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.wiz.runtime.ConfigNamespace;
import com.wiz.runtime.ConfigService;
import com.wiz.runtime.ProjectContext;
import com.wiz.runtime.WizContext;

public class OrmService {

    private final ProjectContext project;
    private final ConfigService config;
    private final Map<String, RepositoryAdapter> adapters;

    public OrmService(WizContext wiz) {
        this(wiz.project(), wiz.config(), SampleRepositories.adapters().values());
    }

    public OrmService(ProjectContext project) {
        this(project, new ConfigService(project), SampleRepositories.adapters().values());
    }

    public OrmService(ProjectContext project, ConfigService config) {
        this(project, config, SampleRepositories.adapters().values());
    }

    public OrmService(ProjectContext project, ConfigService config, Iterable<RepositoryAdapter> adapters) {
        this.project = project;
        this.config = config;
        LinkedHashMap<String, RepositoryAdapter> mappedAdapters = new LinkedHashMap<>();
        adapters.forEach(adapter -> mappedAdapters.put(adapter.schema().tableName(), adapter));
        this.adapters = Map.copyOf(mappedAdapters);
    }

    public OrmModel use(String tableName) {
        return use(tableName, defaultNamespace(tableName));
    }

    public OrmModel use(String tableName, String namespace) {
        RepositoryAdapter adapter = Optional.ofNullable(adapters.get(tableName))
                .orElseThrow(() -> new IllegalArgumentException("Unknown repository table: " + tableName));
        return new OrmModel(adapter, databasePath(namespace == null || namespace.isBlank() ? adapter.schema().namespace() : namespace));
    }

    public Path databasePath(String namespace) {
        String configured = configuredDatabasePath(namespace).orElse("data/" + namespace + ".sqlite");
        Path path = Path.of(configured);
        Path root = project.root().toAbsolutePath().normalize();
        Path resolved = path.isAbsolute() ? path.normalize() : root.resolve(path).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Database path must stay inside project root: " + configured);
        }
        try {
            Files.createDirectories(resolved.getParent());
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Failed to create database directory", exception);
        }
        return resolved;
    }

    private Optional<String> configuredDatabasePath(String namespace) {
        ConfigNamespace database = config.namespace("database");
        Optional<String> databasePath = namespacePath(database.values(), namespace);
        if (databasePath.isPresent()) {
            return databasePath;
        }
        ConfigNamespace season = config.namespace("season");
        return namespacePath(season.values(), namespace)
                .or(() -> value(season.values(), "database_" + namespace))
                .or(() -> value(season.values(), "sqlite_" + namespace))
                .or(() -> value(season.values(), "db_" + namespace));
    }

    private Optional<String> namespacePath(Map<String, Object> values, String namespace) {
        return value(values, namespace)
                .or(() -> value(values, namespace + ".path"))
                .or(() -> value(values, namespace + "_path"))
                .or(() -> namespace.equals("base") ? value(values, "default") : Optional.empty())
                .or(() -> namespace.equals("base") ? value(values, "path") : Optional.empty());
    }

    private Optional<String> value(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null || value.toString().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.toString());
    }

    private String defaultNamespace(String tableName) {
        return switch (tableName) {
            case "post", "comment" -> "post";
            default -> "base";
        };
    }
}
