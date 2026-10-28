package com.hrms.identity;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
  @Bean
  OpenAPI apiDescription() {
    return new OpenAPI()
        .info(
            new Info()
                .title("HRMS REST API")
                .version("1.0.0")
                .description("Hotel reservation and operations; CNY amounts and UTC timestamps."))
        .schemaRequirement(
            "bearerAuth",
            new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT"));
  }

  @Bean
  OpenApiCustomizer operationSecurity() {
    return api ->
        api.getPaths()
            .forEach(
                (path, item) ->
                    item.readOperationsMap()
                        .forEach(
                            (method, operation) -> {
                              boolean publicApi =
                                  path.startsWith("/api/auth/")
                                      || path.equals("/api/payment/callback")
                                      || (method.name().equals("GET")
                                          && (path.startsWith("/api/hotels")
                                              || path.startsWith("/api/room-types")));
                              operation.setSecurity(
                                  publicApi
                                      ? List.of()
                                      : List.of(new SecurityRequirement().addList("bearerAuth")));
                            }));
  }
}
