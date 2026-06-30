package com.ram.trading.stock.constant;

public enum CandleInterval {

    MINUTE_1("minutes", "1"),
    MINUTE_5("minutes", "5"),
    MINUTE_15("minutes", "15"),
    DAY_1("days", "1");

    private final String unit;
    private final String value;

    CandleInterval(String unit, String value) {
        this.unit = unit;
        this.value = value;
    }

    public String toApiPath() {
        return unit + "/" + value;
    }
}