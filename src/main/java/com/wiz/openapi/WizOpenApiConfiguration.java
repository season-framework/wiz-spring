package com.wiz.openapi;

import java.util.List;

import com.wiz.config.WizApiProperties;
import com.wiz.dispatch.RouteRegistry;
import com.wiz.runtime.ProjectRegistry;
import com.wiz.runtime.WizSpringVersion;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.tags.Tag;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerMapping;

@Configuration(proxyBeanMethods = false)
public class WizOpenApiConfiguration {

    static final String REQUEST_ID_HEADER_COMPONENT = "WizRequestId";

    @Bean
    OpenAPI wizOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("WIZ Spring API")
                        .version(WizSpringVersion.current())
                        .description("Runtime endpoints and HTTP APIs exposed by the active WIZ workspace."))
                .tags(List.of(
                        new Tag().name(WizOpenApiCustomizer.APP_API_TAG)
                                .description("Dynamic App API dispatcher endpoints."),
                        new Tag().name(WizOpenApiCustomizer.ROUTE_TAG)
                                .description("HTTP routes declared by the active WIZ workspace."),
                        new Tag().name("Runtime")
                                .description("WIZ Spring runtime diagnostics.")))
                .components(new Components().addHeaders(REQUEST_ID_HEADER_COMPONENT,
                        new Header()
                                .description("Request correlation identifier generated or accepted by WIZ Spring.")
                                .schema(new StringSchema())));
    }

    @Bean
    OpenApiCustomizer wizRouteOpenApiCustomizer(ProjectRegistry projectRegistry,
            RouteRegistry routeRegistry,
            WizApiProperties apiProperties) {
        return new WizOpenApiCustomizer(projectRegistry, routeRegistry, apiProperties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "springdoc.swagger-ui", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    HandlerMapping wizSwaggerUiResourceHandlerMapping(
            @Qualifier("resourceHandlerMapping") HandlerMapping resourceHandlerMapping,
            SwaggerUiConfigProperties swaggerUiProperties) {
        return new SwaggerUiResourceHandlerMapping(resourceHandlerMapping, swaggerUiProperties.getPath());
    }
}
