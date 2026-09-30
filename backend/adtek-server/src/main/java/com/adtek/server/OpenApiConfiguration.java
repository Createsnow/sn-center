package com.adtek.server;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MapSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 在装配层补齐统一信封与 Security 过滤器错误，避免文档只描述 Controller 的 200。 */
@Configuration
public class OpenApiConfiguration {
    @Bean
    OpenAPI apiDefinition() {
        return new OpenAPI().info(new Info().title("Adtek Platform SN").version("0.1.0"))
                .components(new Components().addSecuritySchemes("bearer", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }

    @Bean
    OpenApiCustomizer resultContract() {
        return api -> {
            var fields = List.of("code", "message", "data", "params", "traceId");
            api.getComponents().getSchemas().forEach((name, schema) -> {
                if (name.startsWith("Result")) { schema.setRequired(fields); }
                if (name.startsWith("CursorResult")) {
                    schema.setRequired(List.of("items", "nextCursor"));
                    ((Schema<?>) schema.getProperties().get("nextCursor")).setTypes(Set.of("string", "null"));
                }
            });
            var error = new ObjectSchema();
            error.setRequired(fields);
            error.addProperty("code", new IntegerSchema().format("int32"));
            error.addProperty("message", new StringSchema());
            var nullData = new Schema<>();
            nullData.setTypes(Set.of("null"));
            error.addProperty("data", nullData);
            error.addProperty("params", new MapSchema().additionalProperties(new StringSchema()));
            error.addProperty("traceId", new StringSchema());
            api.getComponents().addSchemas("ErrorResult", error);
            api.getPaths().forEach((path, item) -> item.readOperations().forEach(operation -> {
                for (String status : List.of("400", "401", "403", "404", "409", "429", "500", "502", "503")) {
                    operation.getResponses().addApiResponse(status, new ApiResponse().description("错误码与 params，参见接口契约")
                            .content(new Content().addMediaType("application/json", new io.swagger.v3.oas.models.media.MediaType()
                                    .schema(new Schema<>().$ref("#/components/schemas/ErrorResult")))));
                }
                if (!Set.of("/api/v1/system/auth/csrf", "/api/v1/system/auth/login", "/api/v1/system/auth/refresh", "/api/v1/integrations/auth/token").contains(path)) {
                    operation.addSecurityItem(new io.swagger.v3.oas.models.security.SecurityRequirement().addList("bearer"));
                }
            }));
        };
    }
}
