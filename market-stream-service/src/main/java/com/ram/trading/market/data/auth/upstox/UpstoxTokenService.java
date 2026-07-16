package com.ram.trading.market.data.auth.upstox;

import com.ram.trading.market.data.client.BrokerAuthClient;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UpstoxTokenService {

    private final BrokerAuthClient brokerAuthClient;

    public String getAccessToken() {

        return brokerAuthClient.getAccessToken();

    }

}