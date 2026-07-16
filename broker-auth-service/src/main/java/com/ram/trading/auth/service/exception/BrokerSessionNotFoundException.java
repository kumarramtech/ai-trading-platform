package com.ram.trading.auth.service.exception;

public class BrokerSessionNotFoundException extends RuntimeException {

    public BrokerSessionNotFoundException(String broker) {
        super("Broker session not found for broker : " + broker);
    }

}