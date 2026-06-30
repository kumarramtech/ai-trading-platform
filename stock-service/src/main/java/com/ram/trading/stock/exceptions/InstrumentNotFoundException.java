package com.ram.trading.stock.exceptions;

public class InstrumentNotFoundException extends RuntimeException {

    public InstrumentNotFoundException(String tradingSymbol) {

        super("Active instrument not found for trading symbol : " + tradingSymbol);

    }

}