package com.ram.trading.signal.engine.service.charges;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IntradayChargesCalculatorTest {

    private final IntradayChargesCalculator calculator = new IntradayChargesCalculator();

    @Test
    void nbccShortOf2026_10_26CostsAboutSixtyFiveRupees() {
        // SELL 737 @ 67.79, cover @ 67.76: ~Rs 50k each side.
        IntradayChargesCalculator.Charges charges = calculator.roundTrip("SELL", 67.79, 67.76, 737);

        assertEquals(40.0, charges.getBrokerage(), 0.001);   // Rs 20 cap on both orders
        assertEquals(12.49, charges.getStt(), 0.01);         // 0.025% of the sell (entry) value
        assertEquals(1.50, charges.getStampDuty(), 0.01);    // 0.003% of the buy (cover) value
        assertEquals(2.97, charges.getExchangeTxn(), 0.01);
        assertEquals(7.75, charges.getGst(), 0.01);         // 18% of brokerage + exchange + SEBI
        assertEquals(64.80, charges.getTotal(), 0.05);
    }

    @Test
    void smallOrdersPayPercentageBrokerageBelowTheCap() {
        // Rs 1,000 order: 0.1% = Rs 1 < Rs 20 cap.
        IntradayChargesCalculator.Charges charges = calculator.roundTrip("BUY", 100.0, 100.0, 10);

        assertEquals(2.0, charges.getBrokerage(), 0.001);
        assertEquals(0.25, charges.getStt(), 0.001);
        assertEquals(0.03, charges.getStampDuty(), 0.001);
    }

    @Test
    void invalidInputsCostNothing() {
        assertEquals(0.0, calculator.roundTrip("BUY", 100.0, 101.0, 0).getTotal());
    }
}
