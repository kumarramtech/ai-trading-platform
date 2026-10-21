package com.ram.trading.trade.execution.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpstoxOrderResponse {

    private String status;

    private DataResponse data;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DataResponse {

        @JsonProperty("order_ids")
        private List<String> orderIds;
    }
}
