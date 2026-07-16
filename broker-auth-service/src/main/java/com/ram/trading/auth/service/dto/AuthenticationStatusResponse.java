package com.ram.trading.auth.service.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class AuthenticationStatusResponse {

    private boolean authenticated;

    private String broker;

    private LocalDateTime expiresAt;

}