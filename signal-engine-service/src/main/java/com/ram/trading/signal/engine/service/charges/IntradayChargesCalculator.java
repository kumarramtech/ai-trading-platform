package com.ram.trading.signal.engine.service.charges;

import lombok.Builder;
import lombok.Value;
import org.springframework.stereotype.Component;

/**
 * Statutory and broker charges for one NSE intraday (MIS) equity round trip,
 * so paper P&L is what the same trade would have left in a live Upstox account.
 * Rates are configurable (trading.charges.*) and must be checked against an
 * Upstox contract note whenever the broker or exchange revises them.
 */
@Component
public class IntradayChargesCalculator {

    /** Brokerage per executed order: the lower of this % of the order value and the cap. */
    @org.springframework.beans.factory.annotation.Value("${trading.charges.brokerage-pct:0.1}")
    private double brokeragePct = 0.1;

    @org.springframework.beans.factory.annotation.Value("${trading.charges.brokerage-max-per-order:20}")
    private double brokerageMaxPerOrder = 20.0;

    /** Securities Transaction Tax, intraday: sell side only. */
    @org.springframework.beans.factory.annotation.Value("${trading.charges.stt-sell-pct:0.025}")
    private double sttSellPct = 0.025;

    /** NSE transaction charge, both sides. */
    @org.springframework.beans.factory.annotation.Value("${trading.charges.exchange-txn-pct:0.00297}")
    private double exchangeTxnPct = 0.00297;

    /** SEBI turnover fee (Rs 10 per crore), both sides. */
    @org.springframework.beans.factory.annotation.Value("${trading.charges.sebi-pct:0.0001}")
    private double sebiPct = 0.0001;

    /** Stamp duty, intraday: buy side only. */
    @org.springframework.beans.factory.annotation.Value("${trading.charges.stamp-buy-pct:0.003}")
    private double stampBuyPct = 0.003;

    /** GST on brokerage + exchange transaction charge + SEBI fee. */
    @org.springframework.beans.factory.annotation.Value("${trading.charges.gst-pct:18}")
    private double gstPct = 18.0;

    /**
     * @param direction BUY (buy first, sell to exit) or SELL (short first, buy to cover)
     */
    public Charges roundTrip(String direction, double entryPrice, double exitPrice, int quantity) {

        if (quantity <= 0 || entryPrice <= 0 || exitPrice <= 0) {
            return Charges.builder().build();
        }

        boolean shortTrade = "SELL".equalsIgnoreCase(direction);
        double entryValue = entryPrice * quantity;
        double exitValue = exitPrice * quantity;
        double buyValue = shortTrade ? exitValue : entryValue;
        double sellValue = shortTrade ? entryValue : exitValue;
        double turnover = buyValue + sellValue;

        double brokerage = brokerage(entryValue) + brokerage(exitValue);
        double stt = sellValue * sttSellPct / 100.0;
        double exchange = turnover * exchangeTxnPct / 100.0;
        double sebi = turnover * sebiPct / 100.0;
        double stamp = buyValue * stampBuyPct / 100.0;
        double gst = (brokerage + exchange + sebi) * gstPct / 100.0;

        return Charges.builder()
                .brokerage(round(brokerage))
                .stt(round(stt))
                .exchangeTxn(round(exchange))
                .sebiFee(round(sebi))
                .stampDuty(round(stamp))
                .gst(round(gst))
                .total(round(brokerage + stt + exchange + sebi + stamp + gst))
                .build();
    }

    private double brokerage(double orderValue) {
        return Math.min(orderValue * brokeragePct / 100.0, brokerageMaxPerOrder);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    @Value
    @Builder
    public static class Charges {
        @Builder.Default double brokerage = 0;
        @Builder.Default double stt = 0;
        @Builder.Default double exchangeTxn = 0;
        @Builder.Default double sebiFee = 0;
        @Builder.Default double stampDuty = 0;
        @Builder.Default double gst = 0;
        @Builder.Default double total = 0;
    }
}
