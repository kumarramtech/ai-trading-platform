package com.ram.trading.newsanalysis.dto;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewsAnalysisRequest {

    private String symbol;

    private String headline;
}