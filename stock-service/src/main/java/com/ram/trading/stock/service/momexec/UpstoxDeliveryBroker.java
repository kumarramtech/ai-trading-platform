package com.ram.trading.stock.service.momexec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ram.trading.stock.service.auth.UpstoxTokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Upstox calls for the momentum executor: DELIVERY (product D) LIMIT orders only. Reads (prices, order status,
 * holdings, funds) are used in every mode; {@link #place} and {@link #cancel} are called only in LIVE mode, after
 * every guard in {@link MomentumExecutionService} passed. Nothing here is shared with the intraday order path.
 */
@Component
public class UpstoxDeliveryBroker {

    static final String TAG = "MOMX";

    private final UpstoxTokenService tokens;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Value("${upstox.base-url:https://api.upstox.com/v2}")
    private String baseUrl = "https://api.upstox.com/v2";

    @Value("${momentum-exec.order-url:https://api-hft.upstox.com/v2/order}")
    private String orderUrl = "https://api-hft.upstox.com/v2/order";

    public UpstoxDeliveryBroker(UpstoxTokenService tokens) {
        this.tokens = tokens;
    }

    /** Order status from Upstox: status (lower case: complete, rejected, cancelled, open, ...), filled, average. */
    record OrderState(String status, int filled, double average, String message) {
        boolean finished() {
            return "complete".equals(status) || "rejected".equals(status) || "cancelled".equals(status);
        }
    }

    /** The access token, or null when Upstox is not logged in (then nothing is sent). */
    String token() {
        try {
            String t = tokens.getAccessToken().block(Duration.ofSeconds(15));
            return t == null || t.isBlank() ? null : t.trim();
        } catch (Exception ex) {
            return null;
        }
    }

    /** Last prices by instrument key. */
    Map<String, Double> ltp(String token, List<String> keys) throws Exception {
        Map<String, Double> out = new HashMap<>();
        for (int i = 0; i < keys.size(); i += 400) {
            String joined = String.join(",", keys.subList(i, Math.min(keys.size(), i + 400)));
            out.putAll(parseLtp(get(token, baseUrl + "/market-quote/ltp?instrument_key=" + enc(joined))));
        }
        return out;
    }

    /** Places one DELIVERY LIMIT order; returns the Upstox order id. LIVE mode only. */
    String place(String token, String instrumentKey, String side, int qty, double limit) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("quantity", qty);
        body.put("product", "D");
        body.put("validity", "DAY");
        body.put("price", limit);
        body.put("tag", TAG);
        body.put("instrument_token", instrumentKey);
        body.put("order_type", "LIMIT");
        body.put("transaction_type", side);
        body.put("disclosed_quantity", 0);
        body.put("trigger_price", 0);
        body.put("is_amo", false);
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(orderUrl + "/place"))
                .header("Authorization", "Bearer " + token).header("Accept", "application/json")
                .header("Content-Type", "application/json").timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
        return parsePlaced(r.statusCode(), r.body());
    }

    void cancel(String token, String orderId) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(orderUrl + "/cancel?order_id=" + enc(orderId)))
                .header("Authorization", "Bearer " + token).header("Accept", "application/json")
                .timeout(Duration.ofSeconds(20)).DELETE().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new IllegalStateException("cancel HTTP " + r.statusCode() + ": " + cut(r.body()));
        }
    }

    OrderState order(String token, String orderId) throws Exception {
        return parseOrder(get(token, baseUrl + "/order/details?order_id=" + enc(orderId)));
    }

    /** Delivery shares at Upstox by instrument key: holdings (settled + T1) plus today's delivery positions. */
    Map<String, Integer> brokerShares(String token) throws Exception {
        return parseShares(get(token, baseUrl + "/portfolio/long-term-holdings"),
                get(token, baseUrl + "/portfolio/short-term-positions"));
    }

    /** Equity available margin (cash for delivery buys). */
    double availableFunds(String token) throws Exception {
        return get(token, baseUrl + "/user/get-funds-and-margin?segment=SEC").path("data").path("equity")
                .path("available_margin").asDouble(0);
    }

    // ------------------------------------------------------------------ parsing (pure, tested)

    static Map<String, Double> parseLtp(JsonNode root) {
        Map<String, Double> out = new HashMap<>();
        root.path("data").fields().forEachRemaining(f -> {
            String k = f.getValue().path("instrument_token").asText(null);
            double p = f.getValue().path("last_price").asDouble(0);
            if (k != null && p > 0) {
                out.put(k, p);
            }
        });
        return out;
    }

    static String parsePlaced(int http, String body) throws Exception {
        JsonNode root = new ObjectMapper().readTree(body == null || body.isBlank() ? "{}" : body);
        String id = root.path("data").path("order_id").asText("");
        if (http != 200 || !"success".equals(root.path("status").asText()) || id.isBlank()) {
            throw new IllegalStateException("order not accepted: HTTP " + http + " " + cut(body));
        }
        return id;
    }

    static OrderState parseOrder(JsonNode root) {
        JsonNode d = root.path("data");
        if (d.isArray()) {
            d = d.size() == 0 ? d : d.get(d.size() - 1);
        }
        return new OrderState(d.path("status").asText("").toLowerCase(), d.path("filled_quantity").asInt(0),
                d.path("average_price").asDouble(0), d.path("status_message").asText(""));
    }

    static Map<String, Integer> parseShares(JsonNode holdings, JsonNode positions) {
        Map<String, Integer> out = new HashMap<>();
        for (JsonNode h : holdings.path("data")) {
            out.merge(h.path("instrument_token").asText(), h.path("quantity").asInt(0) + h.path("t1_quantity").asInt(0),
                    Integer::sum);
        }
        for (JsonNode p : positions.path("data")) {
            if ("D".equals(p.path("product").asText()) || "CNC".equals(p.path("product").asText())) {
                out.merge(p.path("instrument_token").asText(), p.path("quantity").asInt(0), Integer::sum);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ http

    private JsonNode get(String token, String url) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token).header("Accept", "application/json")
                .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new IllegalStateException("Upstox " + url.replaceAll("\\?.*", "").replace(baseUrl, "")
                    + " HTTP " + r.statusCode());
        }
        return mapper.readTree(r.body());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String cut(String s) {
        return s == null ? "" : s.length() <= 200 ? s : s.substring(0, 200);
    }
}
