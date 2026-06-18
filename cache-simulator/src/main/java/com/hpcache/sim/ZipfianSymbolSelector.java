package com.hpcache.sim;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import net.jcip.annotations.ThreadSafe;

@ThreadSafe
public class ZipfianSymbolSelector {
    private final List<String> symbols;
    private final double[] cumulativeProbabilities;
    private final double skew;

    public ZipfianSymbolSelector(List<String> symbols, double skew) {
        this.symbols = List.copyOf(symbols);
        this.skew = skew;
        this.cumulativeProbabilities = new double[symbols.size()];
        
        double sum = 0.0;
        for (int i = 0; i < symbols.size(); i++) {
            sum += 1.0 / Math.pow(i + 1, skew);
            cumulativeProbabilities[i] = sum;
        }
        for (int i = 0; i < cumulativeProbabilities.length; i++) {
            cumulativeProbabilities[i] /= sum;
        }
    }

    public String select(){
        double r = ThreadLocalRandom.current().nextDouble();
        // binarySearch returns -(insertion point) - 1 when r is not found exactly.
        // Insertion point is the index where r would be inserted to keep the array sorted,
        // which is the CDF bucket r fell into. E.g. r=0.7 in [0.545, 0.818, 1.0] gives
        // result=-2, so index = -(-2) - 1 = 1. Exact hits (result >= 0) are used directly.
        int result = Arrays.binarySearch(cumulativeProbabilities, r);
        int index = result < 0 ? -result - 1 : result;
        index = Math.min(index, symbols.size() - 1);
        return symbols.get(index);
    }               
            
    public Map<String, Double> getProbabilities() {
        var probs = new LinkedHashMap<String, Double>();
        for (int i = 0; i < symbols.size(); i++) {
            double p = i == 0 ? cumulativeProbabilities[0] : cumulativeProbabilities[i] - cumulativeProbabilities[i - 1];
            probs.put(symbols.get(i), p);
        }
        return probs;
    }

    public double skew() {
      return skew;
    }
  
}
