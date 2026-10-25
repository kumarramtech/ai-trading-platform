package com.ram.trading.trade.execution.client;

import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
public class WebClientConfig {

    /**
     * Applies bounded connect/response timeouts to every WebClient built from the
     * shared builder. Without them a hung broker call never completes, which stalls
     * order reconciliation indefinitely. A timeout on order placement is treated as
     * an ambiguous outcome and reconciled by tag; it never triggers a blind retry.
     */
    @Bean
    public WebClientCustomizer brokerTimeoutCustomizer(
            @Value("${execution.http.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${execution.http.response-timeout-ms:10000}") long responseTimeoutMs) {

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .responseTimeout(Duration.ofMillis(responseTimeoutMs));

        return builder -> builder.clientConnector(new ReactorClientHttpConnector(httpClient));
    }

    @Bean
    public WebClient webClient(
            WebClient.Builder builder,
            @Value("${broker.service.base-url}") String brokerServiceUrl) {

        return builder
                .baseUrl(brokerServiceUrl)
                .build();
    }
}
