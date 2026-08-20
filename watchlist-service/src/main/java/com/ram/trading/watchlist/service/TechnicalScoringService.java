package com.ram.trading.watchlist.service;

import com.ram.trading.watchlist.dto.TechnicalCandidate;
import com.ram.trading.watchlist.dto.TechnicalIndicatorResponse;

public interface TechnicalScoringService {

    TechnicalCandidate score(
            TechnicalIndicatorResponse indicator,
            String companyName);
}