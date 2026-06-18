package com.hpcache.sim;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import net.jcip.annotations.NotThreadSafe;

@NotThreadSafe
public class MarketDataSimulator {
    private final List<String> symbols;
    private final Map<String, Double> currentPrices;
    private final Map<String, Double> volatilities;
    private final double drift;
    private final double dt;
    private int sequenceCounter;

    public MarketDataSimulator(List<String> symbols, double basePrice, double volatility) {
        this.symbols = List.copyOf(symbols);
        currentPrices = new HashMap<>();
        volatilities = new HashMap<>();
        for (String symbol : symbols) {
            currentPrices.put(symbol, basePrice);
            volatilities.put(symbol, volatility);
        }
        drift = 0.0;
        dt = 1.0 / 252;
        sequenceCounter = 0;
    }

    public MarketEvent nextTick(String symbol) {
        double currentPrice = currentPrices.get(symbol);
        double vol = volatilities.get(symbol);
        double Z = ThreadLocalRandom.current().nextGaussian();
        double nextPrice = Math.max(currentPrice * Math.exp((drift - 0.5 * vol * vol) * dt + vol * Math.sqrt(dt) * Z), 0.01);
        currentPrices.put(symbol, nextPrice);

        double bidPrice = nextPrice - 0.01;
        double askPrice = nextPrice + 0.01;
        long volume = (long) (ThreadLocalRandom.current().nextDouble() * 1000);
        long timestampNanos = System.nanoTime();
        int sequenceNumber = ++sequenceCounter;

        return new MarketEvent(symbol, bidPrice, askPrice, volume, timestampNanos, sequenceNumber);
    }

    public MarketEvent nextTick() {
        String symbol = symbols.get(ThreadLocalRandom.current().nextInt(symbols.size()));
        return nextTick(symbol);
    }

    public void setVolatility(String symbol, double volatility) {
        volatilities.put(symbol, volatility);
    }

    public Map<String, Double> getCurrentPrices() {
        return new HashMap<>(currentPrices);
    }
}
