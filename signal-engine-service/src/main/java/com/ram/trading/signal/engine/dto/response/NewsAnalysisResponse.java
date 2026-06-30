package com.ram.trading.signal.engine.dto.response;

import lombok.Data;

@Data
public class NewsAnalysisResponse {

    private String symbol;
    private String sentiment;
    private Integer score;
    private String summary;
}