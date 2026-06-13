package com.ram.trading.stock.service;

import reactor.core.publisher.Flux;

public class StockService {

        public Flux<String> getWatchList() {

            return Flux.just(
                    "TCS",
                    "INFY",
                    "WIPRO",
                    "HDFCBANK",
                    "ICICIBANK",
                    "RELIANCE",
                    "SBIN",
                    "LT");
        }

}
