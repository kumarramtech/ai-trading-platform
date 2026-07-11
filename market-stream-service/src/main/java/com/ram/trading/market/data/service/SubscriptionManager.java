package com.ram.trading.market.data.service;

import java.util.List;

public interface SubscriptionManager {

    void subscribe(List<String> instrumentKeys);

    void unsubscribe(List<String> instrumentKeys);

}