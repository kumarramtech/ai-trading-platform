package com.ram.trading.stock.service.instument;

import com.ram.trading.stock.dto.InstrumentValidationResult;
import com.ram.trading.stock.entity.Instrument;

import java.util.List;

public interface InstrumentValidator {

    InstrumentValidationResult validate(List<Instrument> instruments);

}