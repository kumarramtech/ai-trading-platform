package com.ram.trading.signal.engine.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient.Builder webClientBuilder() {

        ConnectionProvider provider =
                ConnectionProvider.builder("trading-platform")

                        .maxConnections(200)

                        .pendingAcquireMaxCount(500)

                        .pendingAcquireTimeout(Duration.ofSeconds(10))

                        .maxIdleTime(Duration.ofSeconds(30))

                        .maxLifeTime(Duration.ofMinutes(5))

                        .build();

        HttpClient httpClient =
                HttpClient.create(provider)

                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)

                        .responseTimeout(Duration.ofSeconds(10))

                        .doOnConnected(connection ->

                                connection
                                        .addHandlerLast(
                                                new ReadTimeoutHandler(
                                                        10,
                                                        TimeUnit.SECONDS))

                                        .addHandlerLast(
                                                new WriteTimeoutHandler(
                                                        10,
                                                        TimeUnit.SECONDS)));

        return WebClient.builder()
                .clientConnector(
                        new ReactorClientHttpConnector(httpClient));
    }
}