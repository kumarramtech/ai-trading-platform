package com.ram.trading.margin.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class UpstoxMarginRequest {

    private List<MarginInstrumentRequest> instruments;
}