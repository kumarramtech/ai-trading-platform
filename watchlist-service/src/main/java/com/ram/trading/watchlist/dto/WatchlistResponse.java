package com.ram.trading.watchlist.dto;

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
public class WatchlistResponse {

    private Integer totalSymbols;

    private Integer technicalCandidates;

    private List<TechnicalCandidate> candidates;
}