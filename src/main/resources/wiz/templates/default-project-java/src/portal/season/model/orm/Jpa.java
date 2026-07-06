import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import javax.sql.DataSource;

import com.wiz.runtime.ProjectResourceHealth;
import com.wiz.runtime.WizContext;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import com.zaxxer.hikari.HikariDataSource;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
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
        return runtime.observedTransactionTemplate();
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
                context.getBean(DataSource.class),
                context.getBean(EntityManagerFactory.class),
                context.getBean(TransactionTemplate.class),
                new ObservedTransactionTemplate(wiz, context.getBean(TransactionTemplate.class)));
        runtime.setObservation(registerObservability(wiz, runtime));
        wiz.projectRuntime().onClose(() -> {
            RUNTIMES.remove(key, runtime);
            runtime.close();
        });
        return runtime;
    }

    private static AutoCloseable registerObservability(WizContext wiz, SharedRuntime runtime) {
        List<AutoCloseable> registrations = new ArrayList<>();
        registrations.add(wiz.observability().registerHealth(wiz.workspace(), "sample.jpa", () -> health(runtime)));
        if (runtime.dataSource() instanceof HikariDataSource hikari) {
            registrations.add(wiz.observability().registerGauge(wiz.workspace(), "sample.jpa", "pool.active", () -> hikari.getHikariPoolMXBean() == null ? 0 : hikari.getHikariPoolMXBean().getActiveConnections()));
            registrations.add(wiz.observability().registerGauge(wiz.workspace(), "sample.jpa", "pool.idle", () -> hikari.getHikariPoolMXBean() == null ? 0 : hikari.getHikariPoolMXBean().getIdleConnections()));
            registrations.add(wiz.observability().registerGauge(wiz.workspace(), "sample.jpa", "pool.total", () -> hikari.getHikariPoolMXBean() == null ? 0 : hikari.getHikariPoolMXBean().getTotalConnections()));
            registrations.add(wiz.observability().registerGauge(wiz.workspace(), "sample.jpa", "pool.pending", () -> hikari.getHikariPoolMXBean() == null ? 0 : hikari.getHikariPoolMXBean().getThreadsAwaitingConnection()));
        }
        return () -> {
            RuntimeException failure = null;
            for (AutoCloseable registration : registrations.reversed()) {
                try {
                    registration.close();
                } catch (Exception exception) {
                    if (failure == null) {
                        failure = new IllegalStateException("Failed to close project observability registration", exception);
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        };
    }

    private static ProjectResourceHealth health(SharedRuntime runtime) {
        if (!runtime.context().isActive()) {
            return ProjectResourceHealth.down("JPA application context is not active");
        }
        if (!runtime.entityManagerFactory().isOpen()) {
            return ProjectResourceHealth.down("EntityManagerFactory is closed");
        }
        LinkedHashMap<String, Object> details = new LinkedHashMap<>();
        details.put("entityManagerFactory", "open");
        if (runtime.dataSource() instanceof HikariDataSource hikari) {
            details.put("pool", hikari.getPoolName());
            details.put("poolClosed", hikari.isClosed());
            if (hikari.isClosed()) {
                return ProjectResourceHealth.down("Hikari pool is closed");
            }
        } else {
            details.put("dataSource", runtime.dataSource().getClass().getName());
        }
        return ProjectResourceHealth.up(details);
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
            path = wiz.workspace().root().resolve(location).normalize();
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

    private static final class ObservedTransactionTemplate extends TransactionTemplate {

        private final WizContext wiz;

        private ObservedTransactionTemplate(WizContext wiz, TransactionTemplate delegate) {
            super(delegate.getTransactionManager(), delegate);
            this.wiz = wiz;
        }

        @Override
        public <T> T execute(TransactionCallback<T> action) throws TransactionException {
            long startedAt = System.nanoTime();
            boolean success = false;
            try {
                T result = super.execute(action);
                success = true;
                return result;
            } finally {
                wiz.observability().recordDuration(wiz.workspace(), "sample.jpa", "transaction", Duration.ofNanos(System.nanoTime() - startedAt), success);
            }
        }

        @Override
        public void executeWithoutResult(Consumer<TransactionStatus> action) throws TransactionException {
            execute(status -> {
                action.accept(status);
                return null;
            });
        }
    }

    private static final class SharedRuntime implements AutoCloseable {

        private final AnnotationConfigApplicationContext context;
        private final DataSource dataSource;
        private final EntityManagerFactory entityManagerFactory;
        private final TransactionTemplate transactionTemplate;
        private final TransactionTemplate observedTransactionTemplate;
        private AutoCloseable observation;

        private SharedRuntime(
                AnnotationConfigApplicationContext context,
                DataSource dataSource,
                EntityManagerFactory entityManagerFactory,
                TransactionTemplate transactionTemplate,
                TransactionTemplate observedTransactionTemplate) {
            this.context = context;
            this.dataSource = dataSource;
            this.entityManagerFactory = entityManagerFactory;
            this.transactionTemplate = transactionTemplate;
            this.observedTransactionTemplate = observedTransactionTemplate;
        }

        AnnotationConfigApplicationContext context() {
            return context;
        }

        DataSource dataSource() {
            return dataSource;
        }

        EntityManagerFactory entityManagerFactory() {
            return entityManagerFactory;
        }

        TransactionTemplate transactionTemplate() {
            return transactionTemplate;
        }

        TransactionTemplate observedTransactionTemplate() {
            return observedTransactionTemplate;
        }

        void setObservation(AutoCloseable observation) {
            this.observation = observation;
        }

        @Override
        public void close() {
            if (observation != null) {
                try {
                    observation.close();
                } catch (Exception exception) {
                    throw new IllegalStateException("Failed to close JPA observability", exception);
                }
            }
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
                    wiz.workspace().root().toAbsolutePath().normalize().toString(),
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
