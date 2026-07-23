package com.ram.trading.stock.service.instument;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
public class BootstrapStatusServiceImpl
        implements BootstrapStatusService {

    private final AtomicBoolean bootstrapRunning =
            new AtomicBoolean(false);

    private final AtomicBoolean bootstrapCompleted =
            new AtomicBoolean(false);

    private final AtomicBoolean bootstrapFailed =
            new AtomicBoolean(false);

    @Override
    public void bootstrapStarted() {

        System.out.println("######## bootstrapStarted() ########");
        bootstrapRunning.set(true);
        bootstrapCompleted.set(false);
        bootstrapFailed.set(false);

        log.info("Bootstrap Status : STARTED");
    }

    @Override
    public void bootstrapCompleted() {

        bootstrapRunning.set(false);
        bootstrapCompleted.set(true);
        bootstrapFailed.set(false);

        log.info("Bootstrap Status : COMPLETED");
    }

    @Override
    public void bootstrapFailed() {

        bootstrapRunning.set(false);
        bootstrapCompleted.set(false);
        bootstrapFailed.set(true);

        log.error("Bootstrap Status : FAILED");
    }

    @Override
    public boolean isBootstrapRunning() {
        return bootstrapRunning.get();
    }

    @Override
    public boolean isBootstrapCompleted() {
        return bootstrapCompleted.get();
    }

    @Override
    public boolean isReadyForTrading() {

        return bootstrapCompleted.get()
                && !bootstrapRunning.get()
                && !bootstrapFailed.get();
    }

    @Override
    public void reset() {

        bootstrapRunning.set(false);
        bootstrapCompleted.set(false);
        bootstrapFailed.set(false);

        log.info("Bootstrap Status Reset");
    }
}