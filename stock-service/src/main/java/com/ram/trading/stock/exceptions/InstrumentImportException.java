package com.ram.trading.stock.exceptions;

public class InstrumentImportException extends RuntimeException {

    public InstrumentImportException(String message, Throwable cause) {
        super(message, cause);
    }
}