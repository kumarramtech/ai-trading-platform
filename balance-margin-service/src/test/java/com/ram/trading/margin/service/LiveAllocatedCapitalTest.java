package com.ram.trading.margin.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Live sizing must never use more than the allocated capital, whatever Upstox holds. */
class LiveAllocatedCapitalTest {

    @Test
    void allocationCapsALargerUpstoxBalance() {
        // Rs 2L in Upstox, Rs 1L allocated, nothing used yet.
        assertEquals(100000.0, BalanceMarginServiceImpl.liveAvailable(200000, 200000, 100000));
    }

    @Test
    void fundsUsedTodayCountAgainstTheAllocation() {
        // Rs 30k blocked by open positions since the opening check.
        assertEquals(70000.0, BalanceMarginServiceImpl.liveAvailable(170000, 200000, 100000));
    }

    @Test
    void smallerUpstoxBalanceWins() {
        assertEquals(60000.0, BalanceMarginServiceImpl.liveAvailable(60000, 60000, 100000));
    }

    @Test
    void exhaustedAllocationLeavesNothing() {
        assertEquals(0.0, BalanceMarginServiceImpl.liveAvailable(80000, 200000, 100000));
    }

    @Test
    void fundsAddedDuringTheDayDoNotRaiseTheAllocation() {
        assertEquals(100000.0, BalanceMarginServiceImpl.liveAvailable(250000, 200000, 100000));
    }
}
