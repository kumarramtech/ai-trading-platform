package com.ram.trading.stock.service.instument;

import java.io.IOException;
import java.io.InputStream;

public interface InstrumentImportService {

    void importInstruments(InputStream inputStream) throws IOException;

}