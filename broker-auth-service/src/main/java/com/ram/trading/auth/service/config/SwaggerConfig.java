package com.ram.trading.auth.service.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {

        return new OpenAPI()
                .info(new Info()
                        .title("AI Trading Platform - Broker Auth Service")
                        .description("Broker Auth APIs")
                        .version("1.0")
                        .contact(new Contact()
                                .name("AI Trading Platform")
                                .email("support@aitrading.com"))
                        .license(new License()
                                .name("Internal Use")));
    }
}