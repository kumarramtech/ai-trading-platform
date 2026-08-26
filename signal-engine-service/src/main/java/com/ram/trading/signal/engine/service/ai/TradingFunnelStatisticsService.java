package com.ram.trading.signal.engine.service.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class TradingFunnelStatisticsService {

    /*
     * ============================================================
     * STAGE 1 : TOTAL EVALUATIONS
     * ============================================================
     */

    private final AtomicLong totalEvaluations =
            new AtomicLong();


    /*
     * ============================================================
     * STAGE 2 : ENGINEERING FILTER
     * ============================================================
     */

    private final AtomicLong engineeringPassed =
            new AtomicLong();

    private final AtomicLong engineeringRejected =
            new AtomicLong();


    /*
     * ============================================================
     * STAGE 3 : AI PIPELINE
     * ============================================================
     */

    private final AtomicLong aiRequested =
            new AtomicLong();

    private final AtomicLong aiResponseReceived =
            new AtomicLong();

    private final AtomicLong aiBuy =
            new AtomicLong();

    private final AtomicLong aiSell =
            new AtomicLong();

    private final AtomicLong aiHold =
            new AtomicLong();

    private final AtomicLong aiTradeNotAllowed =
            new AtomicLong();


    /*
     * ============================================================
     * STAGE 4 : RISK GUARD
     * ============================================================
     */

    private final AtomicLong riskEvaluated =
            new AtomicLong();

    private final AtomicLong riskApproved =
            new AtomicLong();

    private final AtomicLong riskRejected =
            new AtomicLong();


    /*
     * ============================================================
     * STAGE 5 : POST PROCESSING
     * ============================================================
     */

    private final AtomicLong postProcessingEntered =
            new AtomicLong();

    private final AtomicLong signalSaved =
            new AtomicLong();

    private final AtomicLong opportunitySaved =
            new AtomicLong();


    /*
     * ============================================================
     * STAGE 6 : PAPER TRADING
     * ============================================================
     */

    private final AtomicLong paperTradeAttempted =
            new AtomicLong();

    private final AtomicLong paperTradeCreated =
            new AtomicLong();


    /*
     * ============================================================
     * TOTAL EVALUATION
     * ============================================================
     */

    public void recordTotalEvaluation() {

        totalEvaluations.incrementAndGet();
    }


    /*
     * ============================================================
     * ENGINEERING FILTER
     * ============================================================
     */

    public void recordEngineeringPassed() {

        engineeringPassed.incrementAndGet();
    }


    public void recordEngineeringRejected() {

        engineeringRejected.incrementAndGet();
    }


    /*
     * ============================================================
     * AI PIPELINE
     * ============================================================
     */

    public void recordAiRequested() {

        aiRequested.incrementAndGet();
    }


    public void recordAiResponseReceived() {

        aiResponseReceived.incrementAndGet();
    }


    public void recordAiBuy() {

        aiBuy.incrementAndGet();
    }


    public void recordAiSell() {

        aiSell.incrementAndGet();
    }


    public void recordAiHold() {

        aiHold.incrementAndGet();
    }


    public void recordAiTradeNotAllowed() {

        aiTradeNotAllowed.incrementAndGet();
    }


    /*
     * ============================================================
     * RISK GUARD
     * ============================================================
     */

    public void recordRiskEvaluated() {

        riskEvaluated.incrementAndGet();
    }


    public void recordRiskApproved() {

        riskApproved.incrementAndGet();
    }


    public void recordRiskRejected() {

        riskRejected.incrementAndGet();
    }


    /*
     * ============================================================
     * POST PROCESSING
     * ============================================================
     */

    public void recordPostProcessingEntered() {

        postProcessingEntered.incrementAndGet();
    }


    public void recordSignalSaved() {

        signalSaved.incrementAndGet();
    }


    public void recordOpportunitySaved() {

        opportunitySaved.incrementAndGet();
    }


    /*
     * ============================================================
     * PAPER TRADING
     * ============================================================
     */

    public void recordPaperTradeAttempted() {

        paperTradeAttempted.incrementAndGet();
    }


    public void recordPaperTradeCreated() {

        paperTradeCreated.incrementAndGet();
    }


    /*
     * ============================================================
     * STATISTICS
     * ============================================================
     */

    public void printStatistics() {

        long total = totalEvaluations.get();

        log.info("""

                =====================================================
                TRADING FUNNEL STATISTICS
                =====================================================

                TOTAL EVALUATIONS
                -----------------------------------------------------
                Total Evaluations              : {}

                ENGINEERING FILTER
                -----------------------------------------------------
                Engineering Passed             : {}
                Engineering Rejected           : {}
                Engineering Pass Rate          : {}%

                AI PIPELINE
                -----------------------------------------------------
                AI Requested                   : {}
                AI Responses Received          : {}
                AI BUY                         : {}
                AI SELL                        : {}
                AI HOLD                        : {}
                AI Trade Not Allowed           : {}

                RISK GUARD
                -----------------------------------------------------
                Risk Evaluated                 : {}
                Risk Approved                  : {}
                Risk Rejected                  : {}

                POST PROCESSING
                -----------------------------------------------------
                Post Processing Entered        : {}
                Signal Saved                   : {}
                Opportunity Saved              : {}

                PAPER TRADING
                -----------------------------------------------------
                Paper Trade Attempted          : {}
                Paper Trade Created            : {}

                =====================================================
                """,
                total,

                engineeringPassed.get(),
                engineeringRejected.get(),
                calculatePercentage(
                        engineeringPassed.get(),
                        total),

                aiRequested.get(),
                aiResponseReceived.get(),
                aiBuy.get(),
                aiSell.get(),
                aiHold.get(),
                aiTradeNotAllowed.get(),

                riskEvaluated.get(),
                riskApproved.get(),
                riskRejected.get(),

                postProcessingEntered.get(),
                signalSaved.get(),
                opportunitySaved.get(),

                paperTradeAttempted.get(),
                paperTradeCreated.get()
        );
    }


    /*
     * ============================================================
     * HELPER
     * ============================================================
     */

    private double calculatePercentage(
            long value,
            long total) {

        if (total == 0) {
            return 0.0;
        }

        return Math.round(
                value * 10000.0 / total
        ) / 100.0;
    }
}