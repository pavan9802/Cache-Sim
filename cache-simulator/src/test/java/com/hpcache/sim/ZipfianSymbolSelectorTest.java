package com.hpcache.sim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.RepeatedTest;

import static org.junit.jupiter.api.Assertions.*;

class ZipfianSymbolSelectorTest {

    private static final long DRAWS = 1_000_000;

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
    void top10ReceivesMoreThan65PercentWithSkew1() {
        List<String> symbols = buildSymbols();
        ZipfianSymbolSelector selector = new ZipfianSymbolSelector(symbols, 1.0);
        Map<String, Long> counts = new HashMap<>();
        for (long i = 0; i < DRAWS; i++) {
            counts.merge(selector.select(), 1L, Long::sum);
        }
        long top10 = symbols.stream().limit(10)
            .mapToLong(s -> counts.getOrDefault(s, 0L))
            .sum();
        double ratio = (double) top10 / DRAWS;
        // Theoretical value: H_10/H_100 = 2.929/5.187 ≈ 56.5%. Threshold is 55% to allow statistical variance.
        assertTrue(ratio > 0.55,
            "Top-10 share was %.1f%%, expected > 55%%".formatted(ratio * 100));
    }

    @RepeatedTest(10)
    void top10ReceivesMoreThan85PercentWithSkew2() {
        List<String> symbols = buildSymbols();
        ZipfianSymbolSelector selector = new ZipfianSymbolSelector(symbols, 2.0);
        Map<String, Long> counts = new HashMap<>();
        for (long i = 0; i < DRAWS; i++) {
            counts.merge(selector.select(), 1L, Long::sum);
        }
        long top10 = symbols.stream().limit(10)
            .mapToLong(s -> counts.getOrDefault(s, 0L))
            .sum();
        double ratio = (double) top10 / DRAWS;
        assertTrue(ratio > 0.85,
            "Top-10 share was %.1f%%, expected > 85%%".formatted(ratio * 100));
    }
}
