package com.ram.trading.signal.engine.scheduler;

import com.ram.trading.signal.engine.client.NotificationClient;
import com.ram.trading.signal.engine.contant.SignalStatus;
import com.ram.trading.signal.engine.dto.notification.NotificationChannel;
import com.ram.trading.signal.engine.dto.notification.NotificationRequest;
import com.ram.trading.signal.engine.entity.PaperTrade;
import com.ram.trading.signal.engine.repo.PaperTradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class DailyTradingSummaryScheduler {

    private final PaperTradeRepository paperTradeRepository;

    private final NotificationClient notificationClient;

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * Runs every trading day at 3:40 PM IST.
     *
     * 15:40 = 3:40 PM
     */
    @Scheduled(
            cron = "0 40 15 * * MON-FRI",
            zone = "Asia/Kolkata"
    )
    public void sendDailyTradingSummary() {

        log.info("==============================================");
        log.info("DAILY TRADING SUMMARY SCHEDULER STARTED");
        log.info("==============================================");

        LocalDate today = LocalDate.now();

        LocalDateTime startOfDay =
                today.atStartOfDay();

        LocalDateTime startOfNextDay =
                today.plusDays(1).atStartOfDay();

        List<PaperTrade> todayTrades =
                paperTradeRepository.findAll()
                        .stream()
                        .filter(trade ->
                                trade.getEntryTime() != null
                                        && !trade.getEntryTime()
                                        .isBefore(startOfDay)
                                        && trade.getEntryTime()
                                        .isBefore(startOfNextDay))
                        .sorted(
                                Comparator.comparing(
                                        PaperTrade::getEntryTime))
                        .toList();

        log.info(
                "Today's paper trades found : {}",
                todayTrades.size());

        if (todayTrades.isEmpty()) {

            sendNoTradesNotification(today);

            log.info(
                    "No paper trades found for {}",
                    today);

            return;
        }

        /*
         * ============================================================
         * 1. TRADE DETAILS NOTIFICATION
         * ============================================================
         */

        String tradeMessage =
                buildTradeDetailsMessage(
                        today,
                        todayTrades);

        sendSlackNotification(
                "TODAY'S PAPER TRADES",
                tradeMessage);

        /*
         * ============================================================
         * 2. DAILY SUMMARY NOTIFICATION
         * ============================================================
         */

        String summaryMessage =
                buildDailySummaryMessage(
                        today,
                        todayTrades);

        sendSlackNotification(
                "TODAY'S TRADING SUMMARY",
                summaryMessage);

        log.info("==============================================");
        log.info("DAILY TRADING SUMMARY COMPLETED");
        log.info("==============================================");
    }

    private String buildTradeDetailsMessage(
            LocalDate today,
            List<PaperTrade> trades) {

        StringBuilder message =
                new StringBuilder();

        message.append("📊 TODAY'S PAPER TRADES\n");
        message.append("Date: ")
                .append(today.format(DATE_FORMATTER))
                .append("\n\n");

        int tradeNumber = 1;

        for (PaperTrade trade : trades) {

            message.append(tradeNumber++)
                    .append(". ")
                    .append(trade.getSignal())
                    .append(" | ")
                    .append(trade.getSymbol())
                    .append("\n");

            message.append("   Quantity : ")
                    .append(trade.getQuantity())
                    .append("\n");

            message.append("   Entry    : ₹")
                    .append(formatAmount(trade.getEntryPrice()))
                    .append("\n");

            if (trade.getExitPrice() != null) {

                message.append("   Exit     : ₹")
                        .append(formatAmount(trade.getExitPrice()))
                        .append("\n");

            } else {

                message.append("   Exit     : OPEN\n");
            }

            message.append("   Invested : ₹")
                    .append(formatAmount(trade.getInvestedAmount()))
                    .append("\n");

            if (trade.getProfitLoss() != null) {

                message.append("   P&L      : ₹")
                        .append(formatAmount(trade.getProfitLoss()))
                        .append("\n");

            } else {

                message.append("   P&L      : OPEN\n");
            }

            if (trade.getEntryTime() != null) {

                message.append("   Entry Time: ")
                        .append(
                                trade.getEntryTime()
                                        .format(TIME_FORMATTER))
                        .append("\n");
            }

            if (trade.getExitTime() != null) {

                message.append("   Exit Time : ")
                        .append(
                                trade.getExitTime()
                                        .format(TIME_FORMATTER))
                        .append("\n");
            }

            message.append("   Status    : ")
                    .append(trade.getStatus())
                    .append("\n\n");
        }

        return message.toString();
    }

    private String buildDailySummaryMessage(
            LocalDate today,
            List<PaperTrade> trades) {

        long totalTrades =
                trades.size();

        long completedTrades =
                trades.stream()
                        .filter(trade ->
                                trade.getProfitLoss() != null)
                        .count();

        long openTrades =
                trades.stream()
                        .filter(trade ->
                                SignalStatus.OPEN
                                        .equals(trade.getStatus()))
                        .count();

        long winningTrades =
                trades.stream()
                        .filter(trade ->
                                trade.getProfitLoss() != null
                                        && trade.getProfitLoss() > 0)
                        .count();

        long losingTrades =
                trades.stream()
                        .filter(trade ->
                                trade.getProfitLoss() != null
                                        && trade.getProfitLoss() < 0)
                        .count();

        long breakevenTrades =
                trades.stream()
                        .filter(trade ->
                                trade.getProfitLoss() != null
                                        && trade.getProfitLoss() == 0)
                        .count();

        double amountUsed =
                trades.stream()
                        .mapToDouble(trade ->
                                trade.getInvestedAmount() != null
                                        ? trade.getInvestedAmount()
                                        : 0.0)
                        .sum();

        double totalProfitLoss =
                trades.stream()
                        .filter(trade ->
                                trade.getProfitLoss() != null)
                        .mapToDouble(
                                PaperTrade::getProfitLoss)
                        .sum();

        String result;

        if (totalProfitLoss > 0) {
            result = "PROFIT";
        } else if (totalProfitLoss < 0) {
            result = "LOSS";
        } else {
            result = "BREAK-EVEN";
        }

        StringBuilder message =
                new StringBuilder();

        message.append("📈 TODAY'S TRADING SUMMARY\n");
        message.append("Date: ")
                .append(today.format(DATE_FORMATTER))
                .append("\n\n");

        message.append("Total Trades      : ")
                .append(totalTrades)
                .append("\n");

        message.append("Completed Trades  : ")
                .append(completedTrades)
                .append("\n");

        message.append("Open Trades       : ")
                .append(openTrades)
                .append("\n");

        message.append("Winning Trades    : ")
                .append(winningTrades)
                .append("\n");

        message.append("Losing Trades     : ")
                .append(losingTrades)
                .append("\n");

        message.append("Breakeven Trades  : ")
                .append(breakevenTrades)
                .append("\n\n");

        message.append("Amount Used       : ₹")
                .append(formatAmount(amountUsed))
                .append("\n");

        message.append("Total Profit/Loss : ₹")
                .append(formatAmount(totalProfitLoss))
                .append("\n\n");

        message.append("Day Result        : ")
                .append(result)
                .append("\n");

        return message.toString();
    }

    private void sendNoTradesNotification(
            LocalDate today) {

        String message =
                "📊 TODAY'S TRADING SUMMARY\n"
                        + "Date: "
                        + today.format(DATE_FORMATTER)
                        + "\n\n"
                        + "No paper trades were executed today.\n"
                        + "Amount Used       : ₹0.00\n"
                        + "Total Profit/Loss : ₹0.00";

        sendSlackNotification(
                "TODAY'S TRADING SUMMARY",
                message);
    }

    private void sendSlackNotification(
            String title,
            String message) {

        NotificationRequest request =
                NotificationRequest.builder()
                        .channel(NotificationChannel.SLACK)
                        .title(title)
                        .message(message)
                        .build();

        notificationClient
                .sendNotification(request)
                .doOnSuccess(response ->
                        log.info(
                                "Slack notification sent successfully | Title={}",
                                title))
                .doOnError(ex ->
                        log.error(
                                "Failed to send Slack notification | Title={}",
                                title,
                                ex))
                .subscribe();
    }

    private String formatAmount(
            Double amount) {

        if (amount == null) {
            return "0.00";
        }

        return String.format(
                "%.2f",
                amount);
    }
}