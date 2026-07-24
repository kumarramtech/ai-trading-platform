package com.ram.trading.signal.engine.audit.dto;

import com.ram.trading.signal.engine.contant.SignalType;
import lombok.*;

import java.time.LocalDateTime;

@Builder
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class TradingAuditReport {

    String symbol;

    SignalType emaSignal;
    SignalType macdSignal;
    SignalType rsiSignal;

    SignalType finalSignal;

    Integer confidence;

    boolean engineeringFilterPassed;

    String rejectionReason;

    Double currentPrice;
    Double ema20;
    Double ema50;
    Double sma20;
    Double sma50;
    Double macd;
    Double signalLine;
    Double rsi;
    LocalDateTime scanTime;
}
