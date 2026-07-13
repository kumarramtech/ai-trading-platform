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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
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
    public void connect() {

        try {

            log.info("=======================================");
            log.info("Connecting to Upstox Market Feed...");
            log.info("=======================================");

            HttpClient client = HttpClient.newHttpClient();

            FeedAuthorizationResponse response = marketFeedClient.authorizeFeed().block();

            String websocketUrl =
                    response.getData().getAuthorizedRedirectUri();
            log.info("Connecting URI : {}", websocketUrl);

            webSocket = client.newWebSocketBuilder()

                    .buildAsync(
                            URI.create(websocketUrl),
                            new UpstoxWebSocketListener(parser)
                    )

                    .join();

            log.info("WebSocket Connection Established.");
            log.info("Instrument = {}", properties.getDefaultInstruments());
            subscribe(List.of(properties.getDefaultInstruments()));

        } catch (Exception ex) {

            log.error("Unable to connect to Upstox", ex);

        }

    }

    @Override
    public void disconnect() {

        if (webSocket != null) {

            webSocket.sendClose(
                    WebSocket.NORMAL_CLOSURE,
                    "Disconnect");

        }

    }

    @Override
    public void subscribe(List<String> instrumentKeys) {

        try {

            if (webSocket == null) {

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
                                            .build()
                            )
                            .build();

            String json = objectMapper.writeValueAsString(request);

            log.info("=======================================");
            log.info("Sending Subscription Request");
            log.info("{}", json);
            log.info("=======================================");

            ByteBuffer buffer =
                    ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8));

            webSocket.sendBinary(buffer, true).join();

            log.info("Binary Subscription Request Sent Successfully.");

        } catch (Exception ex) {

            log.error("Unable to subscribe.", ex);

        }

    }

    @Override
    public boolean isConnected() {

        return webSocket != null;

    }

}