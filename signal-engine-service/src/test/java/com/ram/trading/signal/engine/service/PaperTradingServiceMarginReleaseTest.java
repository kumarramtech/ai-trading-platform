package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.client.AIServiceClient;
import com.ram.trading.signal.engine.client.BalanceMarginClient;
import com.ram.trading.signal.engine.client.NotificationClient;
import com.ram.trading.signal.engine.client.StockServiceClient;
import com.ram.trading.signal.engine.client.interfac.PortfolioClient;
import com.ram.trading.signal.engine.contant.SignalStatus;
import com.ram.trading.signal.engine.dto.market.ExitReason;
import com.ram.trading.signal.engine.dto.market.Tick;
import com.ram.trading.signal.engine.entity.PaperTrade;
import com.ram.trading.signal.engine.exit.ExitDecision;
import com.ram.trading.signal.engine.repo.PaperTradeRepository;
import com.ram.trading.signal.engine.service.ai.TradingFunnelStatisticsService;
import com.ram.trading.signal.engine.service.charges.IntradayChargesCalculator;
import com.ram.trading.signal.engine.strategy.BasicTradingStrategy;
import com.ram.trading.signal.engine.util.TradingSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PaperTradingServiceMarginReleaseTest {

    private PaperTradeRepository repository;
    private BalanceMarginClient balanceMarginClient;
    private PaperTradingService service;

    @BeforeEach
    void setUp() {
        repository = mock(PaperTradeRepository.class);
        balanceMarginClient = mock(BalanceMarginClient.class);
        PortfolioClient portfolioClient = mock(PortfolioClient.class);
        TradingSignalService tradingSignalService = mock(TradingSignalService.class);
        NotificationClient notificationClient = mock(NotificationClient.class);

        when(repository.save(any(PaperTrade.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(balanceMarginClient.releaseMargin(anyDouble(), anyDouble())).thenReturn(Mono.empty());
        when(portfolioClient.closePosition(anyString(), anyInt())).thenReturn(Mono.empty());
        when(tradingSignalService.closeSignal(any(), any(), any(), any(), any())).thenReturn(Mono.empty());
        when(notificationClient.sendNotification(any())).thenReturn(Mono.empty());

        service = new PaperTradingService(
                repository,
                mock(AIServiceClient.class),
                mock(StockServiceClient.class),
                mock(BasicTradingStrategy.class),
                notificationClient,
                mock(SignalService.class),
                portfolioClient,
                mock(TradingSessionService.class),
                tradingSignalService,
                balanceMarginClient,
                mock(TradingFunnelStatisticsService.class),
                new IntradayChargesCalculator());
    }

    @Test
    void closingAPaperTradeReturnsItsMarginAndPnlToTheAccount() {
        when(repository.findById(1L)).thenReturn(Optional.of(openTrade()));

        StepVerifier.create(service.closeTrade(openTrade(), targetExit(), tickAt(110.0)))
                .verifyComplete();

        // 10 shares, 100 -> 110: gross +100 on Rs 10,000 reserved margin. The account
        // receives it net of round-trip charges, as the broker account would.
        double charges = new IntradayChargesCalculator().roundTrip("BUY", 100.0, 110.0, 10).getTotal();
        verify(balanceMarginClient).releaseMargin(10000.0, Math.round((100.0 - charges) * 100.0) / 100.0);
    }

    @Test
    void aTradeThatIsAlreadyClosedIsNotReleasedAgain() {
        PaperTrade alreadyClosed = openTrade();
        alreadyClosed.setStatus(SignalStatus.TARGET_HIT);
        when(repository.findById(1L)).thenReturn(Optional.of(alreadyClosed));

        StepVerifier.create(service.closeTrade(openTrade(), targetExit(), tickAt(110.0)))
                .verifyComplete();

        verify(balanceMarginClient, never()).releaseMargin(anyDouble(), anyDouble());
        verify(repository, never()).save(any(PaperTrade.class));
    }

    private static PaperTrade openTrade() {
        return PaperTrade.builder()
                .id(1L)
                .signalId(11L)
                .symbol("TCS")
                .signal("BUY")
                .entryPrice(100.0)
                .quantity(10)
                .requiredMargin(10000.0)
                .status(SignalStatus.OPEN)
                .build();
    }

    private static ExitDecision targetExit() {
        return ExitDecision.builder().exit(true).reason(ExitReason.TARGET).exitPrice(110.0).build();
    }

    private static Tick tickAt(double price) {
        return Tick.builder().symbol("TCS").lastTradedPrice(price).build();
    }
}
