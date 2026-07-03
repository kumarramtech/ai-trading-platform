package com.ram.trading.ai.engine.dto.news;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewsAnalysis {

    private String sentiment;

    private String summary;

    private String impact;

}