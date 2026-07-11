package com.ram.trading.market.data.service;

import com.ram.trading.market.data.dto.Tick;

public interface TickProcessor {

    void process(Tick tick);

}