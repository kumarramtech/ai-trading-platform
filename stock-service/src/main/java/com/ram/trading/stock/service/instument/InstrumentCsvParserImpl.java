package com.ram.trading.stock.service.instument;

import com.ram.trading.stock.entity.Instrument;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class InstrumentCsvParserImpl implements InstrumentCsvParser {

    @Override
    public List<Instrument> parse(InputStream inputStream) throws IOException {

        log.info("[InstrumentParser] CSV parsing started.");

        List<Instrument> instruments = new ArrayList<>();

        Reader reader = new InputStreamReader(inputStream);

        CSVParser parser = CSVFormat.DEFAULT
                .builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build()
                .parse(reader);

        for (CSVRecord record : parser) {
            Instrument instrument = map(record);
            instruments.add(instrument);
        }


        log.info("[InstrumentParser] CSV parsing completed. Total Records : {}",
                instruments.size());

        return instruments;
    }

    private Instrument map(CSVRecord record) {

        return Instrument.builder().broker("UPSTOX")

                .exchange(record.get("exchange"))

                .segment(record.get("segment"))

                .tradingSymbol(record.get("trading_symbol"))

                .companyName(record.get("company_name"))

                .instrumentKey(record.get("instrument_key"))

                .exchangeToken(record.get("exchange_token"))

                .isin(record.get("isin"))

                .instrumentType(record.get("instrument_type"))

                .tickSize(parseDecimal(record.get("tick_size")))

                .lotSize(parseInteger(record.get("lot_size")))

                .freezeQuantity(parseInteger(record.get("freeze_quantity")))

                .expiry(parseDate(record.get("expiry")))

                .strikePrice(parseDecimal(record.get("strike_price")))

                .optionType(record.get("option_type"))

                .isActive(true)

                .build();
    }
    private Integer parseInteger(String value) {

        if (value == null || value.isBlank()) {
            return null;
        }

        return Integer.parseInt(value);
    }
    private BigDecimal parseDecimal(String value) {

        if (value == null || value.isBlank()) {
            return null;
        }

        return new BigDecimal(value);
    }
    private LocalDate parseDate(String value) {

        if (value == null || value.isBlank()) {
            return null;
        }

        return LocalDate.parse(value);
    }
}