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
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class InstrumentImportServiceImpl
        implements InstrumentImportService {

    private static final int BATCH_SIZE = 1000;

    private final InstrumentCsvParser parser;
    private final InstrumentService instrumentService;
    private final InstrumentValidator validator;

    @Override
    public void importInstruments(InputStream inputStream) {

        log.info("[InstrumentImport] Import started.");

        try {

            List<Instrument> parsedInstruments =
                    parser.parse(inputStream);

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

            saveInBatches(validation.getValidInstruments());

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
                    "[InstrumentImport] Saving Batch {} ({} - {})",
                    batch,
                    i + 1,
                    end);

            instrumentService.saveAll(
                    instruments.subList(i, end));

            batch++;
        }

        log.info("[InstrumentImport] All batches saved successfully.");
    }
}