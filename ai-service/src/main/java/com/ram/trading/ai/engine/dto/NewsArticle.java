package com.ram.trading.ai.engine.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class NewsArticle {

    private String title;
    private String description;
    private String publishedAt;
    private String source;
}