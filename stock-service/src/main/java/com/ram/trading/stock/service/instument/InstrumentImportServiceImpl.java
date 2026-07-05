package com.ram.trading.stock.service.instument;

import com.ram.trading.stock.dto.InstrumentValidationResult;
import com.ram.trading.stock.entity.Instrument;
import com.ram.trading.stock.exceptions.InstrumentImportException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class InstrumentImportServiceImpl
        implements InstrumentImportService {

    private static final int BATCH_SIZE = 1000;

    private final InstrumentJsonParser parser;
    private final InstrumentService instrumentService;
    private final InstrumentValidator validator;

   /* @Override
    public void importInstruments(InputStream inputStream) {

        log.info("[InstrumentImport] Import started.");

        try {

            List<Instrument> parsedInstruments =
                    parser.parse(inputStream);

            log.info("Parsed Instruments : {}", parsedInstruments.size());
            InstrumentValidationResult validation =
                    validator.validate(parsedInstruments);

            log.info("""
                    [InstrumentImport]

                    Total Records   : {}

                    Valid Records   : {}

                    Invalid Records : {}
                    """,
                    validation.getTotalRecords(),
                    validation.getValidRecords(),
                    validation.getInvalidRecords());


            if (!validation.getErrors().isEmpty()) {

                validation.getErrors()
                        .forEach(error ->
                                log.warn("[InstrumentImport] {}", error));

            }


            instrumentService.deleteAll();

            Collection<Instrument> unique =
                    validation.getValidInstruments()
                            .stream()
                            .collect(Collectors.toMap(
                                    i -> i.getBroker() + "|" + i.getTradingSymbol(),
                                    Function.identity(),
                                    (first, second) -> first,
                                    LinkedHashMap::new))
                            .values();

            saveInBatches(new ArrayList<>(unique));

            log.info("[InstrumentImport] Import completed successfully.");

        } catch (IOException ex) {

            log.error("[InstrumentImport] Import failed.", ex);

            throw new InstrumentImportException(
                    "Unable to import Instrument Master",
                    ex);

        }
    }*/

    @Override
    public void importInstruments(InputStream inputStream) {

        log.info("[InstrumentImport] Import started.");

        try {

            List<Instrument> parsedInstruments =
                    parser.parse(inputStream);

            log.info("==================================================");
            log.info("Parsed Instruments : {}", parsedInstruments.size());
            log.info("==================================================");

            parsedInstruments.stream()
                    .filter(i ->
                            "RELIANCE".equals(i.getTradingSymbol())
                                    || "HDFCBANK".equals(i.getTradingSymbol())
                                    || "ICICIBANK".equals(i.getTradingSymbol()))
                    .forEach(i -> log.info(
                            "[PARSED] {} | {} | {} | {}",
                            i.getTradingSymbol(),
                            i.getExchange(),
                            i.getSegment(),
                            i.getInstrumentType()));

            InstrumentValidationResult validation =
                    validator.validate(parsedInstruments);

            log.info("""
                [InstrumentImport]

                Total Records   : {}

                Valid Records   : {}

                Invalid Records : {}
                """,
                    validation.getTotalRecords(),
                    validation.getValidRecords(),
                    validation.getInvalidRecords());

            log.info("==================================================");
            log.info("Total Parsed : {}", parsedInstruments.size());
            log.info("Valid        : {}", validation.getValidInstruments().size());
            log.info("Invalid      : {}", validation.getInvalidRecords());
            log.info("==================================================");

            validation.getValidInstruments().stream()
                    .filter(i ->
                            "RELIANCE".equals(i.getTradingSymbol())
                                    || "HDFCBANK".equals(i.getTradingSymbol())
                                    || "ICICIBANK".equals(i.getTradingSymbol()))
                    .forEach(i -> log.info(
                            "[VALID] {} | {} | {} | {}",
                            i.getTradingSymbol(),
                            i.getExchange(),
                            i.getSegment(),
                            i.getInstrumentType()));

            if (!validation.getErrors().isEmpty()) {
                validation.getErrors()
                        .forEach(error ->
                                log.warn("[InstrumentImport] {}", error));
            }

            instrumentService.deleteAll();

            Map<String, Instrument> uniqueMap = new LinkedHashMap<>();

            for (Instrument instrument : validation.getValidInstruments()) {

                Instrument existing =
                        uniqueMap.get(instrument.getTradingSymbol());

                if (existing == null) {

                    uniqueMap.put(instrument.getTradingSymbol(), instrument);
                    continue;
                }

                boolean currentPreferred =
                        "NSE".equalsIgnoreCase(instrument.getExchange())
                                && "NSE_EQ".equalsIgnoreCase(instrument.getSegment())
                                && "EQ".equalsIgnoreCase(instrument.getInstrumentType());

                boolean existingPreferred =
                        "NSE".equalsIgnoreCase(existing.getExchange())
                                && "NSE_EQ".equalsIgnoreCase(existing.getSegment())
                                && "EQ".equalsIgnoreCase(existing.getInstrumentType());

                if (currentPreferred && !existingPreferred) {

                    uniqueMap.put(instrument.getTradingSymbol(), instrument);
                }
            }

            Collection<Instrument> unique = uniqueMap.values();

            log.info("==================================================");
            log.info("Unique Instruments : {}", unique.size());
            log.info("==================================================");

            unique.stream()
                    .filter(i ->
                            "RELIANCE".equals(i.getTradingSymbol())
                                    || "HDFCBANK".equals(i.getTradingSymbol())
                                    || "ICICIBANK".equals(i.getTradingSymbol()))
                    .forEach(i -> log.info(
                            "[UNIQUE] {} | {} | {} | {}",
                            i.getTradingSymbol(),
                            i.getExchange(),
                            i.getSegment(),
                            i.getInstrumentType()));

            saveInBatches(new ArrayList<>(unique));

            log.info("[InstrumentImport] Import completed successfully.");

        } catch (IOException ex) {

            log.error("[InstrumentImport] Import failed.", ex);

            throw new InstrumentImportException(
                    "Unable to import Instrument Master",
                    ex);
        }
    }

    private void saveInBatches(List<Instrument> instruments) {
        int batch = 1;
        for (int i = 0; i < instruments.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, instruments.size());
            log.info(
                    "[InstrumentImport] Saving Batch {} ({} - {})",batch, i + 1,end);
            instrumentService.saveAll(instruments.subList(i, end));
            batch++;
        }

        log.info("[InstrumentImport] All batches saved successfully.");
    }
}