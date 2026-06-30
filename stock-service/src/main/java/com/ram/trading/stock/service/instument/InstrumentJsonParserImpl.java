package com.ram.trading.stock.service.instument;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ram.trading.stock.entity.Instrument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class InstrumentJsonParserImpl implements InstrumentJsonParser {

    private final ObjectMapper objectMapper;

    @Override
    public List<Instrument> parse(InputStream inputStream) throws IOException {
        log.info("[InstrumentParser] JSON parsing started.");
        List<Instrument> instruments = new ArrayList<>();
        JsonFactory factory = objectMapper.getFactory();
        try (JsonParser parser = factory.createParser(inputStream)) {
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw new IOException("Expected JSON Array");
            }
            while (parser.nextToken() == JsonToken.START_OBJECT) {
                JsonNode node = objectMapper.readTree(parser);
                log.info("{}", node);
                Instrument instrument = Instrument.builder()
                        .broker("UPSTOX")
                        .exchange(getText(node, "exchange"))
                        .segment(getText(node, "segment"))
                        .tradingSymbol(getText(node, "trading_symbol"))
                        .companyName(getText(node, "company_name"))
                        .instrumentKey(getText(node, "instrument_key"))
                        .exchangeToken(getText(node, "exchange_token"))
                        .isin(getText(node, "isin"))
                        .instrumentType(getText(node, "instrument_type"))
                        .tickSize(getDecimal(node, "tick_size"))
                        .lotSize(getInteger(node, "lot_size"))
                        .freezeQuantity(getInteger(node, "freeze_quantity"))
                        .expiry(getDate(node, "expiry"))
                        .strikePrice(getDecimal(node, "strike_price"))
                        .optionType(getText(node, "option_type"))
                        .isActive(true)
                        .build();
                instruments.add(instrument);
            }
        }
        log.info("[InstrumentParser] Parsed {} instruments.",
                instruments.size());
        return instruments;
    }


    private String getText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    private Integer getInteger(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.numberValue().intValue();
    }

    private BigDecimal getDecimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.decimalValue();
    }

    private LocalDate getDate(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        long epochMillis = value.asLong();
        if (epochMillis == 0) {
            return null;
        }
        return Instant.ofEpochMilli(epochMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDate();
    }

}