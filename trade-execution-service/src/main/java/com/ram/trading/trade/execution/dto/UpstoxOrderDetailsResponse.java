package com.ram.trading.trade.execution.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class UpstoxOrderDetailsResponse {

    private String status;

    private DataResponse data;

    @Data
    public static class DataResponse {

        @JsonProperty("order_id")
        private String orderId;

        private String status;

        @JsonProperty("status_message")
        private String statusMessage;

        @JsonProperty("status_message_raw")
        private String statusMessageRaw;

        private String tag;

        private Integer quantity;

        @JsonProperty("filled_quantity")
        private Integer filledQuantity;

        @JsonProperty("pending_quantity")
        private Integer pendingQuantity;

        @JsonProperty("average_price")
        private BigDecimal averagePrice;

        private BigDecimal price;

        @JsonProperty("trigger_price")
        private BigDecimal triggerPrice;

        @JsonProperty("exchange_order_id")
        private String exchangeOrderId;

        @JsonProperty("order_timestamp")
        private String orderTimestamp;

        @JsonProperty("exchange_timestamp")
        private String exchangeTimestamp;

        @JsonProperty("instrument_token")
        private String instrumentToken;

        @JsonProperty("transaction_type")
        private String transactionType;

        @JsonProperty("order_type")
        private String orderType;

        private String product;

        private String validity;

        @JsonProperty("disclosed_quantity")
        private Integer disclosedQuantity;
    }
}
