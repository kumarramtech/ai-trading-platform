package com.ram.trading.stock.dto;

import com.ram.trading.stock.entity.Instrument;
import lombok.*;

import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InstrumentValidationResult {

    private int totalRecords;

    private int validRecords;

    private int invalidRecords;

    private List<Instrument> validInstruments;

    private List<String> errors;

}