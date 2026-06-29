package com.ram.trading.stock.service.instument;

import com.ram.trading.stock.entity.Instrument;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public interface InstrumentCsvParser {

    List<Instrument> parse(InputStream inputStream) throws IOException;

}