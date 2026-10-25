package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.client.StockServiceClient;
import com.ram.trading.signal.engine.client.TradeExecutionClient;
import com.ram.trading.signal.engine.client.TradeExecutionClient.TradeExecutionPosition;
import com.ram.trading.signal.engine.repo.PaperTradeRepository;
import com.ram.trading.signal.engine.util.TradingSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Signal Engine owns market-close timing, so it must square off live positions too. */
class MarketCloseServiceLiveTest {

    private PaperTradeRepository repository;
    private TradeExecutionClient tradeExecutionClient;
    private TradingSessionService tradingSessionService;
    private MarketCloseService service;

    @BeforeEach
    void setUp() {
        repository = mock(PaperTradeRepository.class);
        tradeExecutionClient = mock(TradeExecutionClient.class);
        tradingSessionService = mock(TradingSessionService.class);
        service = new MarketCloseService(repository, mock(StockServiceClient.class),
                mock(PaperTradingService.class), tradeExecutionClient, tradingSessionService);
        when(tradingSessionService.shouldForceCloseTrades()).thenReturn(true);
        when(repository.findByStatus(any())).thenReturn(List.of());
    }

    private static TradeExecutionPosition position(long signalId, String status, int remaining) {
        return new TradeExecutionPosition(
                signalId, signalId, "TCS", "NSE_EQ|TEST", "BUY", remaining, remaining, new BigDecimal("100"),
                null, null, null, status, null, null, null, 0, remaining, null, null,
                null, null, null, null, null, null, false);
    }

    @Test
    void liveModeExitsEveryOpenLivePositionAtMarket() {
        ReflectionTestUtils.setField(service, "executionTradeMode", "live");
        when(tradeExecutionClient.getOpenTrades()).thenReturn(Mono.just(List.of(
                position(1L, "OPEN", 3), position(2L, "EXIT_PENDING", 2))));
        when(tradeExecutionClient.exitTrade(any())).thenReturn(Mono.just(
                new TradeExecutionClient.TradeExecutionResponse(true, 1L, "TCS", "SELL", 3,
                        null, null, "exit-1", "CLOSED", "closed", 3, null)));

        service.closeOpenPositions().block();

        verify(tradeExecutionClient).exitTrade(new TradeExecutionClient.TradeExitRequest(1L, 3, "MARKET"));
        verify(tradeExecutionClient, times(1)).exitTrade(any());
    }

    @Test
    void paperModeNeverCallsTradeExecution() {
        ReflectionTestUtils.setField(service, "executionTradeMode", "paper");

        service.closeOpenPositions().block();

        verifyNoInteractions(tradeExecutionClient);
    }
}
