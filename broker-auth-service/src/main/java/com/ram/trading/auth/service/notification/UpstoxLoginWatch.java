package com.ram.trading.auth.service.notification;

import com.ram.trading.auth.service.constant.BrokerConstants;
import com.ram.trading.auth.service.entity.BrokerSession;
import com.ram.trading.auth.service.exception.BrokerSessionNotFoundException;
import com.ram.trading.auth.service.service.BrokerSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Warns on Slack before a missing Upstox login costs a trading day. The token expires at
 * 03:30 IST, so each weekday morning it reminds until someone logs in, and it stops by
 * itself once the session is valid. Weekdays only; there is no holiday calendar, so a
 * market holiday gets the reminders too.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UpstoxLoginWatch {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Duration TOKEN_ALERT_GAP = Duration.ofMinutes(30);
    private static final LocalTime ALERTS_FROM = LocalTime.of(7, 0);
    private static final LocalTime ALERTS_UNTIL = LocalTime.of(15, 30);

    private final BrokerSessionService sessions;
    private final BrokerAuthNotifier notifier;

    private LocalDateTime lastTokenAlert;

    @Schedules({
            @Scheduled(cron = "0 0,15,25 7 * * MON-FRI", zone = "Asia/Kolkata"),
            @Scheduled(cron = "0 30 8 * * MON-FRI", zone = "Asia/Kolkata"),
            @Scheduled(cron = "0 0 9 * * MON-FRI", zone = "Asia/Kolkata")
    })
    public void morningCheck() {
        try {
            check(LocalDateTime.now(IST));
        } catch (RuntimeException ex) {
            log.error("Upstox login check failed", ex);
            notifier.loginNeeded("The login check itself failed (" + ex.getClass().getSimpleName()
                    + "), so the token state is unknown.");
        }
    }

    void check(LocalDateTime now) {
        BrokerSession session;
        try {
            session = sessions.getBrokerSession(BrokerConstants.UPSTOX);
        } catch (BrokerSessionNotFoundException ex) {
            notifier.loginNeeded("No Upstox session is stored.");
            return;
        }
        LocalDateTime expiresAt = session.getExpiresAt();
        if (expiresAt == null || !expiresAt.isAfter(now)) {
            notifier.loginNeeded("The Upstox token has expired" + (expiresAt == null ? "." : " (" + expiresAt.toLocalTime() + " today)."));
        }
    }

    /**
     * A service was refused a token. Services ask often, so this alerts at most every 30
     * minutes, and only in weekday trading hours: overnight the token is expected to be gone.
     */
    public void tokenRequestFailed(RuntimeException ex) {
        tokenRequestFailed(ex, LocalDateTime.now(IST));
    }

    synchronized void tokenRequestFailed(RuntimeException ex, LocalDateTime now) {
        DayOfWeek day = now.getDayOfWeek();
        boolean weekend = day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
        LocalTime t = now.toLocalTime();
        if (weekend || t.isBefore(ALERTS_FROM) || !t.isBefore(ALERTS_UNTIL)) {
            return;
        }
        if (lastTokenAlert != null && Duration.between(lastTokenAlert, now).compareTo(TOKEN_ALERT_GAP) < 0) {
            return;
        }
        lastTokenAlert = now;
        notifier.tokenRequestFailed(ex.getMessage());
    }
}
