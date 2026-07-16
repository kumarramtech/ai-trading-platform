package com.ram.trading.auth.service.upstox;

import com.ram.trading.auth.service.constant.BrokerConstants;
import com.ram.trading.auth.service.entity.BrokerSession;
import com.ram.trading.auth.service.service.BrokerSessionService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class UpstoxTokenService {

    private UpstoxTokenResponse token;

    private final BrokerSessionService brokerSessionService;

    public void saveToken(UpstoxTokenResponse token) {

        this.token = token;

        brokerSessionService.saveBrokerSession(
                BrokerConstants.UPSTOX,
                token);

        log.info("Upstox Access Token Stored Successfully.");
    }


    public boolean isAuthenticated() {
        return token != null;
    }
}