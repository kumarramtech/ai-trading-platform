package com.ram.trading.auth.service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "broker_session")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrokerSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String broker;

    @Column(name = "access_token", length = 5000)
    private String accessToken;

    @Column(name = "refresh_token", length = 5000)
    private String refreshToken;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "token_type")
    private String tokenType;
}