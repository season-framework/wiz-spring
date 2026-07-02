import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import javax.sql.DataSource;

import com.wiz.runtime.WizContext;

import jakarta.persistence.EntityManagerFactory;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@EnableTransactionManagement
public class JpaConfig {

    @Bean(destroyMethod = "close")
    public DataSource dataSource(WizContext wiz) {
        Map<String, Object> values = applicationValues(wiz);
        String url = datasourceUrl(wiz, values);
        int maximumPoolSize = positiveInt(values.get("sample.datasource.maximum-pool-size"), defaultMaximumPoolSize(url));
        HikariConfig config = new HikariConfig();
        config.setPoolName("wiz-" + safePoolName(wiz.project().name()) + "-sample");
        config.setDriverClassName(value(values.get("sample.datasource.driver-class-name"), driverFor(url)));
        config.setJdbcUrl(url);
        config.setMaximumPoolSize(maximumPoolSize);
        config.setMinimumIdle(Math.min(maximumPoolSize, positiveInt(values.get("sample.datasource.minimum-idle"), defaultMinimumIdle(url, maximumPoolSize))));
        config.setConnectionTimeout(positiveLong(values.get("sample.datasource.connection-timeout-millis"), 30_000));
        config.setIdleTimeout(positiveLong(values.get("sample.datasource.idle-timeout-millis"), 600_000));
        config.setMaxLifetime(positiveLong(values.get("sample.datasource.max-lifetime-millis"), 1_800_000));
        optional(values.get("sample.datasource.username"), config::setUsername);
        optional(values.get("sample.datasource.password"), config::setPassword);
        if (isSqlite(url)) {
            config.addDataSourceProperty("busy_timeout", String.valueOf(positiveLong(values.get("sample.datasource.sqlite-busy-timeout-millis"), 5_000)));
            config.addDataSourceProperty("journal_mode", value(values.get("sample.datasource.sqlite-journal-mode"), "WAL"));
        }
        return new HikariDataSource(config);
    }

    @Bean
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource, WizContext wiz) {
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan(projectPackageRoot());
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaProperties(jpaProperties(wiz));
        return factory;
    }

    @Bean
    public PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    private Properties jpaProperties(WizContext wiz) {
        Map<String, Object> values = applicationValues(wiz);
        String url = value(values.get("sample.datasource.url"), "jdbc:sqlite:data/app.db");
        Properties properties = new Properties();
        properties.setProperty("hibernate.dialect", value(values.get("sample.jpa.dialect"), dialectFor(url)));
        properties.setProperty("hibernate.hbm2ddl.auto", value(values.get("sample.jpa.hbm2ddl-auto"), "update"));
        properties.setProperty("hibernate.show_sql", value(values.get("sample.jpa.show-sql"), "false"));
        properties.setProperty("hibernate.format_sql", value(values.get("sample.jpa.format-sql"), "false"));
        return properties;
    }

    private String datasourceUrl(WizContext wiz, Map<String, Object> values) {
        String url = value(values.get("sample.datasource.url"), "jdbc:sqlite:data/app.db");
        if (!url.startsWith("jdbc:sqlite:")) {
            return url;
        }
        String location = url.substring("jdbc:sqlite:".length());
        Path path = Path.of(location);
        if (!path.isAbsolute()) {
            path = wiz.project().root().resolve(location).normalize();
        }
        Path parent = path.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (java.io.IOException exception) {
                throw new IllegalStateException("Failed to create sample database directory", exception);
            }
        }
        return "jdbc:sqlite:" + path;
    }

    private Map<String, Object> applicationValues(WizContext wiz) {
        return wiz.config().namespace("application").values();
    }

    private String driverFor(String url) {
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

    private String dialectFor(String url) {
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

    private boolean isSqlite(String url) {
        return url.startsWith("jdbc:sqlite:");
    }

    private int defaultMaximumPoolSize(String url) {
        return isSqlite(url) ? 1 : 10;
    }

    private int defaultMinimumIdle(String url, int maximumPoolSize) {
        return isSqlite(url) ? 1 : Math.min(maximumPoolSize, 2);
    }

    private String projectPackageRoot() {
        String packageName = getClass().getPackageName();
        int marker = packageName.indexOf(".portal.season.");
        return marker > 0 ? packageName.substring(0, marker) : packageName;
    }

    private String value(Object value, String defaultValue) {
        if (value == null || value.toString().isBlank()) {
            return defaultValue;
        }
        return value.toString();
    }

    private int positiveInt(Object value, int defaultValue) {
        if (value == null || value.toString().isBlank()) {
            return defaultValue;
        }
        try {
            return Math.max(1, Integer.parseInt(value.toString()));
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    private long positiveLong(Object value, long defaultValue) {
        if (value == null || value.toString().isBlank()) {
            return defaultValue;
        }
        try {
            return Math.max(1, Long.parseLong(value.toString()));
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    private String safePoolName(String value) {
        return value(value, "project").replaceAll("[^A-Za-z0-9_.-]", "-");
    }

    private void optional(Object value, java.util.function.Consumer<String> setter) {
        if (value != null && !value.toString().isBlank()) {
            setter.accept(value.toString());
        }
    }
}
