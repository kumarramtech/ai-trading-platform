package com.ram.trading.margin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class UpstoxMarginResponse {

    private String status;

    private MarginData data;

    @Data
    public static class MarginData {

        private List<InstrumentMargin> margins;

        @JsonProperty("required_margin")
        private BigDecimal requiredMargin;

        @JsonProperty("final_margin")
        private BigDecimal finalMargin;
    }

    @Data
    public static class InstrumentMargin {

        @JsonProperty("span_margin")
        private BigDecimal spanMargin;

        @JsonProperty("exposure_margin")
        private BigDecimal exposureMargin;

        @JsonProperty("equity_margin")
        private BigDecimal equityMargin;

        @JsonProperty("net_buy_premium")
        private BigDecimal netBuyPremium;

        @JsonProperty("additional_margin")
        private BigDecimal additionalMargin;

        @JsonProperty("total_margin")
        private BigDecimal totalMargin;

        @JsonProperty("tender_margin")
        private BigDecimal tenderMargin;
    }
}