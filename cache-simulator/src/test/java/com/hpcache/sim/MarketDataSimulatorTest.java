package com.hpcache.sim;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.RepeatedTest;

import static org.junit.jupiter.api.Assertions.*;

class MarketDataSimulatorTest {

    private static List<String> buildSymbols() {
        List<String> symbols = new ArrayList<>(100);
        symbols.add("AAPL");
        symbols.add("MSFT");
        symbols.add("GOOGL");
        for (int i = 4; i <= 100; i++) {
            symbols.add(String.format("SYM%03d", i));
        }
        return symbols;
    }

    @RepeatedTest(10)
    void generates1000TicksWithPositivePrices() {
        MarketDataSimulator sim = new MarketDataSimulator(buildSymbols(), 100.0, 0.2);
        for (int i = 0; i < 1_000; i++) {
            MarketEvent e = sim.nextTick("AAPL");
            assertTrue(e.bidPrice() > 0, "bidPrice must be > 0, got " + e.bidPrice());
            assertTrue(e.askPrice() > 0, "askPrice must be > 0, got " + e.askPrice());
        }
    }

    @RepeatedTest(10)
    void consecutiveTicksDiffer() {
        MarketDataSimulator sim = new MarketDataSimulator(buildSymbols(), 100.0, 0.2);
        MarketEvent prev = sim.nextTick("AAPL");
        for (int i = 0; i < 999; i++) {
            MarketEvent curr = sim.nextTick("AAPL");
            assertNotEquals(prev.bidPrice(), curr.bidPrice(),
                "Consecutive ticks should differ (GBM random walk)");
            prev = curr;
        }
    }

    @RepeatedTest(10)
    void sequenceNumbersIncrementByOne() {
        MarketDataSimulator sim = new MarketDataSimulator(buildSymbols(), 100.0, 0.2);
        int prev = sim.nextTick("AAPL").sequenceNumber();
        for (int i = 0; i < 999; i++) {
            int curr = sim.nextTick("AAPL").sequenceNumber();
            assertEquals(prev + 1, curr, "Sequence numbers must increment by 1");
            prev = curr;
        }
    }
}
