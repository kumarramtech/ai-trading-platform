package com.ram.trading.auth.service.upstox;

import com.ram.trading.auth.service.entity.BrokerSession;
import com.ram.trading.auth.service.notification.BrokerAuthNotifier;
import com.ram.trading.auth.service.service.BrokerSessionService;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UpstoxAuthServiceTest {

    private static final String CODE = "one-time-code";

    private final UpstoxClient client = mock(UpstoxClient.class);
    private final UpstoxTokenService tokens = mock(UpstoxTokenService.class);
    private final BrokerSessionService sessions = mock(BrokerSessionService.class);
    private final BrokerAuthNotifier notifier = mock(BrokerAuthNotifier.class);
    private final UpstoxAuthService service =
            new UpstoxAuthService(mock(UpstoxProperties.class), client, tokens, sessions, notifier);

    private static UpstoxTokenResponse token(String accessToken) {
        UpstoxTokenResponse token = new UpstoxTokenResponse();
        token.setAccessToken(accessToken);
        return token;
    }

    @Test
    void successSavesTheTokenThenNotifiesWithItsExpiry() {
        LocalDateTime expiry = LocalDateTime.of(2026, 11, 10, 3, 30);
        BrokerSession session = new BrokerSession();
        session.setExpiresAt(expiry);
        when(client.exchangeCode(CODE)).thenReturn(Mono.just(token("abc")));
        when(sessions.getBrokerSession("UPSTOX")).thenReturn(session);

        StepVerifier.create(service.authenticate(CODE)).expectNext("Authentication Successful").verifyComplete();

        verify(tokens).saveToken(any());
        verify(notifier).loginSucceeded(expiry);
        verify(notifier, never()).loginFailed(anyString());
    }

    @Test
    void anUpstoxErrorNotifiesWithoutTheCode() {
        when(client.exchangeCode(CODE)).thenReturn(Mono.error(new RuntimeException("invalid code " + CODE)));

        StepVerifier.create(service.authenticate(CODE)).verifyError();

        verify(notifier).loginFailed("invalid code ***");
        verify(notifier, never()).loginSucceeded(any());
    }

    @Test
    void aBlankTokenIsAFailureNotASuccess() {
        when(client.exchangeCode(CODE)).thenReturn(Mono.just(token(" ")));

        StepVerifier.create(service.authenticate(CODE)).verifyError();

        verify(tokens, never()).saveToken(any());
        verify(notifier).loginFailed(contains("no token"));
        verify(notifier, never()).loginSucceeded(any());
    }

    @Test
    void anEmptyAnswerIsAFailure() {
        when(client.exchangeCode(CODE)).thenReturn(Mono.empty());

        StepVerifier.create(service.authenticate(CODE)).verifyError();

        verify(notifier).loginFailed(contains("no token"));
    }

    @Test
    void aFailedSaveIsAFailure() {
        when(client.exchangeCode(CODE)).thenReturn(Mono.just(token("abc")));
        doThrow(new IllegalStateException("database down")).when(tokens).saveToken(any());

        StepVerifier.create(service.authenticate(CODE)).verifyError();

        verify(notifier).loginFailed(contains("database down"));
        verify(notifier, never()).loginSucceeded(any());
    }

    @Test
    void aProblemReadingTheExpiryDoesNotFailTheLogin() {
        when(client.exchangeCode(CODE)).thenReturn(Mono.just(token("abc")));
        when(sessions.getBrokerSession("UPSTOX")).thenThrow(new IllegalStateException("database down"));

        StepVerifier.create(service.authenticate(CODE)).expectNext("Authentication Successful").verifyComplete();

        verify(notifier).loginSucceeded(null);
    }

    @Test
    void aLongReasonIsCut() {
        String reason = UpstoxAuthService.reason(new RuntimeException("x".repeat(500)), CODE);

        assertEquals(303, reason.length());
    }
}
