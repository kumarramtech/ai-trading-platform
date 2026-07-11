package com.ram.trading.market.data.provider.upstox.websocket;

import com.ram.trading.market.data.auth.upstox.UpstoxProperties;
import com.ram.trading.market.data.auth.upstox.UpstoxTokenService;
import com.ram.trading.market.data.parser.UpstoxMessageParser;
import com.ram.trading.market.data.provider.dto.FeedAuthorizationResponse;
import com.ram.trading.market.data.provider.upstox.client.UpstoxMarketFeedClient;
import com.ram.trading.market.data.provider.upstox.listener.UpstoxWebSocketListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class UpstoxWebSocketClientImpl implements UpstoxWebSocketClient {

    private final UpstoxTokenService tokenService;

    private WebSocket webSocket;

    private final UpstoxMessageParser parser;

    private final UpstoxMarketFeedClient marketFeedClient;

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

            webSocket = client.newWebSocketBuilder()

                    .buildAsync(
                            URI.create(websocketUrl),
                            new UpstoxWebSocketListener(parser)
                    )

                    .join();

            log.info("WebSocket Connection Established.");

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

    }

    @Override
    public boolean isConnected() {

        return webSocket != null;

    }

}