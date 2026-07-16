package com.ram.trading.auth.service.service;

import com.ram.trading.auth.service.entity.BrokerSession;
import com.ram.trading.auth.service.upstox.UpstoxTokenResponse;

public interface BrokerSessionService {

    void saveBrokerSession(String broker,
                           UpstoxTokenResponse token);

    BrokerSession getBrokerSession(String broker);

    String getAccessToken(String broker);

}