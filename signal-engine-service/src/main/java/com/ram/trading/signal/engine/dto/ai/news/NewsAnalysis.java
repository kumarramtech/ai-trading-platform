package com.ram.trading.signal.engine.dto.ai.news;

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