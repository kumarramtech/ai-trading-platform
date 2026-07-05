package com.ram.trading.stock.mapper;

import com.ram.trading.stock.bootstrap.dto.HistoricalPriceResponse;
import com.ram.trading.stock.bootstrap.entity.HistoricalPrice;
import org.springframework.stereotype.Component;

@Component
public class HistoricalPriceMapper {

    public static HistoricalPriceResponse toResponse(
            HistoricalPrice entity) {

        return HistoricalPriceResponse.builder()
                .symbol(entity.getSymbol())
                .tradeDate(entity.getTradeDate())
                .openPrice(entity.getOpenPrice())
                .highPrice(entity.getHighPrice())
                .lowPrice(entity.getLowPrice())
                .closePrice(entity.getClosePrice())
                .volume(entity.getVolume())
                .openInterest(entity.getOpenInterest())
                .build();

    }

}