package com.ram.trading.signal.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ram.trading.signal.engine.client.TradeExecutionClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** Guards the JSON contract used by Signal Engine when calling Trade Execution. */
class TradeExecutionClientContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void entryRequestUsesTradeExecutionJsonFieldNames() throws Exception {
        var request = new TradeExecutionClient.TradeExecutionRequest(
                701L, "TCS", "NSE_EQ|INE467B01029", "BUY",
                new BigDecimal("3500.00"), 2, new BigDecimal("7000.00"),
                new BigDecimal("3475.00"), new BigDecimal("3550.00"), "MARKET");

        JsonNode json = mapper.readTree(mapper.writeValueAsBytes(request));
        assertEquals(701L, json.get("signalId").asLong());
        assertEquals("NSE_EQ|INE467B01029", json.get("instrumentKey").asText());
        assertEquals("BUY", json.get("signal").asText());
        assertEquals(2, json.get("quantity").asInt());
        assertEquals(0, json.get("entryPrice").decimalValue().compareTo(new BigDecimal("3500.00")));
        assertEquals(0, json.get("stopLoss").decimalValue().compareTo(new BigDecimal("3475.00")));
        assertEquals(0, json.get("target").decimalValue().compareTo(new BigDecimal("3550.00")));
    }

    @Test
    void executionResponseDeserializesFillConfirmationFields() throws Exception {
        String json = """
                {"success":true,"signalId":701,"symbol":"TCS","signal":"BUY",
                 "quantity":2,"executedPrice":3501.25,"requiredMargin":7000.00,
                 "orderId":"broker-701","status":"FILLED","message":"filled",
                 "filledQuantity":2,"rejectionReason":null}
                """;

        var response = mapper.readValue(json, TradeExecutionClient.TradeExecutionResponse.class);
        assertTrue(response.success());
        assertEquals(701L, response.signalId());
        assertEquals("TCS", response.symbol());
        assertEquals("FILLED", response.status());
        assertEquals("broker-701", response.orderId());
        assertEquals(2, response.filledQuantity());
        assertEquals(0, response.executedPrice().compareTo(new BigDecimal("3501.25")));
    }
}
