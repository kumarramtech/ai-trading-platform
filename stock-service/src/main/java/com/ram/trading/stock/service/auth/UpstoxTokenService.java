package com.ram.trading.stock.service.auth;

import com.ram.trading.stock.dto.UpstoxTokenResponse;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@Getter
public class UpstoxTokenService {

    private UpstoxTokenResponse token;

    public void saveToken(UpstoxTokenResponse token) {

        this.token = token;

        log.info("Upstox Access Token Stored Successfully.");
    }

    public String getAccessToken() {

        if (token == null) {
            throw new IllegalStateException("Upstox is not authenticated.");
        }

        return token.getAccessToken();
    }

    public boolean isAuthenticated() {
        return token != null;
    }
}