package com.ram.trading.market.data.auth.upstox;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "upstox")
@NoArgsConstructor
public class UpstoxProperties {

    private String clientId;
    private String clientSecret;
    private String redirectUri;
    private String authorizationUrl;
    private String tokenUrl;
    private String analyticsToken;
    private String baseUrl;
    private String defaultInstruments;
    private String marketFeedUrl;
    private String marketUrl;

}