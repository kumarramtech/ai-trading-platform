package com.ram.trading.market.data.provider.upstox.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ram.trading.market.data.auth.upstox.UpstoxProperties;
import com.ram.trading.market.data.client.StockInstrumentClient;
import com.ram.trading.market.data.parser.UpstoxMessageParser;
import com.ram.trading.market.data.provider.dto.FeedAuthorizationResponse;
import com.ram.trading.market.data.provider.dto.InstrumentSubscriptionResponse;
import com.ram.trading.market.data.provider.dto.SubscriptionData;
import com.ram.trading.market.data.provider.dto.SubscriptionRequest;
import com.ram.trading.market.data.provider.upstox.client.UpstoxMarketFeedClient;
import com.ram.trading.market.data.provider.upstox.listener.UpstoxWebSocketListener;
import com.ram.trading.market.data.service.MarketDataProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.LinkedHashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class UpstoxWebSocketClientImpl implements UpstoxWebSocketClient, MarketDataProvider {

    /*
     * Upstox V3 currently allows up to 2 normal WebSocket connections per user.
     * We deliberately keep each FULL subscription at <= 1500 instruments.
     * This safely covers the documented FULL-feed limits and allows our current
     * ~2655 instrument universe to be split across two sockets.
     */
    private static final int MAX_INSTRUMENTS_PER_CONNECTION = 1500;
    private static final int MAX_CONNECTIONS = 2;

    private final UpstoxMessageParser parser;
    private final UpstoxMarketFeedClient marketFeedClient;
    private final ObjectMapper objectMapper;
    private final UpstoxProperties properties;
    private final StockInstrumentClient stockInstrumentClient;

    private WebSocket primaryWebSocket;
    private WebSocket secondaryWebSocket;

    @Override
    public synchronized void connect() {
        try {
            if (isFullyConnected()) {
                log.debug("Already connected to Upstox Market Feed on all required connections.");
                return;
            }

            disconnectQuietly();

            log.debug("Connecting to Upstox Market Feed...");

            List<String> instrumentKeys = loadInstrumentKeys();

            if (instrumentKeys.isEmpty()) {
                log.warn("No instruments available for Upstox subscription.");
                return;
            }

            List<List<String>> partitions = partition(instrumentKeys);

            if (partitions.size() > MAX_CONNECTIONS) {
                throw new IllegalStateException(
                        "Upstox subscription universe requires " + partitions.size()
                                + " connections, but maximum supported is " + MAX_CONNECTIONS
                                + ". Instruments=" + instrumentKeys.size()
                );
            }

            log.debug(
                    "UPSTOX SUBSCRIPTION PLAN -> instruments={}, connections={}, partitionSize={}",
                    instrumentKeys.size(),
                    partitions.size(),
                    MAX_INSTRUMENTS_PER_CONNECTION
            );

            primaryWebSocket = openConnection("PRIMARY");
            subscribe(primaryWebSocket, partitions.get(0), "PRIMARY");

            if (partitions.size() > 1) {
                secondaryWebSocket = openConnection("SECONDARY");
                subscribe(secondaryWebSocket, partitions.get(1), "SECONDARY");
            }

            log.debug(
                    "UPSTOX MARKET FEED READY -> primaryConnected={}, secondaryConnected={}, subscribedInstruments={}",
                    isSocketConnected(primaryWebSocket),
                    isSocketConnected(secondaryWebSocket),
                    instrumentKeys.size()
            );

        } catch (Exception ex) {
            log.error("Unable to connect to Upstox Market Feed", ex);
            disconnectQuietly();
        }
    }

    private List<String> loadInstrumentKeys() {
        Set<String> configuredKeys = new LinkedHashSet<>(
                stockInstrumentClient
                        .loadSubscriptions()
                        .stream()
                        .map(InstrumentSubscriptionResponse::getInstrumentKey)
                        .filter(key -> key != null && !key.isBlank())
                        .toList());

        // Index feeds are required by MarketRegimeTracker. Keep these two
        // explicit so regime data does not depend on the stock universe
        // endpoint containing index instruments.
        configuredKeys.add("NSE_INDEX|Nifty 50");
        configuredKeys.add("NSE_INDEX|Nifty Bank");

        List<String> instrumentKeys = new ArrayList<>(configuredKeys);

        log.debug("Loaded {} unique instruments for Upstox subscription", instrumentKeys.size());

        instrumentKeys.stream()
                .limit(10)
                .forEach(key -> log.debug("UPSTOX SUBSCRIPTION SAMPLE -> {}", key));

        return instrumentKeys;
    }

    private List<List<String>> partition(List<String> instrumentKeys) {
        List<List<String>> partitions = new ArrayList<>();

        for (int start = 0; start < instrumentKeys.size(); start += MAX_INSTRUMENTS_PER_CONNECTION) {
            int end = Math.min(
                    start + MAX_INSTRUMENTS_PER_CONNECTION,
                    instrumentKeys.size()
            );
            partitions.add(new ArrayList<>(instrumentKeys.subList(start, end)));
        }

        return partitions;
    }

    private WebSocket openConnection(String connectionName) {
        FeedAuthorizationResponse response =
                marketFeedClient.authorizeFeed().block();

        if (response == null
                || response.getData() == null
                || response.getData().getAuthorizedRedirectUri() == null) {
            throw new IllegalStateException(
                    "Unable to obtain authorized websocket url from Upstox for " + connectionName
            );
        }

        String websocketUrl = response.getData().getAuthorizedRedirectUri();

        log.debug("UPSTOX {} -> Connecting URI received", connectionName);

        HttpClient client = HttpClient.newHttpClient();

        WebSocket socket = client.newWebSocketBuilder()
                .buildAsync(
                        URI.create(websocketUrl),
                        new UpstoxWebSocketListener(parser))
                .join();

        log.debug(
                "UPSTOX {} -> WebSocket Connection Established, connected={}",
                connectionName,
                isSocketConnected(socket)
        );

        return socket;
    }

    private void subscribe(
            WebSocket socket,
            List<String> instrumentKeys,
            String connectionName) {

        if (!isSocketConnected(socket)) {
            throw new IllegalStateException(
                    "Cannot subscribe because " + connectionName + " WebSocket is not connected"
            );
        }

        SubscriptionRequest request =
                SubscriptionRequest.builder()
                        .guid(UUID.randomUUID().toString())
                        .method("sub")
                        .data(
                                SubscriptionData.builder()
                                        .mode("full")
                                        .instrumentKeys(instrumentKeys)
                                        .build())
                        .build();

        try {
            String json = objectMapper.writeValueAsString(request);

            log.debug(
                    "UPSTOX {} -> Sending FULL subscription for {} instruments",
                    connectionName,
                    instrumentKeys.size()
            );

            ByteBuffer buffer = ByteBuffer.wrap(
                    json.getBytes(StandardCharsets.UTF_8)
            );

            socket.sendBinary(buffer, true).join();

            log.debug(
                    "UPSTOX {} -> Binary FULL Subscription Request Sent Successfully -> {} instruments",
                    connectionName,
                    instrumentKeys.size()
            );

        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Unable to subscribe " + connectionName + " WebSocket",
                    ex
            );
        }
    }

    @Override
    public synchronized void disconnect() {
        disconnectQuietly();
    }

    private void disconnectQuietly() {
        closeSocket(primaryWebSocket, "PRIMARY");
        closeSocket(secondaryWebSocket, "SECONDARY");
        primaryWebSocket = null;
        secondaryWebSocket = null;
    }

    private void closeSocket(WebSocket socket, String connectionName) {
        if (socket == null) {
            return;
        }

        try {
            if (!socket.isInputClosed() && !socket.isOutputClosed()) {
                socket.sendClose(
                                WebSocket.NORMAL_CLOSURE,
                                "Disconnect")
                        .join();
            }
        } catch (Exception ex) {
            log.warn("Error while closing {} Upstox WebSocket", connectionName, ex);
        }
    }

    @Override
    public void subscribe(List<String> instrumentKeys) {
        if (instrumentKeys == null || instrumentKeys.isEmpty()) {
            log.warn("No instruments supplied for subscription.");
            return;
        }

        try {
            List<List<String>> partitions = partition(instrumentKeys);

            if (partitions.size() > MAX_CONNECTIONS) {
                throw new IllegalArgumentException(
                        "Cannot subscribe " + instrumentKeys.size()
                                + " instruments using maximum " + MAX_CONNECTIONS
                                + " connections at " + MAX_INSTRUMENTS_PER_CONNECTION
                                + " instruments per connection"
                );
            }

            if (primaryWebSocket == null) {
                log.warn("Primary WebSocket is not connected; subscription skipped.");
                return;
            }

            subscribe(primaryWebSocket, partitions.get(0), "PRIMARY");

            if (partitions.size() > 1) {
                if (secondaryWebSocket == null) {
                    log.warn("Secondary WebSocket is not connected; second subscription skipped.");
                    return;
                }
                subscribe(secondaryWebSocket, partitions.get(1), "SECONDARY");
            }

        } catch (Exception ex) {
            log.error("Unable to subscribe instruments to Upstox", ex);
        }
    }

    @Override
    public boolean isConnected() {
        return isFullyConnected();
    }

    private boolean isFullyConnected() {
        if (!isSocketConnected(primaryWebSocket)) {
            return false;
        }

        // If a second socket is being used, both must be healthy.
        return secondaryWebSocket == null || isSocketConnected(secondaryWebSocket);
    }

    private boolean isSocketConnected(WebSocket socket) {
        return socket != null
                && !socket.isInputClosed()
                && !socket.isOutputClosed();
    }

    @Override
    public void unsubscribe(List<String> instrumentKeys) {
        // TODO: Implement unsubscribe later if required.
        log.debug("Unsubscribe requested for {} instruments", instrumentKeys.size());
    }
}
