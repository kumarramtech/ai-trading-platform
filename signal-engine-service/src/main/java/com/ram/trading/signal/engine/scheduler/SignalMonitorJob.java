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

        service.checkOpenSignals();
    }
}