package __WIZ_PACKAGE_ROOT__.api;

import java.util.List;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springdoc.core.customizers.OperationCustomizer;

@Configuration
@EnableConfigurationProperties(ApiProperties.class)
public class ApiWebConfiguration implements WebMvcConfigurer {

    private final ApiProperties properties;

    public ApiWebConfiguration(ApiProperties properties) {
        this.properties = properties;
    }

    @Bean
    OperationCustomizer apiVersionOperationCustomizer() {
        return (operation, handlerMethod) -> {
            if (!properties.versioning().pathEnabled()
                    || !AnnotatedElementUtils.hasAnnotation(handlerMethod.getBeanType(), ApiController.class)) {
                return operation;
            }
            ensureVersionPathParameter(operation);
            return operation;
        };
    }

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(
                properties.mappingPrefix(),
                HandlerTypePredicate.forAnnotation(ApiController.class));
    }

    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
        ApiProperties.Versioning versioning = properties.versioning();
        if (!versioning.pathEnabled()) {
            return;
        }
        configurer.usePathSegment(
                properties.versionPathSegmentIndex(),
                path -> properties.matchesApiPath(path.pathWithinApplication().value()));
        if (versioning.defaultVersion() != null) {
            configurer.setDefaultVersion(versioning.defaultVersion());
        }
        if (!versioning.supportedVersions().isEmpty()) {
            configurer.addSupportedVersions(versioning.supportedVersions().toArray(String[]::new));
        }
    }

    private void ensureVersionPathParameter(Operation operation) {
        Parameter parameter = operation.getParameters() == null ? null : operation.getParameters().stream()
                .filter(candidate -> "version".equals(candidate.getName()) && "path".equals(candidate.getIn()))
                .findFirst()
                .orElse(null);
        if (parameter == null) {
            parameter = new Parameter().name("version").in("path");
            operation.addParametersItem(parameter);
        }

        parameter.setRequired(true);
        if (parameter.getDescription() == null) {
            parameter.setDescription("API version");
        }
        if (parameter.getSchema() == null) {
            StringSchema schema = new StringSchema();
            schema.setDefault(properties.versioning().defaultVersion());
            List<String> supportedVersions = properties.versioning().supportedVersions();
            if (!supportedVersions.isEmpty()) {
                schema.setEnum(supportedVersions);
            }
            parameter.setSchema(schema);
        }
    }
}
