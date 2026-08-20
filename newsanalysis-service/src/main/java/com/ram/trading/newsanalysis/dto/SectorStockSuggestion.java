package com.ram.trading.newsanalysis.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SectorStockSuggestion {

    private String sector;

    private List<StockSuggestion> stocks;
}