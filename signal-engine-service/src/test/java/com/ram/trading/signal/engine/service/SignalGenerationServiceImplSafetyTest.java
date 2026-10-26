package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.client.TradeExecutionClient;
import com.ram.trading.signal.engine.contant.SignalType;
import com.ram.trading.signal.engine.dto.StockResponse;
import com.ram.trading.signal.engine.dto.TechnicalIndicatorResponse;
import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.dto.EntryQualityResult;
import com.ram.trading.signal.engine.entity.TradingSignalEntity;
import com.ram.trading.signal.engine.risk.PositionSizingResult;
import com.ram.trading.signal.engine.risk.PositionSizingService;
import com.ram.trading.signal.engine.dto.premarket.JudasSwingResult;
import com.ram.trading.signal.engine.dto.premarket.MinuteCandle;
import com.ram.trading.signal.engine.dto.premarket.ORBResult;
import com.ram.trading.signal.engine.dto.premarket.OpeningRange;
import com.ram.trading.signal.engine.service.impl.SignalGenerationServiceImpl;
import com.ram.trading.signal.engine.service.interfac.MarketDataProvider;
import com.ram.trading.signal.engine.util.TradingSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SignalGenerationServiceImplSafetyTest {

    @Test
    void genericScannerPathReturnsHoldWhenStrategyEntryWindowIsClosed() throws Exception {
        Harness harness = newHarness();

        when(harness.sessionService.canCreateStrategyEntry()).thenReturn(false);

        TradingSignal result = harness.service.generateSignal("TCS").block();

        assertNotNull(result);
        assertEquals("TCS", result.getSymbol());
        assertEquals(SignalType.HOLD.name(), result.getSignal());
        verify(harness.sessionService).canCreateStrategyEntry();
        verifyNoInteractions(harness.mocksExcept(harness.sessionService));
    }

    @Test
    void genericScannerPathRemainsAnalysisOnlyEvenWhenEntryWindowIsOpen() throws Exception {
        Harness harness = newHarness();
        MarketDataProvider marketDataProvider = harness.mock(MarketDataProvider.class);
        com.ram.trading.signal.engine.indicator.service.TechnicalIndicatorService indicatorService =
                harness.mock(com.ram.trading.signal.engine.indicator.service.TechnicalIndicatorService.class);
        when(harness.sessionService.canCreateStrategyEntry()).thenReturn(true);
        when(marketDataProvider.getStockPrice("TCS")).thenReturn(Mono.just(
                StockResponse.builder().symbol("TCS").price(100.0).build()));
        when(indicatorService.calculate("TCS")).thenReturn(Mono.just(
                new TechnicalIndicatorResponse()));

        TradingSignal result = harness.service.generateSignal("TCS").block();

        assertNotNull(result);
        assertEquals("TCS", result.getSymbol());
        assertEquals(SignalType.HOLD.name(), result.getSignal());
        verify(marketDataProvider).getStockPrice("TCS");
        verify(indicatorService).calculate("TCS");

        verifyNoInteractions(harness.mock(TradingSignalService.class));
        verifyNoInteractions(harness.mock(PaperTradingService.class));
        verifyNoInteractions(harness.mock(TradeExecutionClient.class));
        verifyNoInteractions(harness.mock(OpportunityService.class));
    }

    @Test
    void duplicateJudasConfirmationIsNotReturnedAsASecondEntrySetup() throws Exception {
        Harness harness = newHarness();
        OpeningRangeService openingRangeService = harness.mock(OpeningRangeService.class);
        JudasSwingDetector judasDetector = harness.mock(JudasSwingDetector.class);
        ORBValidator orbValidator = harness.mock(ORBValidator.class);

        LocalDateTime confirmation = LocalDateTime.of(LocalDate.now(), LocalTime.of(10, 15));
        OpeningRange openingRange = OpeningRange.builder()
                .symbol("TCS")
                .complete(true)
                .high(101.0)
                .low(99.0)
                .build();
        when(openingRangeService.get("TCS")).thenReturn(openingRange);
        when(judasDetector.detect(openingRange, LocalTime.of(10, 15))).thenReturn(JudasSwingResult.builder()
                .symbol("TCS")
                .detected(true)
                .direction("BEARISH_REVERSAL")
                .sweepSide("HIGH_SWEEP")
                .confirmationTime(confirmation)
                .build());
        when(orbValidator.validate(openingRange, LocalTime.of(10, 15))).thenReturn(ORBResult.builder()
                .symbol("TCS").valid(false).build());

        MinuteCandle candle = MinuteCandle.builder()
                .symbol("TCS")
                .minute(LocalDateTime.of(LocalDate.now(), LocalTime.of(10, 15)))
                .open(100.0).high(101.0).low(99.0).close(100.0).volume(1000L)
                .build();

        Method findEntrySetup = SignalGenerationServiceImpl.class
                .getDeclaredMethod("findEntrySetup", MinuteCandle.class);
        findEntrySetup.setAccessible(true);

        Object first = findEntrySetup.invoke(harness.service, candle);
        Object second = findEntrySetup.invoke(harness.service, candle);

        assertNotNull(first, "The first confirmed Judas setup should be accepted");
        assertNull(second, "The same Judas confirmation must not be accepted twice");
        verify(judasDetector, times(2)).detect(openingRange, LocalTime.of(10, 15));
    }

    @Test
    void liveModeWithLiveExecutionDisabledFailsClosedWithoutPaperOrBrokerExecution() throws Exception {
        Harness harness = newHarness();
        ReflectionTestUtils.setField(harness.service, "executionTradeMode", "live");
        ReflectionTestUtils.setField(harness.service, "liveExecutionEnabled", false);

        MarketDataProvider marketDataProvider = harness.mock(MarketDataProvider.class);
        EntryQualityService entryQualityService = harness.mock(EntryQualityService.class);
        PositionSizingService positionSizingService = harness.mock(PositionSizingService.class);
        TradingSignalService tradingSignalService = harness.mock(TradingSignalService.class);
        TradeExecutionClient tradeExecutionClient = harness.mock(TradeExecutionClient.class);
        PaperTradingService paperTradingService = harness.mock(PaperTradingService.class);

        when(harness.sessionService.canCreateStrategyEntry()).thenReturn(true, true, true);
        when(marketDataProvider.getStockPrice("TCS")).thenReturn(Mono.just(
                StockResponse.builder().symbol("TCS").price(100.0).build()));
        when(entryQualityService.evaluate(anyString(), any(), any(), any(), any()))
                .thenReturn(EntryQualityResult.builder()
                        .symbol("TCS").direction("BUY").signalPrice(100.0)
                        .freshMarketPrice(100.0).status("NORMAL").reason("test").build());
        when(positionSizingService.calculate(any(TradingSignal.class))).thenReturn(Mono.just(
                PositionSizingResult.builder()
                        .valid(true).entryPrice(100.0).stopLoss(99.0)
                        .marginBasedQuantity(10).targetMargin(1000.0)
                        .requiredMargin(1000.0).tradeValue(1000.0)
                        .leverage(1.0).expectedRisk(10.0).maxRiskPerTrade(250.0)
                        .build()));
        when(tradingSignalService.save(any(TradingSignal.class))).thenReturn(
                TradingSignalEntity.builder().id(123L).symbol("TCS").signal("BUY").build());

        TradingSignal signal = TradingSignal.builder()
                .symbol("TCS").signal("BUY")
                .entryPrice(100.0).stopLoss(99.0).targetPrice(102.0)
                .confidence(90).build();

        Method postProcess = SignalGenerationServiceImpl.class.getDeclaredMethod(
                "postProcessSignal", TradingSignal.class,
                TechnicalIndicatorResponse.class, Double.class);
        postProcess.setAccessible(true);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> {
                    @SuppressWarnings("unchecked")
                    Mono<TradingSignal> result = (Mono<TradingSignal>) postProcess.invoke(
                            harness.service, signal, new TechnicalIndicatorResponse(), 100.0);
                    result.block();
                });

        assertTrue(exception.getMessage().contains("LIVE execution is disabled"));

        verifyNoInteractions(tradeExecutionClient);
        verifyNoInteractions(paperTradingService);
    }

    @Test
    void paperModeCreatesPaperTradeWithoutCallingBrokerExecutionClient() throws Exception {
        Harness harness = newHarness();
        ReflectionTestUtils.setField(harness.service, "executionTradeMode", "paper");
        ReflectionTestUtils.setField(harness.service, "liveExecutionEnabled", false);

        MarketDataProvider marketDataProvider = harness.mock(MarketDataProvider.class);
        EntryQualityService entryQualityService = harness.mock(EntryQualityService.class);
        PositionSizingService positionSizingService = harness.mock(PositionSizingService.class);
        TradingSignalService tradingSignalService = harness.mock(TradingSignalService.class);
        TradeExecutionClient tradeExecutionClient = harness.mock(TradeExecutionClient.class);
        PaperTradingService paperTradingService = harness.mock(PaperTradingService.class);

        when(harness.sessionService.canCreateStrategyEntry()).thenReturn(true, true, true);
        when(marketDataProvider.getStockPrice("TCS")).thenReturn(Mono.just(
                StockResponse.builder().symbol("TCS").price(100.0).build()));
        when(entryQualityService.evaluate(anyString(), any(), any(), any(), any()))
                .thenReturn(EntryQualityResult.builder()
                        .symbol("TCS").direction("BUY").signalPrice(100.0)
                        .freshMarketPrice(100.0).status("NORMAL").reason("test").build());
        when(positionSizingService.calculate(any(TradingSignal.class))).thenReturn(Mono.just(
                PositionSizingResult.builder()
                        .valid(true).entryPrice(100.0).stopLoss(99.0)
                        .marginBasedQuantity(10).targetMargin(1000.0)
                        .requiredMargin(1000.0).tradeValue(1000.0)
                        .leverage(1.0).expectedRisk(10.0).maxRiskPerTrade(250.0)
                        .build()));
        TradingSignalEntity entity = TradingSignalEntity.builder()
                .id(124L).symbol("TCS").signal("BUY").build();
        when(tradingSignalService.save(any(TradingSignal.class))).thenReturn(entity);

        TradingSignal signal = TradingSignal.builder()
                .symbol("TCS").signal("BUY")
                .entryPrice(100.0).stopLoss(99.0).targetPrice(102.0)
                .confidence(90).build();
        TechnicalIndicatorResponse indicator = new TechnicalIndicatorResponse();

        Method postProcess = SignalGenerationServiceImpl.class.getDeclaredMethod(
                "postProcessSignal", TradingSignal.class,
                TechnicalIndicatorResponse.class, Double.class);
        postProcess.setAccessible(true);

        @SuppressWarnings("unchecked")
        Mono<TradingSignal> result = (Mono<TradingSignal>) postProcess.invoke(
                harness.service, signal, indicator, 100.0);

        TradingSignal returned = result.block();

        assertNotNull(returned);
        assertEquals("BUY", returned.getSignal());
        verify(paperTradingService).createTrade(eq(entity), eq(indicator),
                // The service forwards the validated sizing result to paper execution.
                argThat(sizing -> sizing != null
                        && sizing.isValid() && sizing.getMarginBasedQuantity() == 10),
                // and the fresh market price, which the paper fill is based on (as a live MARKET order).
                eq(100.0));
        verifyNoInteractions(tradeExecutionClient);
    }

    @Test
    void invalidExecutionModeIsRejectedByConfigurationValidation() throws Exception {
        Harness harness = newHarness();
        ReflectionTestUtils.setField(harness.service, "executionTradeMode", "automatic");

        Method validate = SignalGenerationServiceImpl.class
                .getDeclaredMethod("validateExecutionTradeMode");
        validate.setAccessible(true);

        java.lang.reflect.InvocationTargetException exception = assertThrows(
                java.lang.reflect.InvocationTargetException.class,
                () -> validate.invoke(harness.service));

        assertInstanceOf(IllegalStateException.class, exception.getCause());
        assertTrue(exception.getCause().getMessage().contains("Invalid execution.trade"));
    }

    private Harness newHarness() throws Exception {
        Constructor<?> constructor = SignalGenerationServiceImpl.class.getDeclaredConstructors()[0];
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        Object[] dependencies = new Object[parameterTypes.length];
        Map<Class<?>, Object> mocks = new HashMap<>();
        List<Object> allMocks = new ArrayList<>();
        TradingSessionService sessionService = mock(TradingSessionService.class);

        for (int i = 0; i < parameterTypes.length; i++) {
            if (parameterTypes[i].equals(TradingSessionService.class)) {
                dependencies[i] = sessionService;
                mocks.put(parameterTypes[i], sessionService);
            } else {
                Object dependency = mock(parameterTypes[i]);
                dependencies[i] = dependency;
                mocks.put(parameterTypes[i], dependency);
                allMocks.add(dependency);
            }
        }

        SignalGenerationServiceImpl service =
                (SignalGenerationServiceImpl) constructor.newInstance(dependencies);
        return new Harness(service, sessionService, mocks, allMocks);
    }

    private static final class Harness {
        private final SignalGenerationServiceImpl service;
        private final TradingSessionService sessionService;
        private final Map<Class<?>, Object> mocks;
        private final List<Object> allMocks;

        private Harness(SignalGenerationServiceImpl service,
                        TradingSessionService sessionService,
                        Map<Class<?>, Object> mocks,
                        List<Object> allMocks) {
            this.service = service;
            this.sessionService = sessionService;
            this.mocks = mocks;
            this.allMocks = allMocks;
        }

        @SuppressWarnings("unchecked")
        private <T> T mock(Class<T> type) {
            return (T) mocks.get(type);
        }

        private Object[] mocksExcept(Object excluded) {
            return allMocks.stream().filter(value -> value != excluded).toArray();
        }
    }
}
