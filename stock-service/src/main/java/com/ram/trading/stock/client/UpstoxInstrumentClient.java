package com.ram.trading.stock.client;

import com.ram.trading.stock.client.properties.UpstoxInstrumentProperties;
import com.ram.trading.stock.exceptions.InstrumentImportException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
@Component
@RequiredArgsConstructor
@Slf4j
public class UpstoxInstrumentClient {

    private final UpstoxInstrumentProperties properties;

    public InputStream downloadInstrumentMaster() {

        log.info("[UpstoxClient] Downloading Instrument Master...");

        try {

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getUrl()))
                    .timeout(Duration.ofMinutes(2))
                    .GET()
                    .build();

            HttpResponse<InputStream> response =
                    client.send(request,
                            HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {

                throw new InstrumentImportException(
                        "Failed to download Instrument Master. HTTP Status : "
                                + response.statusCode(),
                        null);

            }

            log.info("[UpstoxClient] Download completed.");

            return new GZIPInputStream(response.body());

        } catch (Exception ex) {

            log.error("[UpstoxClient] Download failed.", ex);

            throw new InstrumentImportException(
                    "Unable to download Instrument Master",
                    ex);

        }
    }
}