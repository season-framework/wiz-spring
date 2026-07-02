import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.wiz.runtime.WizContext;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.support.TransactionTemplate;

public final class Jpa {

    private static final ConcurrentHashMap<RuntimeKey, SharedRuntime> RUNTIMES = new ConcurrentHashMap<>();

    private final SharedRuntime runtime;
    private final EntityManager entityManager;

    public Jpa(WizContext wiz) {
        RuntimeKey key = RuntimeKey.from(wiz);
        this.runtime = RUNTIMES.computeIfAbsent(key, ignored -> createRuntime(wiz, key));
        this.entityManager = SharedEntityManagerCreator.createSharedEntityManager(runtime.entityManagerFactory());
    }

    public EntityManager entityManager() {
        return entityManager;
    }

    public TransactionTemplate transaction() {
        return runtime.transactionTemplate();
    }

    private static SharedRuntime createRuntime(WizContext wiz, RuntimeKey key) {
        ClassLoader projectLoader = Jpa.class.getClassLoader();
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.setBeanClassLoader(projectLoader);
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(beanFactory);
        context.setClassLoader(projectLoader);
        context.registerBean(WizContext.class, () -> wiz);
        context.register(JpaConfig.class);

        ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(projectLoader);
        try {
            context.refresh();
        } finally {
            Thread.currentThread().setContextClassLoader(previousLoader);
        }

        SharedRuntime runtime = new SharedRuntime(
                context,
                context.getBean(EntityManagerFactory.class),
                context.getBean(TransactionTemplate.class));
        wiz.runtimeCache().get(wiz.project()).onClose(() -> {
            RUNTIMES.remove(key, runtime);
            runtime.close();
        });
        return runtime;
    }

    private static String value(Object value, String defaultValue) {
        if (value == null || value.toString().isBlank()) {
            return defaultValue;
        }
        return value.toString();
    }

    private static String datasourceUrl(WizContext wiz, Map<String, Object> values) {
        String url = value(values.get("sample.datasource.url"), "jdbc:sqlite:data/app.db");
        if (!url.startsWith("jdbc:sqlite:")) {
            return url;
        }
        String location = url.substring("jdbc:sqlite:".length());
        Path path = Path.of(location);
        if (!path.isAbsolute()) {
            path = wiz.project().root().resolve(location).normalize();
        }
        return "jdbc:sqlite:" + path;
    }

    private static String driverFor(String url) {
        if (url.startsWith("jdbc:postgresql:")) {
            return "org.postgresql.Driver";
        }
        if (url.startsWith("jdbc:mysql:")) {
            return "com.mysql.cj.jdbc.Driver";
        }
        if (url.startsWith("jdbc:mariadb:")) {
            return "org.mariadb.jdbc.Driver";
        }
        return "org.sqlite.JDBC";
    }

    private static String dialectFor(String url) {
        if (url.startsWith("jdbc:postgresql:")) {
            return "org.hibernate.dialect.PostgreSQLDialect";
        }
        if (url.startsWith("jdbc:mysql:")) {
            return "org.hibernate.dialect.MySQLDialect";
        }
        if (url.startsWith("jdbc:mariadb:")) {
            return "org.hibernate.dialect.MariaDBDialect";
        }
        return "org.hibernate.community.dialect.SQLiteDialect";
    }

    private record SharedRuntime(
            AnnotationConfigApplicationContext context,
            EntityManagerFactory entityManagerFactory,
            TransactionTemplate transactionTemplate) implements AutoCloseable {

        @Override
        public void close() {
            context.close();
        }
    }

    private record RuntimeKey(
            String projectRoot,
            String datasourceUrl,
            String driverClassName,
            String username,
            String password,
            String maximumPoolSize,
            String minimumIdle,
            String connectionTimeoutMillis,
            String idleTimeoutMillis,
            String maxLifetimeMillis,
            String sqliteBusyTimeoutMillis,
            String sqliteJournalMode,
            String dialect,
            String hbm2ddlAuto,
            String showSql,
            String formatSql) {

        private static RuntimeKey from(WizContext wiz) {
            Map<String, Object> values = wiz.config().namespace("application").values();
            String url = Jpa.datasourceUrl(wiz, values);
            return new RuntimeKey(
                    wiz.project().root().toAbsolutePath().normalize().toString(),
                    url,
                    value(values.get("sample.datasource.driver-class-name"), driverFor(url)),
                    value(values.get("sample.datasource.username"), ""),
                    value(values.get("sample.datasource.password"), ""),
                    value(values.get("sample.datasource.maximum-pool-size"), ""),
                    value(values.get("sample.datasource.minimum-idle"), ""),
                    value(values.get("sample.datasource.connection-timeout-millis"), ""),
                    value(values.get("sample.datasource.idle-timeout-millis"), ""),
                    value(values.get("sample.datasource.max-lifetime-millis"), ""),
                    value(values.get("sample.datasource.sqlite-busy-timeout-millis"), ""),
                    value(values.get("sample.datasource.sqlite-journal-mode"), ""),
                    value(values.get("sample.jpa.dialect"), dialectFor(url)),
                    value(values.get("sample.jpa.hbm2ddl-auto"), "update"),
                    value(values.get("sample.jpa.show-sql"), "false"),
                    value(values.get("sample.jpa.format-sql"), "false"));
        }
    }
}
