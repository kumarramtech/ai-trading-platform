package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.client.AIServiceClient;
import com.ram.trading.signal.engine.client.BalanceMarginClient;
import com.ram.trading.signal.engine.client.NotificationClient;
import com.ram.trading.signal.engine.client.StockServiceClient;
import com.ram.trading.signal.engine.client.interfac.PortfolioClient;
import com.ram.trading.signal.engine.contant.SignalStatus;
import com.ram.trading.signal.engine.contant.SignalType;
import com.ram.trading.signal.engine.dto.*;
import com.ram.trading.signal.engine.dto.ai.portfolio.OpenPositionContextResponse;
import com.ram.trading.signal.engine.dto.market.ExitReason;
import com.ram.trading.signal.engine.dto.market.Tick;
import com.ram.trading.signal.engine.dto.notification.NotificationChannel;
import com.ram.trading.signal.engine.dto.notification.NotificationRequest;
import com.ram.trading.signal.engine.dto.request.ClosedPositionDto;
import com.ram.trading.signal.engine.dto.request.OpenPositionDashboard;
import com.ram.trading.signal.engine.dto.request.OpportunityDashboard;
import com.ram.trading.signal.engine.dto.response.*;
import com.ram.trading.signal.engine.entity.PaperTrade;
import com.ram.trading.signal.engine.entity.TradingSignalEntity;
import com.ram.trading.signal.engine.exit.ExitDecision;
import com.ram.trading.signal.engine.repo.PaperTradeRepository;
import com.ram.trading.signal.engine.risk.PositionSizingResult;
import com.ram.trading.signal.engine.service.ai.TradingFunnelStatisticsService;
import com.ram.trading.signal.engine.service.charges.IntradayChargesCalculator;
import com.ram.trading.signal.engine.strategy.BasicTradingStrategy;
import com.ram.trading.signal.engine.util.TradeUtil;
import com.ram.trading.signal.engine.util.TradingSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaperTradingService {

    private final PaperTradeRepository repository;

    private final AIServiceClient aiServiceClient;

    private final StockServiceClient stockServiceClient;

    private final BasicTradingStrategy basicTradingStrategy;

    private final NotificationClient notificationClient;

    private final SignalService signalService;

    private final PortfolioClient portfolioClient;

    private final TradingSessionService tradingSessionService;

    private final TradingSignalService tradingSignalService;

    private final BalanceMarginClient balanceMarginClient;

    /** Paper trades currently being closed (guards against a double close). */
    private final java.util.Set<Long> closingTradeIds =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final TradingFunnelStatisticsService tradingFunnelStatisticsService;

    private final IntradayChargesCalculator chargesCalculator;

    /**
     * Adverse slippage per fill, in % of price. Live entries and exits are
     * MARKET orders (and the protective stop is SL-M), which fill across the
     * spread, so a paper fill at the exact LTP would flatter every trade.
     */
    @Value("${paper.trading.slippage-pct:0.03}")
    private double slippagePct;

    @Value("${trading.reentry.cooldown-minutes:15}")
    private long reentryCooldownMinutes;


    @Value("${trading.total-capital}")
    private double totalCapital;

    @Value("${trading.min-available-capital:1000}")
    private double minAvailableCapital;

    public synchronized void createTrade(
            TradingSignalEntity signal,
            TechnicalIndicatorResponse indicatorResponse,
            PositionSizingResult sizingResult) {
        createTrade(signal, indicatorResponse, sizingResult, null);
    }

    /**
     * @param marketPrice price at the moment of execution (what a live MARKET
     *                    order would fill against); the signal's entry price is
     *                    used when it is unavailable
     */
    public synchronized void createTrade(
            TradingSignalEntity signal,
            TechnicalIndicatorResponse indicatorResponse,
            PositionSizingResult sizingResult,
            Double marketPrice) {

        /*
         * ============================================================
         * STEP 1 : TRADING SESSION CHECK
         * ============================================================
         */
        if (!tradingSessionService.canCreateTrade()) {

            log.info(
                    "Trading session closed for new entries. Symbol={}",
                    signal.getSymbol());

            return;
        }

        log.info("=================================");
        log.info("PAPER TRADE CREATION STARTED");
        log.info("Symbol      : {}", signal.getSymbol());
        log.info("Signal      : {}", signal.getSignal());
        log.info("Entry Price : {}", signal.getEntryPrice());
        log.info("=================================");

        /*
         * ============================================================
         * STEP 2 : BASIC VALIDATION
         * ============================================================
         */
        if (indicatorResponse == null) {

            throw new IllegalArgumentException(
                    "Technical indicators not found for "
                            + signal.getSymbol());
        }

        /*
         * ============================================================
         * STEP 3 : ONLY BUY / SELL CAN CREATE A TRADE
         * ============================================================
         */
        if (!SignalType.BUY.name().equalsIgnoreCase(signal.getSignal())
                && !SignalType.SELL.name()
                .equalsIgnoreCase(signal.getSignal())) {

            log.info(
                    "Skipping Non-Tradable Signal | Symbol={} | Signal={}",
                    signal.getSymbol(),
                    signal.getSignal());

            return;
        }

        /*
         * ============================================================
         * STEP 4 : VALIDATE ENTRY PRICE
         * ============================================================
         */
        if (signal.getEntryPrice() <= 0) {

            log.warn(
                    "Invalid Entry Price | Symbol={} | EntryPrice={}",
                    signal.getSymbol(),
                    signal.getEntryPrice());

            return;
        }

        /*
         * ============================================================
         * STEP 5 : APPROVED MARGIN-BASED POSITION SIZE
         * ============================================================
         *
         * PositionSizingService has already calculated the quantity
         * using the broker's actual margin requirement and the
         * configured ₹10,000 target margin.
         *
         * PaperTradingService must not recalculate or reduce the
         * quantity based on the old ₹250 risk-sizing rule.
         */
        if (sizingResult == null || !sizingResult.isValid()) {

            log.info(
                    "Position sizing is invalid. Skipping trade | Symbol={} | Reason={}",
                    signal.getSymbol(),
                    sizingResult != null
                            ? sizingResult.getRejectionReason()
                            : "Sizing result is null");

            return;
        }

        int quantity = sizingResult.getMarginBasedQuantity();

        if (quantity <= 0) {
            log.info(
                    "Approved quantity is zero. Skipping trade | Symbol={}",
                    signal.getSymbol());
            return;
        }

        double requiredMargin = sizingResult.getRequiredMargin();
        double leverage = sizingResult.getLeverage();

        /*
         * ============================================================
         * STEP 6 : CALCULATE CURRENT AVAILABLE CAPITAL
         * ============================================================
         */
        /*
         * Capital is owned by Balance Margin Service (paper account: reserved
         * and released per trade, realized P&L applied, reset daily). Its
         * balance came back with the sizing margin call; the local
         * total-capital estimate is only a fallback if it is missing.
         */
        boolean accountBalanceAvailable =
                sizingResult.getAvailableBalance() != null;

        double availableCapital = accountBalanceAvailable
                ? sizingResult.getAvailableBalance()
                : getAvailableCapital();

        double investmentAmount =
                quantity * signal.getEntryPrice();

        log.info("======================================");
        log.info("CAPITAL CHECK");
        log.info("Capital Source      : {}", accountBalanceAvailable
                ? "BALANCE_MARGIN_ACCOUNT" : "LOCAL_ESTIMATE");
        log.info("Available Capital   : {}", availableCapital);
        log.info("Required Margin     : {}", requiredMargin);
        log.info("Minimum Capital     : {}", minAvailableCapital);
        log.info("======================================");

        /*
         * ============================================================
         * STEP 7 : MINIMUM AVAILABLE CAPITAL GUARD
         * ============================================================
         */
        if (availableCapital < minAvailableCapital) {

            log.warn("======================================");
            log.warn("NEW TRADE CREATION STOPPED");
            log.warn("Symbol              : {}", signal.getSymbol());
            log.warn("Available Capital   : {}", availableCapital);
            log.warn("Minimum Required    : {}", minAvailableCapital);
            log.warn(
                    "Existing open trades will continue to be monitored.");
            log.warn("======================================");

            return;
        }

        if (availableCapital < requiredMargin) {

            log.warn(
                    "INSUFFICIENT PAPER MARGIN | Symbol={} | RequiredMargin={} | AvailableMargin={}",
                    signal.getSymbol(),
                    requiredMargin,
                    availableCapital);

            return;
        }

        /*
         * ============================================================
         * STEP 8 : PREVENT DUPLICATE OPEN TRADE
         * ============================================================
         */
        if (!validateTradeCreation(signal)) {
            return;
        }

        /*
         * ============================================================
         * STEP 10 : BUSINESS VALIDATION
         * ============================================================
         */
        log.debug(
                "Creating Trade => Entry={}, Target={}, Stop={}",
                signal.getEntryPrice(),
                signal.getTargetPrice(),
                signal.getStopLoss());

        if (SignalType.BUY.name()
                .equalsIgnoreCase(signal.getSignal())) {

            if (signal.getTargetPrice()
                    <= signal.getEntryPrice()) {

                log.error(
                        "Invalid BUY Trade. Symbol={}, Entry={}, "
                                + "Target={}, Stop={}",
                        signal.getSymbol(),
                        signal.getEntryPrice(),
                        signal.getTargetPrice(),
                        signal.getStopLoss());

                throw new IllegalStateException(
                        "Invalid BUY Trade : Target Price must be "
                                + "greater than Entry Price.");
            }

            if (signal.getStopLoss()
                    >= signal.getEntryPrice()) {

                log.error(
                        "Invalid BUY Trade. Symbol={}, Entry={}, "
                                + "Target={}, Stop={}",
                        signal.getSymbol(),
                        signal.getEntryPrice(),
                        signal.getTargetPrice(),
                        signal.getStopLoss());

                throw new IllegalStateException(
                        "Invalid BUY Trade : Stop Loss must be "
                                + "less than Entry Price.");
            }

        } else if (SignalType.SELL.name()
                .equalsIgnoreCase(signal.getSignal())) {

            if (signal.getTargetPrice()
                    >= signal.getEntryPrice()) {

                log.error(
                        "Invalid SELL Trade. Symbol={}, Entry={}, "
                                + "Target={}, Stop={}",
                        signal.getSymbol(),
                        signal.getEntryPrice(),
                        signal.getTargetPrice(),
                        signal.getStopLoss());

                throw new IllegalStateException(
                        "Invalid SELL Trade : Target Price must be "
                                + "less than Entry Price.");
            }

            if (signal.getStopLoss()
                    <= signal.getEntryPrice()) {

                log.error(
                        "Invalid SELL Trade. Symbol={}, Entry={}, "
                                + "Target={}, Stop={}",
                        signal.getSymbol(),
                        signal.getEntryPrice(),
                        signal.getTargetPrice(),
                        signal.getStopLoss());

                throw new IllegalStateException(
                        "Invalid SELL Trade : Stop Loss must be "
                                + "greater than Entry Price.");
            }
        }

        /*
         * ============================================================
         * STEP 10B : LIVE-LIKE FILL
         * ============================================================
         *
         * A live MARKET entry fills at the price when the order reaches
         * the exchange (after the AI call), across the spread; stop and
         * target stay at their planned levels. A fill already through
         * either level is a trade live would not want, so skip it.
         */
        double referenceEntryPrice =
                marketPrice != null && Double.isFinite(marketPrice) && marketPrice > 0
                        ? marketPrice
                        : signal.getEntryPrice();

        boolean longTrade = SignalType.BUY.name().equalsIgnoreCase(signal.getSignal());

        double entryFill = round(slip(referenceEntryPrice, longTrade));

        boolean fillBeyondPlan = longTrade
                ? entryFill <= signal.getStopLoss() || entryFill >= signal.getTargetPrice()
                : entryFill >= signal.getStopLoss() || entryFill <= signal.getTargetPrice();

        if (fillBeyondPlan) {

            log.warn(
                    "PAPER FILL REJECTED | Symbol={} | Signal={} | SignalEntry={} | MarketPrice={} | "
                            + "Fill={} | Stop={} | Target={} | Price already through stop or target",
                    signal.getSymbol(),
                    signal.getSignal(),
                    signal.getEntryPrice(),
                    referenceEntryPrice,
                    entryFill,
                    signal.getStopLoss(),
                    signal.getTargetPrice());

            return;
        }

        investmentAmount = quantity * entryFill;

        /*
         * ============================================================
         * STEP 11 : APPROVED EXECUTION DATA
         * ============================================================
         *
         * Instrument/margin lookup was already performed by the
         * position-sizing layer. No duplicate broker lookup is needed.
         */

        /*
         * ============================================================
         * STEP 12 : USE APPROVED BROKER MARGIN RESULT
         * ============================================================
         *
         * Margin calculation and quantity sizing were completed by
         * PositionSizingService. This service only consumes the result.
         */
        log.info(
                "APPROVED TRADE SIZING | Symbol={} | TargetMargin={} | " +
                        "RequiredMargin={} | Qty={} | Exposure={} | Leverage={}",
                signal.getSymbol(),
                sizingResult.getTargetMargin(),
                requiredMargin,
                quantity,
                sizingResult.getTradeValue(),
                leverage);

        if (requiredMargin <= 0) {
            log.warn(
                    "TRADE NOT CREATED | Invalid required margin | Symbol={}",
                    signal.getSymbol());
            return;
        }

        /*
         * ============================================================
         * STEP 13 : RESERVE MARGIN
         * ============================================================
         */
        long reserveStart =
                System.currentTimeMillis();

        try {

            balanceMarginClient
                    .reserveMargin(requiredMargin)
                    .block();

            log.info(
                    "MARGIN RESERVATION COMPLETED | " +
                            "Symbol={} | DurationMs={}",
                    signal.getSymbol(),
                    System.currentTimeMillis()
                            - reserveStart);

        } catch (Exception ex) {

            log.error(
                    "MARGIN RESERVATION FAILED | " +
                            "Symbol={} | DurationMs={}",
                    signal.getSymbol(),
                    System.currentTimeMillis()
                            - reserveStart,
                    ex);

            return;
        }

        /*
         * ============================================================
         * STEP 14 : CREATE PAPER TRADE
         * ============================================================
         */
        PaperTrade saved;

        try {

            PaperTrade trade =
                    PaperTrade.builder()
                            .symbol(
                                    signal.getSymbol())

                            .signalId(
                                    signal.getId())

                            .signal(
                                    signal.getSignal())

                            .entryPrice(
                                    entryFill)

                            .referenceEntryPrice(
                                    round(referenceEntryPrice))

                            .strategy(
                                    signal.getStrategy())

                            .qualityScore(
                                    signal.getQualityScore())

                            .marketRegime(
                                    signal.getMarketRegime())

                            .plannedRisk(
                                    round(Math.abs(entryFill - signal.getStopLoss()) * quantity))

                            .quantity(
                                    quantity)

                            .investedAmount(
                                    investmentAmount)

                            .requiredMargin(
                                    round(requiredMargin))

                            .leverage(
                                    round(leverage))

                            .rsi(
                                    indicatorResponse
                                            .getRsi14())

                            .ema20(
                                    indicatorResponse
                                            .getEma20())

                            .ema50(
                                    indicatorResponse
                                            .getEma50())

                            .macd(
                                    indicatorResponse
                                            .getMacd())

                            .targetPrice(
                                    round(
                                            signal.getTargetPrice()))

                            .stopLoss(
                                    round(
                                            signal.getStopLoss()))

                            .initialStopLoss(
                                    round(
                                            signal.getStopLoss()))

                            .currentStopLoss(
                                    round(
                                            signal.getStopLoss()))

                            .trailingStep(0)

                            .lastTrailingUpdate(
                                    LocalDateTime.now())

                            .status(
                                    SignalStatus.OPEN)

                            .confidence(
                                    signal.getConfidence())

                            .entryTime(
                                    LocalDateTime.now())

                            .build();

            saved =
                    repository.save(trade);

        } catch (Exception ex) {

            /*
             * IMPORTANT:
             *
             * Margin was already reserved.
             * Release it if PaperTrade saving fails.
             */
            log.error(
                    "Paper Trade Save Failed. " +
                            "Releasing reserved margin | Symbol={}",
                    signal.getSymbol(),
                    ex);

            try {

                balanceMarginClient
                        .releaseMargin(
                                requiredMargin,
                                0.0)
                        .block();

                log.info(
                        "Reserved Margin Released Successfully | " +
                                "Symbol={}",
                        signal.getSymbol());

            } catch (Exception releaseException) {

                log.error(
                        "CRITICAL: Failed to release margin after " +
                                "PaperTrade save failure | Symbol={}",
                        signal.getSymbol(),
                        releaseException);
            }

            return;
        }

        tradingFunnelStatisticsService
                .recordPaperTradeCreated();

        /*
         * ============================================================
         * TRADE CREATED LOGGING
         * ============================================================
         */
        log.info("======================================");
        log.info("PAPER TRADE CREATED SUCCESSFULLY");
        log.info(
                "Trade ID            : {}",
                saved.getId());
        log.info(
                "Symbol              : {}",
                saved.getSymbol());
        log.info(
                "Signal              : {}",
                saved.getSignal());
        log.info(
                "Entry Price         : {}",
                saved.getEntryPrice());
        log.info(
                "Quantity            : {}",
                saved.getQuantity());
        log.info(
                "Invested Amount     : {}",
                saved.getInvestedAmount());
        log.info(
                "Required Margin     : {}",
                saved.getRequiredMargin());
        log.info(
                "Leverage            : {}",
                saved.getLeverage());
        log.info(
                "Available Capital Before Trade : {}",
                availableCapital);
        log.info(
                "Available Capital After Trade  : {}",
                availableCapital - investmentAmount);
        log.info("======================================");

        /*
         * ============================================================
         * TRADE ENTRY SNAPSHOT
         * ============================================================
         */
        log.info(
                "TRADE_ENTRY_SNAPSHOT | " +
                        "TradeId={} | " +
                        "Symbol={} | " +
                        "SignalId={} | " +
                        "Signal={} | " +
                        "Entry={} | " +
                        "Target={} | " +
                        "InitialStop={} | " +
                        "CurrentStop={} | " +
                        "Qty={} | " +
                        "Confidence={} | " +
                        "RSI={} | " +
                        "EMA20={} | " +
                        "EMA50={} | " +
                        "MACD={} | " +
                        "RequiredMargin={} | " +
                        "Leverage={} | " +
                        "EntryTime={}",

                saved.getId(),
                saved.getSymbol(),
                saved.getSignalId(),
                saved.getSignal(),
                saved.getEntryPrice(),
                saved.getTargetPrice(),
                saved.getInitialStopLoss(),
                saved.getCurrentStopLoss(),
                saved.getQuantity(),
                saved.getConfidence(),
                saved.getRsi(),
                saved.getEma20(),
                saved.getEma50(),
                saved.getMacd(),
                saved.getRequiredMargin(),
                saved.getLeverage(),
                saved.getEntryTime());

        /*
         * ============================================================
         * STEP 15 : UPDATE PORTFOLIO
         * ============================================================
         */
        portfolioClient
                .openPosition(
                        saved.getSymbol(),
                        saved.getQuantity(),
                        saved.getEntryPrice())

                .doOnSuccess(v ->
                        log.info(
                                "Portfolio Updated Successfully : {}",
                                saved.getSymbol()))

                .onErrorResume(ex -> {

                    log.error(
                            "Portfolio Update Failed for {}",
                            saved.getSymbol(),
                            ex);

                    return Mono.empty();
                })

                .subscribe();

        /*
         * ============================================================
         * STEP 16 : NOTIFICATION
         * ============================================================
         */
        NotificationRequest request =
                NotificationRequest.builder()
                        .channel(
                                NotificationChannel.SLACK)

                        .title(
                                "TRADE OPENED")

                        .message(
                                "Symbol: "
                                        + saved.getSymbol()
                                        + ", Signal: "
                                        + saved.getSignal()
                                        + ", Entry: ₹"
                                        + saved.getEntryPrice()
                                        + ", Target: ₹"
                                        + saved.getTargetPrice()
                                        + ", StopLoss: ₹"
                                        + saved.getStopLoss()
                                        + ", Quantity: "
                                        + saved.getQuantity()
                                        + ", Required Margin: ₹"
                                        + saved.getRequiredMargin()
                                        + ", Leverage: "
                                        + saved.getLeverage()
                                        + "x"
                                        + ", Confidence: "
                                        + saved.getConfidence()
                                        + "%")

                        .build();

        notificationClient
                .sendNotification(request)
                .doOnError(e ->
                        log.error(
                                "Slack Notification Failed",
                                e))
                .subscribe();
    }

    public Mono<Void> closePreviousDayTrades() {

        log.info("======================================");
        log.info("Checking Previous Day Open Trades");
        log.info("======================================");

        return Flux.fromIterable(repository.findByStatus(SignalStatus.OPEN))

                .filter(trade ->
                        !trade.getEntryTime()
                                .toLocalDate()
                                .equals(LocalDate.now()))

                .flatMap(trade -> {

                    log.info("Closing Previous Day Trade : {}",
                            trade.getSymbol());

                    trade.setStatus(SignalStatus.MARKET_CLOSED);
                    trade.setExitTime(LocalDateTime.now());

                    // If no market price is available,
                    // close at entry price.
                    trade.setExitPrice(trade.getEntryPrice());
                    trade.setProfitLoss(0.0);


                    return Mono.fromCallable(() ->
                                    repository.save(trade))
                            .subscribeOn(Schedulers.boundedElastic());

                })

                .then()

                .doOnSuccess(v ->
                        log.info("Previous Day Trade Cleanup Completed"))

                .doOnError(ex ->
                        log.error("Previous Day Trade Cleanup Failed", ex));
    }


    public PaperTrade createPaperTrade(CreatePaperTradeRequest request) {

        PaperTrade trade = PaperTrade.builder()
                .symbol(request.getSymbol())
                .signal(request.getPaperSignal())
                .entryPrice(request.getEntryPrice())
                .quantity(request.getQuantity())
                .investedAmount(request.getEntryPrice() * request.getQuantity())
                .targetPrice(request.getTargetPrice())
                .stopLoss(request.getStopLoss())
                .confidence(request.getConfidence())
                .ema20(request.getEma20())
                .ema50(request.getEma50())
                .macd(request.getMacd())
                .rsi(request.getRsi())
                .status(SignalStatus.OPEN)
                .entryTime(LocalDateTime.now())
                .build();

        return repository.save(trade);
    }

    private Double round(Double value) {

        if (value == null) {
            return null;
        }

        return BigDecimal.valueOf(value)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    public List<PaperTrade> getByStatus(SignalStatus status) {

        return repository.findByStatus(status);
    }

    public List<PaperTrade> getAll() {
        return repository.findAll();
    }

    public Mono<OpenPositionContextResponse> getOpenPositionContext(String symbol) {

        PaperTrade trade = repository
                .findTopBySymbolAndStatusOrderByEntryTimeDesc(
                        symbol,
                        SignalStatus.OPEN)
                .orElse(null);

        if (trade == null) {
            return Mono.just(OpenPositionContextResponse.builder()
                    .positionExists(false)
                    .symbol(symbol)
                    .build());
        }

        return stockServiceClient
                .getStockPrice(symbol)
                .map(stock -> {

                    Double currentPrice = stock.getPrice();

                    Double currentPnL;

                    if ("BUY".equalsIgnoreCase(trade.getSignal())) {

                        currentPnL =
                                (currentPrice - trade.getEntryPrice())
                                        * trade.getQuantity();

                    } else {

                        currentPnL =
                                (trade.getEntryPrice() - currentPrice)
                                        * trade.getQuantity();
                    }

                    Double pnlPercentage =
                            (currentPnL / trade.getInvestedAmount()) * 100;

                    return OpenPositionContextResponse.builder()
                            .positionExists(true)
                            .symbol(trade.getSymbol())
                            .quantity(trade.getQuantity())
                            .entryPrice(trade.getEntryPrice())
                            .currentPrice(currentPrice)
                            .currentPnL(Math.round(currentPnL * 100.0) / 100.0)
                            .pnlPercentage(Math.round(pnlPercentage * 100.0) / 100.0)
                            .stopLoss(trade.getCurrentStopLoss())
                            .targetPrice(trade.getTargetPrice())
                            .status(trade.getStatus().name())
                            .signal(trade.getSignal())
                            .build();
                });
    }

    public PaperTradeSummary getSummary() {

        List<PaperTrade> trades =
                repository.findAll();

        long totalTrades =
                trades.size();

        long openPositions =
                trades.stream()
                        .filter(t ->
                                SignalStatus.OPEN
                                        .equals(t.getStatus()))
                        .count();

        long closedPositions =
                totalTrades - openPositions;

        long winningTrades =
                trades.stream()
                        .filter(t ->
                                t.getProfitLoss() != null
                                        && t.getProfitLoss() > 0)
                        .count();

        long losingTrades =
                trades.stream()
                        .filter(t ->
                                t.getProfitLoss() != null
                                        && t.getProfitLoss() < 0)
                        .count();

        long breakevenTrades =
                trades.stream()
                        .filter(t ->
                                t.getProfitLoss() != null
                                        && t.getProfitLoss() == 0)
                        .count();

        double totalInvestment =
                trades.stream()
                        .filter(t ->
                                SignalStatus.OPEN
                                        .equals(t.getStatus()))
                        .mapToDouble(
                                PaperTrade::getInvestedAmount)
                        .sum();

        double totalProfit =
                trades.stream()
                        .filter(t ->
                                t.getProfitLoss() != null)
                        .mapToDouble(
                                PaperTrade::getProfitLoss)
                        .sum();

        double winRate =
                (winningTrades + losingTrades) == 0
                        ? 0
                        : (winningTrades * 100.0)
                          / (winningTrades + losingTrades);

        return PaperTradeSummary.builder()
                .totalTrades(totalTrades)
                .openPositions(openPositions)
                .closedPositions(closedPositions)
                .winningTrades(winningTrades)
                .losingTrades(losingTrades)
                .breakevenTrades(breakevenTrades)
                .totalInvestment(totalInvestment)
                .totalProfit(totalProfit)
                .winRate(Math.round(winRate * 100.0) / 100.0)
                .build();
    }

    public List<PaperTrade> getHistory() {
        return repository
                .findAllByOrderByEntryTimeDesc();
    }

    public TradeInsights getInsights() {
        List<PaperTrade> closedTrades =
                repository.findAll()
                        .stream()
                        .filter(trade ->
                                trade.getStatus() != SignalStatus.OPEN)
                        .toList();

        double averageWinningRsi =
                closedTrades.stream()
                        .filter(trade ->
                                trade.getProfitLoss() != null
                                        && trade.getProfitLoss() > 0)
                        .mapToDouble(PaperTrade::getRsi)
                        .average()
                        .orElse(0);

        double averageLosingRsi =
                closedTrades.stream()
                        .filter(trade ->
                                trade.getProfitLoss() != null
                                        && trade.getProfitLoss() < 0)
                        .mapToDouble(PaperTrade::getRsi)
                        .average()
                        .orElse(0);

        double averageWinningMacd =
                closedTrades.stream()
                        .filter(trade ->
                                trade.getProfitLoss() != null
                                        && trade.getProfitLoss() > 0)
                        .mapToDouble(PaperTrade::getMacd)
                        .average()
                        .orElse(0);

        double averageLosingMacd =
                closedTrades.stream()
                        .filter(trade ->
                                trade.getProfitLoss() != null
                                        && trade.getProfitLoss() < 0)
                        .mapToDouble(PaperTrade::getMacd)
                        .average()
                        .orElse(0);

        Integer bestConfidence =
                closedTrades.stream()
                        .filter(trade ->
                                trade.getConfidence() != null)
                        .map(PaperTrade::getConfidence)
                        .max(Integer::compareTo)
                        .orElse(0);

        Integer worstConfidence =
                closedTrades.stream()
                        .filter(trade ->
                                trade.getConfidence() != null)
                        .map(PaperTrade::getConfidence)
                        .min(Integer::compareTo)
                        .orElse(0);

        return TradeInsights.builder()
                .averageWinningRsi(
                        averageWinningRsi)
                .averageLosingRsi(
                        averageLosingRsi)
                .averageWinningMacd(
                        averageWinningMacd)
                .averageLosingMacd(
                        averageLosingMacd)
                .bestConfidence(
                        bestConfidence)
                .worstConfidence(
                        worstConfidence)
                .build();
    }

    public PaperTradeDashboard getDashboard() {

        List<PaperTrade> trades =
                repository.findAll();

        long totalTrades =
                trades.size();

        long openTrades =
                trades.stream()
                        .filter(t ->
                                t.getStatus() ==
                                        SignalStatus.OPEN)
                        .count();

        List<PaperTrade> closedTradesList =
                trades.stream()
                        .filter(t ->
                                t.getStatus() !=
                                        SignalStatus.OPEN)
                        .toList();

        long closedTrades =
                closedTradesList.size();

        long winningTrades =
                closedTradesList.stream()
                        .filter(t ->
                                t.getProfitLoss() != null
                                        && t.getProfitLoss() > 0)
                        .count();

        long losingTrades =
                closedTradesList.stream()
                        .filter(t ->
                                t.getProfitLoss() != null
                                        && t.getProfitLoss() < 0)
                        .count();

        long breakevenTrades =
                closedTradesList.stream()
                        .filter(t ->
                                t.getProfitLoss() != null
                                        && t.getProfitLoss() == 0)
                        .count();

        double totalProfit =
                closedTradesList.stream()
                        .filter(t ->
                                t.getProfitLoss() != null)
                        .mapToDouble(
                                PaperTrade::getProfitLoss)
                        .sum();

        double bestTrade =
                closedTradesList.stream()
                        .filter(t ->
                                t.getProfitLoss() != null)
                        .mapToDouble(
                                PaperTrade::getProfitLoss)
                        .max()
                        .orElse(0);

        double worstTrade =
                closedTradesList.stream()
                        .filter(t ->
                                t.getProfitLoss() != null)
                        .mapToDouble(
                                PaperTrade::getProfitLoss)
                        .min()
                        .orElse(0);

        double winRate =
                closedTrades == 0
                        ? 0
                        : ((double) winningTrades
                           / closedTrades) * 100;

        return PaperTradeDashboard.builder()
                .totalTrades(totalTrades)
                .openTrades(openTrades)
                .closedTrades(closedTrades)
                .winningTrades(winningTrades)
                .losingTrades(losingTrades)
                .breakevenTrades(breakevenTrades)
                .winRate(winRate)
                .totalProfit(totalProfit)
                .bestTrade(bestTrade)
                .worstTrade(worstTrade)
                .build();
    }

    public StrategyReport getStrategyReport() {
        List<PaperTrade> trades =
                repository.findAll()
                        .stream()
                        .filter(t ->
                                t.getStatus() != SignalStatus.OPEN)
                        .toList();

        long totalTrades =
                trades.size();

        long winningTrades =
                trades.stream()
                        .filter(t ->
                                t.getProfitLoss() != null
                                        && t.getProfitLoss() > 0)
                        .count();

        long losingTrades =
                trades.stream()
                        .filter(t ->
                                t.getProfitLoss() != null
                                        && t.getProfitLoss() < 0)
                        .count();

        double winRate =
                totalTrades == 0
                        ? 0
                        : ((double) winningTrades
                           / totalTrades) * 100;

        double averageConfidence =
                trades.stream()
                        .filter(t ->
                                t.getConfidence() != null)
                        .mapToInt(
                                PaperTrade::getConfidence)
                        .average()
                        .orElse(0);

        double averageWinningConfidence =
                trades.stream()
                        .filter(t ->
                                t.getProfitLoss() != null
                                        && t.getProfitLoss() > 0
                                        && t.getConfidence() != null)
                        .mapToInt(
                                PaperTrade::getConfidence)
                        .average()
                        .orElse(0);

        double averageLosingConfidence =
                trades.stream()
                        .filter(t ->
                                t.getProfitLoss() != null
                                        && t.getProfitLoss() < 0
                                        && t.getConfidence() != null)
                        .mapToInt(
                                PaperTrade::getConfidence)
                        .average()
                        .orElse(0);

        double averageWinningRsi =
                trades.stream()
                        .filter(t ->
                                t.getProfitLoss() != null
                                        && t.getProfitLoss() > 0
                                        && t.getRsi() != null)
                        .mapToDouble(
                                PaperTrade::getRsi)
                        .average()
                        .orElse(0);

        double averageLosingRsi =
                trades.stream()
                        .filter(t ->
                                t.getProfitLoss() != null
                                        && t.getProfitLoss() < 0
                                        && t.getRsi() != null)
                        .mapToDouble(
                                PaperTrade::getRsi)
                        .average()
                        .orElse(0);

        long breakevenTrades =  trades.stream().filter(t ->t.getProfitLoss() != null
                && t.getProfitLoss() == 0).count();
        return StrategyReport.builder()
                .totalTrades(totalTrades)
                .winningTrades(winningTrades)
                .losingTrades(losingTrades)
                .winRate(winRate)
                .averageConfidence(averageConfidence)
                .averageWinningConfidence(averageWinningConfidence)
                .averageLosingConfidence(averageLosingConfidence)
                .averageWinningRsi(averageWinningRsi)
                .averageLosingRsi(averageLosingRsi)
                .breakevenTrades(breakevenTrades)
                .build();
    }

    public Mono<TradeReviewResponse> reviewTrade(
            Long tradeId) {

        PaperTrade trade =
                repository.findById(tradeId)
                        .orElseThrow();

        if (trade.getExitPrice() == null ||
                trade.getProfitLoss() == null) {

            return Mono.just(
                    TradeReviewResponse.builder()
                            .tradeId(trade.getId())
                            .review(
                                    "Trade is still OPEN. AI review will be available once the trade is completed.")
                            .build());
        }

        TradeReviewRequest request =
                TradeReviewRequest.builder()
                        .tradeId(trade.getId())
                        .symbol(trade.getSymbol())
                        .signal(trade.getSignal())
                        .entryPrice(trade.getEntryPrice())
                        .exitPrice(trade.getExitPrice())
                        .profitLoss(trade.getProfitLoss())
                        .confidence(trade.getConfidence())
                        .rsi(trade.getRsi())
                        .ema20(trade.getEma20())
                        .ema50(trade.getEma50())
                        .macd(trade.getMacd())
                        .build();

        return aiServiceClient.reviewTrade(
                request);
    }

    public Mono<StrategyReviewResponse> strategyReview() {

        List<PaperTrade> trades =
                repository
                        .findTop20ByStatusNotOrderByIdDesc(
                                SignalStatus.OPEN);

        List<TradeReviewRequest> requests =
                trades.stream()
                        .map(trade ->
                                TradeReviewRequest.builder()
                                        .tradeId(trade.getId())
                                        .symbol(trade.getSymbol())
                                        .signal(trade.getSignal())
                                        .entryPrice(trade.getEntryPrice())
                                        .exitPrice(trade.getExitPrice())
                                        .profitLoss(trade.getProfitLoss())
                                        .confidence(trade.getConfidence())
                                        .rsi(trade.getRsi())
                                        .ema20(trade.getEma20())
                                        .ema50(trade.getEma50())
                                        .macd(trade.getMacd())
                                        .build())
                        .toList();
        if (requests.isEmpty()) {
            return Mono.just(
                    StrategyReviewResponse.builder()
                            .totalTrades(0)
                            .winningTrades(0)
                            .losingTrades(0)
                            .review("No completed trades available for strategy review.")
                            .build());
        }
        return aiServiceClient
                .reviewStrategy(requests);
    }

    public TradeAnalyticsResponse getAnalytics() {

        List<PaperTrade> completedTrades =
                repository.findAll()
                        .stream()
                        .filter(trade ->
                                trade.getProfitLoss() != null)
                        .toList();

        long totalTrades = completedTrades.size();

        long winningTrades =
                completedTrades.stream()
                        .filter(t -> t.getProfitLoss() > 0)
                        .count();

        long losingTrades =
                completedTrades.stream()
                        .filter(t -> t.getProfitLoss() < 0)
                        .count();

        double totalProfit =
                completedTrades.stream()
                        .mapToDouble(PaperTrade::getProfitLoss)
                        .sum();

        double winRate =
                totalTrades == 0
                        ? 0
                        : (winningTrades * 100.0) / totalTrades;

        long breakevenTrades =
                completedTrades.stream()
                        .filter(t -> t.getProfitLoss() == 0)
                        .count();

        double averageLoss =
                completedTrades.stream()
                        .filter(t -> t.getProfitLoss() != null)
                        .filter(t -> t.getProfitLoss() < 0)
                        .mapToDouble(PaperTrade::getProfitLoss)
                        .average()
                        .orElse(0.0);

        double bestTrade =
                completedTrades.stream()
                        .filter(t -> t.getProfitLoss() != null)
                        .mapToDouble(PaperTrade::getProfitLoss)
                        .max()
                        .orElse(0.0);

        double worstTrade =
                completedTrades.stream()
                        .filter(t -> t.getProfitLoss() != null)
                        .mapToDouble(PaperTrade::getProfitLoss)
                        .min()
                        .orElse(0.0);

        double averageProfit =
                completedTrades.stream()
                        .filter(t -> t.getProfitLoss() != null)
                        .filter(t -> t.getProfitLoss() > 0)
                        .mapToDouble(PaperTrade::getProfitLoss)
                        .average()
                        .orElse(0.0);

        return TradeAnalyticsResponse.builder()
                .totalTrades(totalTrades)
                .winningTrades(winningTrades)
                .losingTrades(losingTrades)
                .breakevenTrades(breakevenTrades)
                .winRate(winRate)
                .totalProfit(totalProfit)
                .averageProfit(averageProfit)
                .averageLoss(averageLoss)
                .bestTrade(bestTrade)
                .worstTrade(worstTrade)
                .build();
    }

    public Mono<PositionSizingResponse> getPositionSize(String symbol, Double capital) {

        return stockServiceClient
                .getStockPrice(symbol)
                .flatMap(basicTradingStrategy::generateSignal)
                .map(signal -> {

                    double allocationPercentage =
                            TradeUtil.getAllocationPercentage(
                                    signal.getConfidence());

                    double investment =
                            capital * allocationPercentage;

                    int quantity =
                            (int) (investment
                                    / signal.getEntryPrice());

                    double riskPerShare;

                    if ("BUY".equals(signal.getSignal())) {

                        riskPerShare =
                                signal.getEntryPrice()
                                        - signal.getStopLoss();

                    } else {

                        riskPerShare =
                                signal.getStopLoss()
                                        - signal.getEntryPrice();
                    }

                    return PositionSizingResponse
                            .builder()
                            .symbol(signal.getSymbol())
                            .capital(capital)
                            .confidence(signal.getConfidence())
                            .recommendedInvestment(TradeUtil.round(investment))
                            .recommendedQuantity(quantity)
                            .riskPerShare(TradeUtil.round(riskPerShare))
                            .totalRisk(TradeUtil.round(quantity * riskPerShare))
                            .build();
                });
    }

    public DailyPnLResponse getDailyPnL() {

        LocalDate today =
                LocalDate.now();

        List<PaperTrade> trades =
                repository.findAll()
                        .stream()
                        .filter(t ->
                                t.getExitTime() != null)
                        .filter(t ->
                                t.getExitTime()
                                        .toLocalDate()
                                        .equals(today))
                        .toList();

        double todayProfit =
                trades.stream()
                        .filter(t ->
                                t.getProfitLoss() != null)
                        .mapToDouble(
                                PaperTrade::getProfitLoss)
                        .sum();

        long winningTrades =
                trades.stream()
                        .filter(t ->
                                t.getProfitLoss() != null)
                        .filter(t ->
                                t.getProfitLoss() > 0)
                        .count();

        long losingTrades =
                trades.stream()
                        .filter(t ->
                                t.getProfitLoss() != null)
                        .filter(t ->
                                t.getProfitLoss() < 0)
                        .count();

        long breakevenTrades =
                trades.stream()
                        .filter(t ->
                                t.getProfitLoss() != null)
                        .filter(t ->
                                t.getProfitLoss() == 0)
                        .count();

        return DailyPnLResponse.builder()
                .todayProfit(todayProfit)
                .totalTrades(
                        (long) trades.size())
                .winningTrades(winningTrades)
                .losingTrades(losingTrades)
                .breakevenTrades(
                        breakevenTrades)
                .build();
    }

    public DailyPnLResponse getPnLByDateRange(
            LocalDate fromDate,
            LocalDate toDate) {

        List<PaperTrade> trades =
                repository.findAll()
                        .stream()
                        .filter(trade ->
                                trade.getExitTime() != null)
                        .filter(trade -> {

                            LocalDate exitDate =
                                    trade.getExitTime()
                                            .toLocalDate();

                            return !exitDate.isBefore(fromDate)
                                    && !exitDate.isAfter(toDate);
                        })
                        .toList();

        double totalProfit =
                trades.stream()
                        .filter(trade ->
                                trade.getProfitLoss() != null)
                        .mapToDouble(
                                PaperTrade::getProfitLoss)
                        .sum();

        long winningTrades =
                trades.stream()
                        .filter(trade ->
                                trade.getProfitLoss() != null)
                        .filter(trade ->
                                trade.getProfitLoss() > 0)
                        .count();

        long losingTrades =
                trades.stream()
                        .filter(trade ->
                                trade.getProfitLoss() != null)
                        .filter(trade ->
                                trade.getProfitLoss() < 0)
                        .count();

        long breakevenTrades =
                trades.stream()
                        .filter(trade ->
                                trade.getProfitLoss() != null)
                        .filter(trade ->
                                trade.getProfitLoss() == 0)
                        .count();

        return DailyPnLResponse.builder()
                .todayProfit(totalProfit)
                .totalTrades((long) trades.size())
                .winningTrades(winningTrades)
                .losingTrades(losingTrades)
                .breakevenTrades(breakevenTrades)
                .build();
    }

    public TradingDashboardResponse getPnLDashboard() {

        TradeAnalyticsResponse analytics =
                getAnalytics();

        DailyPnLResponse dailyPnL =
                getDailyPnL();

        long openTrades = repository.countByStatus(SignalStatus.OPEN);

        return TradingDashboardResponse
                .builder()
                .totalTrades(
                        analytics.getTotalTrades())
                .winningTrades(
                        analytics.getWinningTrades())
                .losingTrades(
                        analytics.getLosingTrades())
                .breakevenTrades(
                        analytics.getBreakevenTrades())
                .winRate(
                        analytics.getWinRate())
                .totalProfit(
                        analytics.getTotalProfit())
                .todayProfit(
                        dailyPnL.getTodayProfit())
                .averageProfit(
                        analytics.getAverageProfit())
                .averageLoss(
                        analytics.getAverageLoss())
                .bestTrade(
                        analytics.getBestTrade())
                .worstTrade(
                        analytics.getWorstTrade())
                .openTrades(
                        openTrades)
                .build();
    }

    public List<PaperTrade> findByExitTimeBetweenOrderByExitTimeDesc(
            LocalDateTime fromDateTime,
            LocalDateTime toDateTime){

        return repository
                .findByExitTimeBetweenOrderByExitTimeDesc(
                        fromDateTime,
                        toDateTime);
    }

    public Mono<OpportunityDashboardResponse> getBestOpportunities(
            Double capital) {

        return signalService
                .getTopOpportunities()
                .take(3)

                .flatMap(opportunity ->

                        getPositionSize(
                                opportunity.getSymbol(),
                                capital)

                                .map(position -> {

                                    double reward =
                                            Math.abs(
                                                    opportunity.getTargetPrice()
                                                            - opportunity.getEntryPrice());

                                    double risk =
                                            Math.abs(
                                                    opportunity.getEntryPrice()
                                                            - opportunity.getStopLoss());

                                    double riskRewardRatio =
                                            risk == 0
                                                    ? 0
                                                    : Math.round((reward / risk) * 100.0) / 100.0;

                                    return OpportunityDashboard
                                            .builder()
                                            .symbol(opportunity.getSymbol())
                                            .signal(opportunity.getSignal())
                                            .confidence(opportunity.getConfidence())
                                            .tradeScore(opportunity.getScore())
                                            .targetPrice(opportunity.getTargetPrice())
                                            .stopLoss(opportunity.getStopLoss())
                                            .entryPrice(opportunity.getEntryPrice())
                                            .sentiment(opportunity.getSentiment())
                                            .sentimentScore(opportunity.getSentimentScore())
                                            .technicalReason(opportunity.getTechnicalReason())
                                            .sentimentReason(opportunity.getSentimentReason())
                                            .recommendation(opportunity.getRecommendation())
                                            .recommendedInvestment(position.getRecommendedInvestment())
                                            .recommendedQuantity(position.getRecommendedQuantity())
                                            .riskPerShare(position.getRiskPerShare())
                                            .totalRisk(position.getTotalRisk())
                                            .riskRewardRatio(riskRewardRatio)
                                            .build();}))
                .collectList()
                .map(list -> {

                    for (int i = 0; i < list.size(); i++) {
                        list.get(i).setRank(i + 1);
                    }

                    double recommendedCapital =
                            list.stream()
                                    .mapToDouble(
                                            OpportunityDashboard::getRecommendedInvestment)
                                    .sum();

                    return OpportunityDashboardResponse
                            .builder()
                            .capital(capital)
                            .recommendedCapital(recommendedCapital)
                            .remainingCapital(capital - recommendedCapital)
                            .opportunityCount(list.size())
                            .opportunities(list)
                            .build();
                });
    }

    public AnalyticsMetricsResponse getAdvancedMetrics() {

        List<PaperTrade> trades =
                repository.findAll()
                        .stream()
                        .filter(t -> t.getProfitLoss() != null)
                        .toList();

        if (trades.isEmpty()) {

            return AnalyticsMetricsResponse.builder()
                    .profitFactor(0)
                    .expectancy(0)
                    .maxDrawdown(0)
                    .consecutiveWins(0)
                    .consecutiveLosses(0)
                    .build();
        }

        double grossProfit =
                trades.stream()
                        .filter(t -> t.getProfitLoss() > 0)
                        .mapToDouble(PaperTrade::getProfitLoss)
                        .sum();

        double grossLoss =
                Math.abs(
                        trades.stream()
                                .filter(t -> t.getProfitLoss() < 0)
                                .mapToDouble(PaperTrade::getProfitLoss)
                                .sum());

        double profitFactor =
                grossLoss == 0
                        ? grossProfit
                        : grossProfit / grossLoss;

        long winningTrades =
                trades.stream()
                        .filter(t -> t.getProfitLoss() > 0)
                        .count();

        long losingTrades =
                trades.stream()
                        .filter(t -> t.getProfitLoss() < 0)
                        .count();

        double avgWin =
                trades.stream()
                        .filter(t -> t.getProfitLoss() > 0)
                        .mapToDouble(PaperTrade::getProfitLoss)
                        .average()
                        .orElse(0);

        double avgLoss =
                Math.abs(
                        trades.stream()
                                .filter(t -> t.getProfitLoss() < 0)
                                .mapToDouble(PaperTrade::getProfitLoss)
                                .average()
                                .orElse(0));

        double totalTrades =
                winningTrades + losingTrades;

        double winRate =
                totalTrades == 0
                        ? 0
                        : winningTrades / totalTrades;

        double lossRate =
                totalTrades == 0
                        ? 0
                        : losingTrades / totalTrades;

        double expectancy =
                (winRate * avgWin)
                        - (lossRate * avgLoss);

        double maxDrawdown =
                TradeUtil.calculateMaxDrawdown(trades);

        long consecutiveWins =
                TradeUtil.calculateConsecutiveWins(trades);

        long consecutiveLosses =
                TradeUtil.calculateConsecutiveLosses(trades);

        return AnalyticsMetricsResponse.builder()
                .profitFactor(TradeUtil.round(profitFactor))
                .expectancy(TradeUtil.round(expectancy))
                .maxDrawdown(TradeUtil.round(maxDrawdown))
                .consecutiveWins(consecutiveWins)
                .consecutiveLosses(consecutiveLosses)
                .build();
    }

    public PerformanceAnalytics getPerformanceAnalytics() {

        List<PaperTrade> trades =
                repository.findAll();

        int totalTrades = trades.size();

        int openTrades =
                (int) trades.stream()
                        .filter(t ->
                                t.getStatus() == SignalStatus.OPEN)
                        .count();

        /*
         * Wins/losses are classified by realized P&L, not exit status:
         * a trailed stop (STOP_LOSS_HIT) can close at breakeven or profit,
         * and must stay consistent with totalProfit/totalLoss below.
         */
        int winningTrades =
                (int) trades.stream()
                        .filter(t ->
                                t.getStatus() != SignalStatus.OPEN
                                        && t.getProfitLoss() != null
                                        && t.getProfitLoss() > 0)
                        .count();

        int losingTrades =
                (int) trades.stream()
                        .filter(t ->
                                t.getStatus() != SignalStatus.OPEN
                                        && t.getProfitLoss() != null
                                        && t.getProfitLoss() < 0)
                        .count();

        double totalProfit =
                trades.stream()
                        .filter(t ->
                                t.getStatus() != SignalStatus.OPEN
                                        && t.getProfitLoss() != null
                                        && t.getProfitLoss() > 0)
                        .mapToDouble(
                                PaperTrade::getProfitLoss)
                        .sum();

        double totalLoss =
                trades.stream()
                        .filter(t ->
                                t.getStatus() != SignalStatus.OPEN
                                        && t.getProfitLoss() != null
                                        && t.getProfitLoss() < 0)
                        .mapToDouble(
                                PaperTrade::getProfitLoss)
                        .sum();

        double netProfit =
                totalProfit + totalLoss;

        double winRate =
                (winningTrades + losingTrades) == 0
                        ? 0
                        : ((double) winningTrades
                           / (winningTrades + losingTrades))
                          * 100;

        double averageProfit =
                winningTrades == 0
                        ? 0
                        : totalProfit / winningTrades;

        double averageLoss =
                losingTrades == 0
                        ? 0
                        : Math.abs(totalLoss)
                          / losingTrades;

        double profitFactor =
                totalLoss == 0
                        ? totalProfit
                        : totalProfit
                          / Math.abs(totalLoss);

        return PerformanceAnalytics
                .builder()
                .totalTrades(totalTrades)
                .openTrades(openTrades)
                .winningTrades(winningTrades)
                .losingTrades(losingTrades)
                .winRate(
                        Math.round(winRate * 100.0)
                                / 100.0)
                .totalProfit(totalProfit)
                .totalLoss(totalLoss)
                .netProfit(netProfit)
                .averageProfit(averageProfit)
                .averageLoss(averageLoss)
                .profitFactor(
                        Math.round(profitFactor * 100.0)
                                / 100.0)
                .build();
    }

    public Mono<OpenPositionDashboard> getOpenPositions() {

        return Flux.fromIterable(
                        repository.findByStatus(
                                SignalStatus.OPEN))

                .flatMap(trade ->

                        stockServiceClient
                                .getStockPrice(
                                        trade.getSymbol())

                                .map(stock -> {

                                    Double currentPrice =
                                            stock.getPrice();

                                    double progress = 0.0;

                                    if ("BUY".equalsIgnoreCase(trade.getSignal())) {

                                        progress =
                                                ((currentPrice - trade.getEntryPrice())
                                                        /
                                                        (trade.getTargetPrice() - trade.getEntryPrice()))
                                                        * 100;

                                    } else if ("SELL".equalsIgnoreCase(trade.getSignal())) {

                                        progress =
                                                ((trade.getEntryPrice() - currentPrice)
                                                        /
                                                        (trade.getEntryPrice() - trade.getTargetPrice()))
                                                        * 100;
                                    }

                                    if (!Double.isFinite(progress)) {
                                        progress = 0.0;
                                    }

                                    progress = Math.max(0.0, progress);

                                    progress =
                                            Math.round(progress * 100.0)
                                                    / 100.0;

                                    // Current market value (not P&L)
                                    Double currentValue =
                                            currentPrice
                                                    * trade.getQuantity();

                                    // P&L must respect the trade direction:
                                    // BUY  -> price goes UP  = profit
                                    // SELL -> price goes DOWN = profit
                                    Double pnl;

                                    if ("BUY".equalsIgnoreCase(trade.getSignal())) {

                                        pnl =
                                                (currentPrice - trade.getEntryPrice())
                                                        * trade.getQuantity();

                                    } else if ("SELL".equalsIgnoreCase(trade.getSignal())) {

                                        pnl =
                                                (trade.getEntryPrice() - currentPrice)
                                                        * trade.getQuantity();

                                    } else {

                                        pnl = 0.0;
                                    }

                                    Double pnlPercent =
                                            (pnl
                                                    / trade.getInvestedAmount())
                                                    * 100;


                                    return OpenPositionResponse
                                            .builder()
                                            .tradeId(trade.getId())
                                            .symbol(trade.getSymbol())
                                            .signal(trade.getSignal())
                                            .entryPrice(trade.getEntryPrice())
                                            .currentPrice(currentPrice)
                                            .targetPrice(trade.getTargetPrice())
                                            .stopLoss(trade.getCurrentStopLoss())
                                            .quantity(trade.getQuantity())
                                            .targetProgress(progress)
                                            .investedAmount(
                                                    trade.getInvestedAmount())
                                            .currentValue(currentValue)
                                            .currentPnL(pnl)
                                            .pnlPercentage(
                                                    Math.round(
                                                            pnlPercent * 100.0)
                                                            / 100.0)
                                            .status(
                                                    trade.getStatus().name())
                                            .build();
                                }))

                .collectList()


                .map(positions -> {

                    double totalInvestment =
                            positions.stream()
                                    .mapToDouble(
                                            OpenPositionResponse::getInvestedAmount)
                                    .sum();

                    double currentValue =
                            positions.stream()
                                    .mapToDouble(
                                            OpenPositionResponse::getCurrentValue)
                                    .sum();

                    // Total open P&L must be the sum of each position's
                    // direction-aware P&L. Do not use currentValue -
                    // totalInvestment because that is incorrect for SELL positions.
                    double currentPnL =
                            positions.stream()
                                    .mapToDouble(
                                            OpenPositionResponse::getCurrentPnL)
                                    .sum();

                    positions.forEach(position -> {

                        double allocation =
                                (position.getInvestedAmount()
                                        / totalInvestment) * 100;

                        position.setPortfolioAllocation(
                                Math.round(allocation * 100.0) / 100.0);
                    });

                    OpenPositionResponse best =
                            positions.stream()
                                    .max(
                                            Comparator.comparing(
                                                    OpenPositionResponse::getCurrentPnL))
                                    .orElse(null);

                    OpenPositionResponse worst =
                            positions.stream()
                                    .min(
                                            Comparator.comparing(
                                                    OpenPositionResponse::getCurrentPnL))
                                    .orElse(null);

                    return OpenPositionDashboard
                            .builder()
                            .openTrades(
                                    positions.size())
                            .totalInvestment(
                                    totalInvestment)
                            .currentValue(
                                    currentValue)
                            .currentPnL(
                                    currentPnL)
                            .positions(
                                    positions)
                            .bestPosition(best != null ? best.getSymbol()
                                    : null)

                            .bestPnL(
                                    best != null
                                            ? best.getCurrentPnL()
                                            : 0)

                            .worstPosition(
                                    worst != null
                                            ? worst.getSymbol()
                                            : null)

                            .worstPnL(
                                    worst != null
                                            ? worst.getCurrentPnL()
                                            : 0)

                            .availableCapital(totalCapital - totalInvestment)
                            .build();
                });
    }

    public Mono<Void> closeTrade(
            PaperTrade trade,
            ExitDecision decision,
            Tick tick) {

        /*
         * One close per trade. A tick exit and the market-close square-off can
         * race for the same trade; closing twice would release its margin (and
         * apply its P&L) to the Balance Margin account twice.
         */
        Long tradeId = trade.getId();

        if (tradeId != null && !closingTradeIds.add(tradeId)) {

            log.warn(
                    "Trade close already in progress. Skipping duplicate close | TradeId={} | Symbol={}",
                    tradeId,
                    trade.getSymbol());

            return Mono.empty();
        }

        log.info("==========================================");
        log.info("Trade Closing.......");
        log.info("Symbol      : {}", trade.getSymbol());
        log.info("Entry Price : {}", trade.getEntryPrice());
        log.info("Exit Price  : {}", tick.getLastTradedPrice());

        trade.setExitReason(decision.getReason());

        if (decision.getReason() == ExitReason.TARGET) {

            trade.setStatus(SignalStatus.TARGET_HIT);

        } else if (decision.getReason() == ExitReason.STOP_LOSS
                || decision.getReason() == ExitReason.TRAILING_STOP) {

            trade.setStatus(SignalStatus.STOP_LOSS_HIT);

        } else if (decision.getReason() == ExitReason.MARKET_CLOSE) {

            trade.setStatus(SignalStatus.MARKET_CLOSED);

            log.info("Trade Closed due to Market Close");

        } else {

            trade.setStatus(SignalStatus.CLOSED);
        }

        trade.setExitTime(LocalDateTime.now());

        settle(trade, tick.getLastTradedPrice());

        log.info(
                "Profit/Loss : {} (gross {} - charges {}) | R={}",
                trade.getProfitLoss(),
                trade.getGrossProfitLoss(),
                trade.getCharges(),
                trade.getRMultiple());

        return Mono.fromCallable(() -> {

                    // Re-check persisted state: an earlier close may already have finished.
                    if (tradeId != null) {

                        PaperTrade current =
                                repository.findById(tradeId).orElse(null);

                        if (current == null
                                || current.getStatus() != SignalStatus.OPEN) {

                            log.warn(
                                    "Trade is no longer OPEN. Skipping close | TradeId={} | Status={}",
                                    tradeId,
                                    current != null ? current.getStatus() : null);

                            return null;
                        }
                    }

                    return repository.save(trade);
                })
                .subscribeOn(Schedulers.boundedElastic())

                // Return the reserved margin plus realized P&L to the paper account
                .flatMap(savedTrade ->
                        releaseAccountMargin(savedTrade)
                                .thenReturn(savedTrade))

                // Update Portfolio
                .flatMap(savedTrade ->

                        portfolioClient
                                .closePosition(
                                        savedTrade.getSymbol(),
                                        savedTrade.getQuantity())

                                .doOnSuccess(v ->
                                        log.info(
                                                "Portfolio Updated Successfully : {}",
                                                savedTrade.getSymbol()))

                                .onErrorResume(ex -> {

                                    log.error(
                                            "Portfolio Update Failed",
                                            ex);

                                    return Mono.empty();

                                })

                                .thenReturn(savedTrade)
                )

                // Update Trading Signal
                .flatMap(savedTrade ->

                        tradingSignalService
                                .closeSignal(
                                        savedTrade.getSignalId(),
                                        savedTrade.getExitPrice(),
                                        savedTrade.getProfitLoss(),
                                        savedTrade.getStatus(),
                                        savedTrade.getExitTime())

                                .doOnSuccess(v ->
                                        log.info("Trading Signal Updated"))

                                .onErrorResume(ex -> {
                                    log.error("Trading Signal Update Failed", ex);
                                    return Mono.empty();
                                })

                                .thenReturn(savedTrade)
                )

                // Send Notification
                .flatMap(savedTrade -> {

                    NotificationRequest request =
                            NotificationRequest.builder()
                                    .channel(NotificationChannel.SLACK)
                                    .title("TRADE CLOSED")
                                    .message(
                                            "Symbol: " + savedTrade.getSymbol()
                                                    + ", Status: " + savedTrade.getStatus()
                                                    + ", Entry: ₹" + savedTrade.getEntryPrice()
                                                    + ", Exit: ₹" + savedTrade.getExitPrice()
                                                    + ", PnL: ₹" + savedTrade.getProfitLoss())
                                    .build();

                    return notificationClient

                            .sendNotification(request)

                            .doOnError(ex ->
                                    log.error(
                                            "Failed to send notification",
                                            ex))

                            .onErrorResume(ex -> Mono.empty())

                            .then();
                })

                .doOnSuccess(v -> {

                    log.info("==========================================");
                    log.info("Trade Closed Successfully");
                    log.info("Symbol : {}", trade.getSymbol());
                    log.info("Status : {}", trade.getStatus());
                    log.info("PnL    : {}", trade.getProfitLoss());
                    log.info("==========================================");

                })

                .doFinally(signal -> {
                    if (tradeId != null) {
                        closingTradeIds.remove(tradeId);
                    }
                });

    }

    /**
     * Releases the margin reserved when the paper trade opened and applies its
     * realized P&L, so the Balance Margin account (the owner of paper capital)
     * reflects closed trades. Without this, available capital only shrank during
     * the day and new trades were refused once it was used up.
     *
     * A failure is logged but does not undo the close: the trade is closed, and
     * the daily 08:30 account reset restores the balance.
     */
    private Mono<Void> releaseAccountMargin(PaperTrade trade) {

        Double requiredMargin = trade.getRequiredMargin();

        if (requiredMargin == null
                || !Double.isFinite(requiredMargin)
                || requiredMargin <= 0) {

            log.warn(
                    "MARGIN RELEASE SKIPPED | No reserved margin recorded | Symbol={} | TradeId={}",
                    trade.getSymbol(),
                    trade.getId());

            return Mono.empty();
        }

        double profitLoss = trade.getProfitLoss() != null
                ? trade.getProfitLoss() : 0.0;

        return balanceMarginClient
                .releaseMargin(requiredMargin, profitLoss)
                .doOnSuccess(v -> log.info(
                        "MARGIN RELEASED | Symbol={} | Margin={} | PnL={}",
                        trade.getSymbol(),
                        requiredMargin,
                        profitLoss))
                .onErrorResume(ex -> {
                    log.error(
                            "MARGIN RELEASE FAILED | Symbol={} | Margin={} | PnL={} | "
                                    + "Paper account balance is understated until the daily reset",
                            trade.getSymbol(),
                            requiredMargin,
                            profitLoss,
                            ex);
                    return Mono.empty();
                });
    }

    /**
     * Fills the exit like a live MARKET / SL-M order (adverse slippage from the
     * trigger price) and books P&L the way the broker account will: profitLoss
     * is net of round-trip charges, with gross, charges and R kept alongside.
     */
    private void settle(PaperTrade trade, double referenceExitPrice) {

        boolean longTrade = !SignalType.SELL.name().equalsIgnoreCase(trade.getSignal());

        // Closing a long sells, closing a short buys.
        trade.setReferenceExitPrice(round(referenceExitPrice));
        trade.setExitPrice(round(slip(referenceExitPrice, !longTrade)));

        double gross = calculatePnL(trade);

        IntradayChargesCalculator.Charges charges =
                chargesCalculator.roundTrip(
                        trade.getSignal(),
                        trade.getEntryPrice(),
                        trade.getExitPrice(),
                        trade.getQuantity());

        double net = gross - charges.getTotal();

        trade.setGrossProfitLoss(round(gross));
        trade.setCharges(charges.getTotal());
        trade.setProfitLoss(round(net));

        if (trade.getPlannedRisk() != null && trade.getPlannedRisk() > 0) {
            trade.setRMultiple(round(net / trade.getPlannedRisk()));
        }

        recordExcursion(trade, referenceExitPrice);
    }

    /** Highest and lowest price seen per open trade id, for MFE / MAE at close. */
    private final java.util.Map<Long, double[]> priceExtremes =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Called on every tick of an open paper trade. In memory: a restart keeps only later ticks. */
    public void recordPrice(PaperTrade trade, Double price) {
        if (trade == null || trade.getId() == null || price == null || !Double.isFinite(price) || price <= 0) {
            return;
        }
        priceExtremes.merge(trade.getId(), new double[]{price, price},
                (seen, now) -> new double[]{Math.max(seen[0], now[0]), Math.min(seen[1], now[1])});
    }

    /** MFE / MAE in R from the extremes seen, the entry and the exit trigger price. */
    private void recordExcursion(PaperTrade trade, double referenceExitPrice) {

        double[] seen = trade.getId() == null ? null : priceExtremes.remove(trade.getId());
        double high = Math.max(trade.getEntryPrice(), referenceExitPrice);
        double low = Math.min(trade.getEntryPrice(), referenceExitPrice);
        if (seen != null) {
            high = Math.max(high, seen[0]);
            low = Math.min(low, seen[1]);
        }

        Double riskPerShare = trade.getPlannedRisk() != null && trade.getQuantity() != null && trade.getQuantity() > 0
                ? trade.getPlannedRisk() / trade.getQuantity()
                : null;
        if (riskPerShare == null || riskPerShare <= 0) {
            return;
        }

        boolean longTrade = !SignalType.SELL.name().equalsIgnoreCase(trade.getSignal());
        double favourable = longTrade ? high - trade.getEntryPrice() : trade.getEntryPrice() - low;
        double adverse = longTrade ? trade.getEntryPrice() - low : high - trade.getEntryPrice();

        trade.setMfeR(round(Math.max(0, favourable) / riskPerShare));
        trade.setMaeR(round(Math.max(0, adverse) / riskPerShare));
    }

    /** Price a market order fills at: worse by slippagePct for the side taken. */
    private double slip(double price, boolean buying) {
        double factor = slippagePct / 100.0;
        return buying ? price * (1 + factor) : price * (1 - factor);
    }

    private Double calculatePnL(PaperTrade trade) {

        if ("SELL".equalsIgnoreCase(trade.getSignal())) {

            return (trade.getEntryPrice()
                    - trade.getExitPrice())
                    * trade.getQuantity();
        }

        return (trade.getExitPrice()
                - trade.getEntryPrice())
                * trade.getQuantity();
    }

    private boolean validateTradeCreation(
            TradingSignalEntity signal) {

        String symbol = signal.getSymbol();

        /*
         * ========================================
         * STEP 1 : PREVENT DUPLICATE OPEN TRADE
         * ========================================
         */
        boolean openTradeExists =
                repository.existsBySymbolAndStatus(
                        symbol,
                        SignalStatus.OPEN);

        if (openTradeExists) {

            log.info(
                    "Skipping trade creation. Open trade already exists | Symbol={}",
                    symbol
            );

            return false;
        }

        /*
         * ========================================
         * STEP 2 : GET LATEST COMPLETED TRADE
         * ========================================
         */
        Optional<PaperTrade> latestTradeOptional =
                repository.findTopBySymbolAndExitTimeIsNotNullOrderByExitTimeDesc(symbol);

        if (latestTradeOptional.isEmpty()) {
            return true;
        }

        PaperTrade latestTrade =
                latestTradeOptional.get();

        /*
         * ========================================
         * STEP 3 : APPLY COOLDOWN AFTER
         * ANY COMPLETED TRADE
         * ========================================
         */
        if (latestTrade.getExitTime() == null) {

            log.warn(
                    "Latest trade has no exit time. Blocking trade creation | Symbol={} | Status={}",
                    symbol,
                    latestTrade.getStatus()
            );

            return false;
        }

        LocalDateTime cooldownUntil =
                latestTrade.getExitTime()
                        .plusMinutes(reentryCooldownMinutes);

        LocalDateTime now =
                LocalDateTime.now();

        if (now.isBefore(cooldownUntil)) {

            long remainingSeconds =
                    Duration.between(
                            now,
                            cooldownUntil
                    ).getSeconds();

            log.info("""
                
                ========================================
                TRADE REJECTED - RE-ENTRY COOLDOWN
                ========================================
                Symbol             : {}
                Previous Status    : {}
                Last Exit Time     : {}
                Cooldown Until     : {}
                Remaining Seconds  : {}
                ========================================
                """,
                    symbol,
                    latestTrade.getStatus(),
                    latestTrade.getExitTime(),
                    cooldownUntil,
                    remainingSeconds
            );

            return false;
        }

        log.info("""
            
            Re-entry cooldown completed.
            Symbol={}
            Previous Status={}
            Last Exit Time={}
            """,
                symbol,
                latestTrade.getStatus(),
                latestTrade.getExitTime()
        );

        return true;
    }

    private double getAvailableCapital() {

        double usedCapital =
                repository.findByStatus(SignalStatus.OPEN)
                        .stream()
                        .mapToDouble(trade -> trade.getRequiredMargin() != null
                                ? trade.getRequiredMargin()
                                : trade.getInvestedAmount())
                        .sum();

        double availableCapital =
                totalCapital - usedCapital;

        return Math.max(availableCapital, 0.0);
    }

    public ClosedPositionResponse getClosedPositions() {

        List<PaperTrade> trades = repository.findByStatusIn(
                        List.of(
                                SignalStatus.TARGET_HIT,
                                SignalStatus.STOP_LOSS_HIT,
                                SignalStatus.CLOSED,
                                SignalStatus.MARKET_CLOSED
                        ))
                .stream()
                .sorted(Comparator.comparing(PaperTrade::getExitTime).reversed())
                .toList();

        List<ClosedPositionDto> positions = trades.stream()
                .map(this::mapToClosedPosition)
                .toList();

        int totalTrades = trades.size();

        int winningTrades = (int) trades.stream()
                .filter(t -> t.getProfitLoss() != null && t.getProfitLoss() > 0)
                .count();

        int losingTrades = (int) trades.stream()
                .filter(t -> t.getProfitLoss() != null && t.getProfitLoss() < 0)
                .count();

        double totalProfit = trades.stream()
                .filter(t -> t.getProfitLoss() != null && t.getProfitLoss() > 0)
                .mapToDouble(PaperTrade::getProfitLoss)
                .sum();

        double totalLoss = trades.stream()
                .filter(t -> t.getProfitLoss() != null && t.getProfitLoss() < 0)
                .mapToDouble(PaperTrade::getProfitLoss)
                .sum();

        double netProfit = trades.stream()
                .filter(t -> t.getProfitLoss() != null)
                .mapToDouble(PaperTrade::getProfitLoss)
                .sum();

        double winRate = totalTrades == 0
                ? 0.0
                : ((double) winningTrades / totalTrades) * 100;

        double averageProfit = winningTrades == 0
                ? 0.0
                : totalProfit / winningTrades;

        double averageLoss = losingTrades == 0
                ? 0.0
                : Math.abs(totalLoss) / losingTrades;

        double profitFactor = totalLoss == 0
                ? totalProfit
                : totalProfit / Math.abs(totalLoss);

        return ClosedPositionResponse.builder()
                .totalTrades(totalTrades)
                .winningTrades(winningTrades)
                .losingTrades(losingTrades)
                .winRate(winRate)
                .totalProfit(totalProfit)
                .totalLoss(Math.abs(totalLoss))
                .netProfit(netProfit)
                .averageProfit(averageProfit)
                .averageLoss(averageLoss)
                .profitFactor(profitFactor)
                .positions(positions)
                .build();
    }

    private ClosedPositionDto mapToClosedPosition(PaperTrade trade) {

        return ClosedPositionDto.builder()
                .tradeId(trade.getId())
                .signalId(trade.getSignalId())
                .symbol(trade.getSymbol())
                .signal(trade.getSignal())
                .entryPrice(trade.getEntryPrice())
                .exitPrice(trade.getExitPrice())
                .quantity(trade.getQuantity())
                .investedAmount(trade.getInvestedAmount())
                .profitLoss(trade.getProfitLoss())
                .targetPrice(trade.getTargetPrice())
                .stopLoss(trade.getStopLoss())
                .confidence(trade.getConfidence())
                .status(trade.getStatus().name())
                .exitReason(trade.getExitReason() != null
                        ? trade.getExitReason().name()
                        : null)
                .entryTime(trade.getEntryTime())
                .exitTime(trade.getExitTime())
                .closedAt(trade.getExitTime())
                .build();
    }

}