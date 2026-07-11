package com.ram.trading.market.data.dto;

import lombok.*;

@Builder
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class MarketInstrument{

        private String symbol;

        private String exchange;

        private String instrumentKey;

        private Double lastPrice;

        private boolean subscribed;


}