package com.ram.trading.margin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class UpstoxFundsResponse {

    private String status;

    private DataResponse data;

    @lombok.Data
    public static class DataResponse {

        @JsonProperty("available_to_trade")
        private AvailableToTrade availableToTrade;

        @JsonProperty("unavailable_to_trade")
        private UnavailableToTrade unavailableToTrade;
    }

    @lombok.Data
    public static class AvailableToTrade {

        private Double total;

        @JsonProperty("cash_available_to_trade")
        private CashAvailableToTrade cashAvailableToTrade;

        @JsonProperty("pledge_available_to_trade")
        private PledgeAvailableToTrade pledgeAvailableToTrade;
    }

    @lombok.Data
    public static class CashAvailableToTrade {

        private Double total;
    }

    @lombok.Data
    public static class PledgeAvailableToTrade {

        private Double total;
    }

    @lombok.Data
    public static class UnavailableToTrade {

        @JsonProperty("cash_unavailable_to_trade")
        private CashUnavailableToTrade cashUnavailableToTrade;

        @JsonProperty("pledge_unavailable_to_trade")
        private PledgeUnavailableToTrade pledgeUnavailableToTrade;
    }

    @lombok.Data
    public static class CashUnavailableToTrade {

        @JsonProperty("unsettled_profit")
        private UnsettledProfit unsettledProfit;
    }

    @lombok.Data
    public static class UnsettledProfit {

        @JsonProperty("todays_profit")
        private Double todaysProfit;

        @JsonProperty("previous_days")
        private Double previousDays;
    }

    @lombok.Data
    public static class PledgeUnavailableToTrade {

        private Double equity;

        @JsonProperty("mutual_funds")
        private Double mutualFunds;
    }
}