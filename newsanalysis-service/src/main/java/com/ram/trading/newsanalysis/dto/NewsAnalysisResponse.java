package com.ram.trading.newsanalysis.dto;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewsAnalysisResponse {

    private String symbol;

    private String sentiment;

    private Integer score;

    private String summary;
}