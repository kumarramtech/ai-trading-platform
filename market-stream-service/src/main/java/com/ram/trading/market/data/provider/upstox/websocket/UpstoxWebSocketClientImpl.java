package com.ram.trading.market.data.provider.upstox.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ram.trading.market.data.auth.upstox.UpstoxProperties;
import com.ram.trading.market.data.parser.UpstoxMessageParser;
import com.ram.trading.market.data.provider.dto.FeedAuthorizationResponse;
import com.ram.trading.market.data.provider.dto.SubscriptionData;
import com.ram.trading.market.data.provider.dto.SubscriptionRequest;
import com.ram.trading.market.data.provider.upstox.client.UpstoxMarketFeedClient;
import com.ram.trading.market.data.provider.upstox.listener.UpstoxWebSocketListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class UpstoxWebSocketClientImpl implements UpstoxWebSocketClient {

    private WebSocket webSocket;

    private final UpstoxMessageParser parser;
    private final UpstoxMarketFeedClient marketFeedClient;
    private final ObjectMapper objectMapper;
    private final UpstoxProperties properties;

    @Override
    public synchronized void connect() {

        try {

            // Prevent duplicate connections
            if (webSocket != null
                    && !webSocket.isInputClosed()
                    && !webSocket.isOutputClosed()) {

                log.info("=======================================");
                log.info("Already connected to Upstox Market Feed.");
                log.info("=======================================");
                return;
            }

            // Clean old socket if present
            if (webSocket != null) {

                try {
                    webSocket.abort();
                } catch (Exception ignored) {
                }

                webSocket = null;
            }

            log.info("=======================================");
            log.info("Connecting to Upstox Market Feed...");
            log.info("=======================================");

            FeedAuthorizationResponse response =
                    marketFeedClient.authorizeFeed().block();

            if (response == null
                    || response.getData() == null
                    || response.getData().getAuthorizedRedirectUri() == null) {

                throw new IllegalStateException(
                        "Unable to obtain authorized websocket url from Upstox.");
            }

            String websocketUrl =
                    response.getData().getAuthorizedRedirectUri();

            log.info("Connecting URI : {}", websocketUrl);

            HttpClient client = HttpClient.newHttpClient();

            webSocket = client.newWebSocketBuilder()
                    .buildAsync(
                            URI.create(websocketUrl),
                            new UpstoxWebSocketListener(parser))
                    .join();

            log.info("=======================================");
            log.info("WebSocket Connection Established.");
            log.info("Instrument : {}", properties.getDefaultInstruments());
            log.info("=======================================");

            subscribe(List.of(properties.getDefaultInstruments()));

        } catch (Exception ex) {

            webSocket = null;

            log.error("Unable to connect to Upstox", ex);
        }
    }

    @Override
    public synchronized void disconnect() {

        if (webSocket == null) {
            return;
        }

        try {

            webSocket.sendClose(
                            WebSocket.NORMAL_CLOSURE,
                            "Disconnect")
                    .join();

        } catch (Exception ex) {

            log.warn("Error while closing websocket.", ex);

        } finally {

            webSocket = null;
        }
    }

    @Override
    public void subscribe(List<String> instrumentKeys) {

        try {

            if (!isConnected()) {

                log.warn("WebSocket is not connected.");
                return;
            }

            SubscriptionRequest request =
                    SubscriptionRequest.builder()
                            .guid(UUID.randomUUID().toString())
                            .method("sub")
                            .data(
                                    SubscriptionData.builder()
                                            .mode("ltpc")
                                            .instrumentKeys(instrumentKeys)
                                            .build())
                            .build();

            String json = objectMapper.writeValueAsString(request);

            log.info("=======================================");
            log.info("Sending Subscription Request");
            log.info("{}", json);
            log.info("=======================================");

            ByteBuffer buffer =
                    ByteBuffer.wrap(
                            json.getBytes(StandardCharsets.UTF_8));

            webSocket.sendBinary(buffer, true).join();

            log.info("Binary Subscription Request Sent Successfully.");

        } catch (Exception ex) {

            log.error("Unable to subscribe.", ex);
        }
    }

    @Override
    public boolean isConnected() {

        return webSocket != null
                && !webSocket.isInputClosed()
                && !webSocket.isOutputClosed();
    }
}