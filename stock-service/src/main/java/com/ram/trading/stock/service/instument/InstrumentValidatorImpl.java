package com.ram.trading.stock.service.instument;

import com.ram.trading.stock.dto.InstrumentValidationResult;
import com.ram.trading.stock.entity.Instrument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class InstrumentValidatorImpl implements InstrumentValidator {

    @Override
    public InstrumentValidationResult validate(List<Instrument> instruments) {

        log.info("[InstrumentValidator] Validation started.");

        List<Instrument> valid = new ArrayList<>();

        List<String> errors = new ArrayList<>();

        Set<String> instrumentKeys = new HashSet<>();

        Set<String> brokerTradingSymbols = new HashSet<>();

        int line = 2;

        for (Instrument instrument : instruments) {

            String error = validateRecord(
                    instrument,
                    instrumentKeys);

            if (error == null) {
                valid.add(instrument);
            } else {
                errors.add("Line " + line + " : " + error);
            }

            line++;
        }

        InstrumentValidationResult result =
                InstrumentValidationResult.builder()
                        .totalRecords(instruments.size())
                        .validRecords(valid.size())
                        .invalidRecords(instruments.size() - valid.size())
                        .validInstruments(valid)
                        .errors(errors)
                        .build();

        log.info("""
                [InstrumentValidator]

                Total Records   : {}

                Valid Records   : {}

                Invalid Records : {}
                """,
                result.getTotalRecords(),
                result.getValidRecords(),
                result.getInvalidRecords());

        return result;
    }

    private String validateRecord(
            Instrument instrument,
            Set<String> instrumentKeys) {

        if (isBlank(instrument.getTradingSymbol()))
            return "Trading Symbol Missing";

        if (isBlank(instrument.getInstrumentKey()))
            return "Instrument Key Missing";

        if (isBlank(instrument.getExchange()))
            return "Exchange Missing";

        if (isBlank(instrument.getSegment()))
            return "Segment Missing";

        if (!instrumentKeys.add(instrument.getInstrumentKey()))
            return "Duplicate Instrument Key";

        /*String businessKey =
                instrument.getBroker() + "|" +
                        instrument.getTradingSymbol();

        if (!brokerTradingSymbols.add(businessKey))
            return "Duplicate Broker + Trading Symbol";*/
       /* validateRecord(instrument,instrumentKeys,brokerTradingSymbols);*/

        return null;
    }

    private boolean isBlank(String value) {

        return value == null || value.trim().isEmpty();

    }
}