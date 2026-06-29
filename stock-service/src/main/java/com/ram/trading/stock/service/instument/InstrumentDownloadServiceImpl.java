package com.ram.trading.stock.service.instument;

import com.ram.trading.stock.client.UpstoxInstrumentClient;
import com.ram.trading.stock.exceptions.InstrumentImportException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class InstrumentDownloadServiceImpl implements InstrumentDownloadService {

    private final UpstoxInstrumentClient client;
    private final InstrumentImportService importService;

    @Override
    public void downloadAndImport() {

        log.info("[InstrumentDownload] Started.");

        try (InputStream stream =
                     client.downloadInstrumentMaster()) {

            importService.importInstruments(stream);

        } catch (IOException ex) {

            throw new InstrumentImportException("Download failed", ex);

        }

        log.info("[InstrumentDownload] Completed.");

    }

}