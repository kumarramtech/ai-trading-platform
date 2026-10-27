package com.ram.trading.signal.engine.scheduler;

import com.ram.trading.signal.engine.service.MarketCloseService;
import com.ram.trading.signal.engine.service.shadow.ShadowTradeService;
import com.ram.trading.signal.engine.util.TradingSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class MarketCloseScheduler {

    private final MarketCloseService marketCloseService;

    private final TradingSessionService tradingSessionService;

    private final ShadowTradeService shadowTradeService;

    /*
     * Runs every minute of the afternoon; shouldForceCloseTrades() limits it to
     * trading.market.close .. trading.market.end, so a later pass retries any
     * position the previous pass could not close.
     */
    @Scheduled(
            cron = "${trading.market.square-off-cron:0 * 14-15 * * MON-FRI}",
            zone = "Asia/Kolkata"
    )
    public void autoSquareOff() {

        if (!tradingSessionService.shouldForceCloseTrades()) {
            return;
        }

        marketCloseService.closeOpenPositions().subscribe();

        // Shadow trades close with the real ones (a no-op once none are open).
        shadowTradeService.closeAllAtMarketClose();
    }

}