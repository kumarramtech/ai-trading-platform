package com.ram.trading.market.data.client;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.beans.factory.annotation.Value;
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
    public WebClient webClient(
            @Value("${signal.tick.http.max-connections:25}") int maxConnections,
            @Value("${signal.tick.http.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${signal.tick.http.response-timeout-seconds:5}") long responseTimeoutSeconds) {

        /*
         * ============================================================
         * SIGNAL SERVICE HTTP CONNECTION POOL
         * ============================================================
         *
         * The application-level dispatcher controls how many tick
         * requests are allowed to execute concurrently.
         *
         * Therefore Reactor Netty must NOT maintain another large
         * pending-acquire queue.
         *
         * If all connections are busy, the request should fail fast
         * and the application-level bounded queue remains the owner
         * of backpressure.
         * ============================================================
         */

        ConnectionProvider provider =
                ConnectionProvider.builder("signal-service-tick-client")
                        .maxConnections(Math.max(1, maxConnections))

                        // Application-level dispatcher owns the queue.
                        .pendingAcquireMaxCount(100)

                        // Do not wait indefinitely for a connection.
                        .pendingAcquireTimeout(Duration.ZERO)

                        // Reuse connections for a reasonable period.
                        .maxIdleTime(Duration.ofSeconds(30))

                        // Periodically recycle connections.
                        .maxLifeTime(Duration.ofMinutes(5))

                        .build();

        /*
         * ============================================================
         * REACTOR NETTY HTTP CLIENT
         * ============================================================
         */

        HttpClient httpClient =
                HttpClient.create(provider)

                        /*
                         * TCP connection establishment timeout
                         */
                        .option(
                                ChannelOption.CONNECT_TIMEOUT_MILLIS,
                                Math.max(500, connectTimeoutMs))

                        /*
                         * Maximum time waiting for the HTTP response.
                         */
                        .responseTimeout(
                                Duration.ofSeconds(
                                        Math.max(1, responseTimeoutSeconds)))

                        /*
                         * Read / Write protection.
                         */
                        .doOnConnected(connection ->
                                connection
                                        .addHandlerLast(
                                                new ReadTimeoutHandler(
                                                        Math.max(
                                                                1,
                                                                responseTimeoutSeconds),
                                                        TimeUnit.SECONDS))

                                        .addHandlerLast(
                                                new WriteTimeoutHandler(
                                                        Math.max(
                                                                1,
                                                                responseTimeoutSeconds),
                                                        TimeUnit.SECONDS)));

        /*
         * ============================================================
         * WEBCLIENT
         * ============================================================
         *
         * IMPORTANT:
         *
         * WebClient.builder() returns WebClient.Builder.
         * .build() converts it into WebClient.
         * ============================================================
         */

        return WebClient.builder()
                .clientConnector(
                        new ReactorClientHttpConnector(httpClient))
                .build();
    }
}