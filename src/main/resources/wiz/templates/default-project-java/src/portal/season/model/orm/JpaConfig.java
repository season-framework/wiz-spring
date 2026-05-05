import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import javax.sql.DataSource;

import com.wiz.runtime.WizContext;

import jakarta.persistence.EntityManagerFactory;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@EnableTransactionManagement
public class JpaConfig {

    @Bean
    public DataSource dataSource(WizContext wiz) {
        Map<String, Object> values = applicationValues(wiz);
        String url = datasourceUrl(wiz, values);
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(value(values.get("sample.datasource.driver-class-name"), driverFor(url)));
        dataSource.setUrl(url);
        optional(values.get("sample.datasource.username"), dataSource::setUsername);
        optional(values.get("sample.datasource.password"), dataSource::setPassword);
        return dataSource;
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

    private void optional(Object value, java.util.function.Consumer<String> setter) {
        if (value != null && !value.toString().isBlank()) {
            setter.accept(value.toString());
        }
    }
}
