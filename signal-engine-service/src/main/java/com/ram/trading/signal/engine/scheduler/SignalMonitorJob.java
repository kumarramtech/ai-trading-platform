package com.ram.trading.signal.engine.scheduler;

import com.ram.trading.signal.engine.service.SignalMonitorService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SignalMonitorJob {

    private final SignalMonitorService service;

    @Scheduled(fixedRate = 60000)
    public void monitorSignals() {
        if (calendar != null && !calendar.isTradingDay()) {
            return; // weekend or NSE holiday: no session, nothing to monitor
        }
        service.checkOpenSignals();
    }

    private com.ram.trading.signal.engine.calendar.TradingCalendar calendar;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCalendar(com.ram.trading.signal.engine.calendar.TradingCalendar calendar) {
        this.calendar = calendar;
    }
}