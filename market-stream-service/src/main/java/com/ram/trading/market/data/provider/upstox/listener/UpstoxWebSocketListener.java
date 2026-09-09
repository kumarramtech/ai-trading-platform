package com.ram.trading.market.data.provider.upstox.listener;


import com.ram.trading.market.data.parser.UpstoxMessageParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.http.WebSocket;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RequiredArgsConstructor
public class UpstoxWebSocketListener implements WebSocket.Listener {

    private final UpstoxMessageParser parser;

    private final ByteArrayOutputStream binaryMessageBuffer =
            new ByteArrayOutputStream();

    @Override
    public void onOpen(WebSocket webSocket) {

        log.debug("Connected to Upstox Market Feed");

        webSocket.request(1);

    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket,
                                     CharSequence data,
                                     boolean last) {

        log.debug("Text Message Received : {}", data);

        webSocket.request(1);

        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket,
                                       ByteBuffer data,
                                       boolean last) {

        int fragmentSize = data.remaining();

        log.debug(
                "Binary Message Received : {} bytes | last={}",
                fragmentSize,
                last);

        try {
            ByteBuffer copy = data.asReadOnlyBuffer();
            byte[] fragment = new byte[copy.remaining()];
            copy.get(fragment);

            binaryMessageBuffer.write(fragment, 0, fragment.length);

            if (last) {
                byte[] completeMessage =
                        binaryMessageBuffer.toByteArray();

                binaryMessageBuffer.reset();

                parser.parse(ByteBuffer.wrap(completeMessage));
            }

        } catch (Exception ex) {

            binaryMessageBuffer.reset();

            log.error(
                    "Unable to assemble/parse fragmented binary market message",
                    ex);
        }

        webSocket.request(1);

        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onPing(
            WebSocket webSocket,
            ByteBuffer message) {

        webSocket.request(1);

        return WebSocket.Listener.super.onPing(
                webSocket,
                message);
    }

    @Override
    public CompletionStage<?> onPong(WebSocket webSocket,
                                     ByteBuffer message) {

        log.debug("Pong Received");

        webSocket.request(1);

        return WebSocket.Listener.super.onPong(webSocket, message);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket,
                                      int statusCode,
                                      String reason) {

        log.debug("WebSocket Closed");
        log.debug("Status Code : {}", statusCode);
        log.debug("Reason      : {}", reason);

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