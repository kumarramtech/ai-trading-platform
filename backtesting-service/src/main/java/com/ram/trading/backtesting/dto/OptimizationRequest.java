package com.ram.trading.backtesting.dto;

public record OptimizationRequest(

        String symbol,

        Double buyStart,
        Double buyEnd,
        Double buyStep,

        Double targetStart,
        Double targetEnd,
        Double targetStep,

        Double stopStart,
        Double stopEnd,
        Double stopStep

) {}