package com.ram.trading.trade.execution.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class UpstoxOrderHistoryResponse {

    private String status;

    private List<OrderData> data;

    @Data
    public static class OrderData {

        private String status;

        private String tag;

        private Integer quantity;

        @JsonProperty("filled_quantity")
        private Integer filledQuantity;

        @JsonProperty("pending_quantity")
        private Integer pendingQuantity;

        @JsonProperty("average_price")
        private BigDecimal averagePrice;

        @JsonProperty("order_id")
        private String orderId;

        @JsonProperty("exchange_order_id")
        private String exchangeOrderId;

        @JsonProperty("status_message")
        private String statusMessage;

        @JsonProperty("status_message_raw")
        private String statusMessageRaw;

        @JsonProperty("instrument_token")
        private String instrumentToken;

        @JsonProperty("transaction_type")
        private String transactionType;

        @JsonProperty("order_type")
        private String orderType;

        private String product;

        private String validity;

        private BigDecimal price;

        @JsonProperty("trigger_price")
        private BigDecimal triggerPrice;

        @JsonProperty("order_timestamp")
        private String orderTimestamp;

        @JsonProperty("exchange_timestamp")
        private String exchangeTimestamp;
    }
}
