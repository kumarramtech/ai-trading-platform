package com.ram.trading.market.data.provider.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class SubscriptionRequest {

    private String guid;

    private String method;

    private SubscriptionData data;

}