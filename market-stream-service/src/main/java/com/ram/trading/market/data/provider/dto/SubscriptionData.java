package com.ram.trading.market.data.provider.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Builder
public class SubscriptionData {

    private String mode;

    private List<String> instrumentKeys;

}