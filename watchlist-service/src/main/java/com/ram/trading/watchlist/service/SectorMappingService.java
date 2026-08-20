package com.ram.trading.watchlist.service;

import java.util.List;

public interface SectorMappingService {

    List<String> getSymbolsForSectors(List<String> sectors);

    boolean isSymbolInSector(
            String symbol,
            List<String> sectors
    );
}