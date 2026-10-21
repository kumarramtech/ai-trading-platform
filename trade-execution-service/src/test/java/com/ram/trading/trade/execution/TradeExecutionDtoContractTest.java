package com.ram.trading.trade.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ram.trading.trade.execution.dto.TradeExecutionRequest;
import com.ram.trading.trade.execution.dto.TradeExecutionResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** Guards the HTTP JSON shape exposed by Trade Execution to Signal Engine. */
class TradeExecutionDtoContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void entryRequestAcceptsSignalEngineJsonFieldNames() throws Exception {
        String json = """
                {"signalId":701,"symbol":"TCS","instrumentKey":"NSE_EQ|INE467B01029",
                 "signal":"BUY","entryPrice":3500.00,"quantity":2,
                 "requiredMargin":7000.00,"stopLoss":3475.00,"target":3550.00,
                 "orderType":"MARKET"}
                """;
        TradeExecutionRequest request = mapper.readValue(json, TradeExecutionRequest.class);
        assertEquals(701L, request.getSignalId());
        assertEquals("TCS", request.getSymbol());
        assertEquals("NSE_EQ|INE467B01029", request.getInstrumentKey());
        assertEquals("BUY", request.getSignal());
        assertEquals(2, request.getQuantity());
        assertEquals(0, request.getEntryPrice().compareTo(new BigDecimal("3500.00")));
        assertEquals(0, request.getStopLoss().compareTo(new BigDecimal("3475.00")));
        assertEquals(0, request.getTarget().compareTo(new BigDecimal("3550.00")));
    }

    @Test
    void entryResponsePublishesFieldsSignalEngineRequiresForFillValidation() throws Exception {
        TradeExecutionResponse response = TradeExecutionResponse.builder()
                .success(true).signalId(701L).symbol("TCS").signal("BUY")
                .quantity(2).executedPrice(new BigDecimal("3501.25"))
                .requiredMargin(new BigDecimal("7000.00")).orderId("broker-701")
                .status("FILLED").message("filled").filledQuantity(2).build();

        JsonNode json = mapper.readTree(mapper.writeValueAsBytes(response));
        assertEquals(true, json.get("success").asBoolean());
        assertEquals(701L, json.get("signalId").asLong());
        assertEquals("TCS", json.get("symbol").asText());
        assertEquals("FILLED", json.get("status").asText());
        assertEquals("broker-701", json.get("orderId").asText());
        assertEquals(2, json.get("filledQuantity").asInt());
        assertTrue(json.has("rejectionReason"));
    }
}
