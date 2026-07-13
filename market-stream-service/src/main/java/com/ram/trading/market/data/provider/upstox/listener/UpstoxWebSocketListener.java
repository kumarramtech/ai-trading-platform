package com.ram.trading.market.data.provider.upstox.listener;


import com.ram.trading.market.data.parser.UpstoxMessageParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RequiredArgsConstructor
public class UpstoxWebSocketListener implements WebSocket.Listener {

    private final UpstoxMessageParser parser;

    @Override
    public void onOpen(WebSocket webSocket) {

        log.info("=======================================");
        log.info("Connected to Upstox Market Feed");
        log.info("=======================================");

        webSocket.request(1);

    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket,
                                     CharSequence data,
                                     boolean last) {

        log.info("Text Message Received : {}", data);

        webSocket.request(1);

        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket,
                                       ByteBuffer data,
                                       boolean last) {

        log.info("Binary Message Received : {} bytes",
                data.remaining());

        parser.parse(data);

        webSocket.request(1);

        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onPing(WebSocket webSocket,
                                     ByteBuffer message) {

        log.info("Ping Received");

        webSocket.request(1);

        return WebSocket.Listener.super.onPing(webSocket, message);
    }

    @Override
    public CompletionStage<?> onPong(WebSocket webSocket,
                                     ByteBuffer message) {

        log.info("Pong Received");

        webSocket.request(1);

        return WebSocket.Listener.super.onPong(webSocket, message);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket,
                                      int statusCode,
                                      String reason) {

        log.info("=======================================");
        log.info("WebSocket Closed");
        log.info("Status Code : {}", statusCode);
        log.info("Reason      : {}", reason);
        log.info("=======================================");

        return WebSocket.Listener.super.onClose(webSocket,
                statusCode,
                reason);
    }

    @Override
    public void onError(WebSocket webSocket,
                        Throwable error) {

        log.error("WebSocket Error", error);

    }

}