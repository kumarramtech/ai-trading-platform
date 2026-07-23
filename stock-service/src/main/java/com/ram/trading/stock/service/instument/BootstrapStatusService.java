package com.ram.trading.stock.service.instument;

public interface BootstrapStatusService {

    void bootstrapStarted();

    void bootstrapCompleted();

    void bootstrapFailed();

    boolean isBootstrapRunning();

    boolean isBootstrapCompleted();

    boolean isReadyForTrading();

    void reset();
}