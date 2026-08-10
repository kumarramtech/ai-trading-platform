package com.ram.trading.newsanalysis.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Builder
public class NewsArticle {

    private String title;

    private String description;

    private String publishedAt;

    private String source;

    private String url;

    private List<String> tickers;
}