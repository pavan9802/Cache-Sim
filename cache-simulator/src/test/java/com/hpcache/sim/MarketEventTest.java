package com.hpcache.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MarketEventTest {

    @Test
    void spreadReturnsAskMinusBid() {
        MarketEvent e = new MarketEvent("AAPL", 99.99, 100.01, 100L, System.nanoTime(), 1);
        assertEquals(100.01 - 99.99, e.spread(), 1e-9);
    }

    @Test
    void midPriceReturnsMidpoint() {
        MarketEvent e = new MarketEvent("AAPL", 99.99, 100.01, 100L, System.nanoTime(), 1);
        assertEquals((99.99 + 100.01) / 2.0, e.midPrice(), 1e-9);
    }

    @Test
    void rejectsBidEqualToAsk() {
        assertThrows(IllegalArgumentException.class,
            () -> new MarketEvent("AAPL", 100.0, 100.0, 0L, 0L, 0));
    }

    @Test
    void rejectsBidGreaterThanAsk() {
        assertThrows(IllegalArgumentException.class,
            () -> new MarketEvent("AAPL", 100.01, 99.99, 0L, 0L, 0));
    }
}
