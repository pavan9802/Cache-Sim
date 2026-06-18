package com.hpcache.sim;

import net.jcip.annotations.ThreadSafe;

@ThreadSafe
public class BurstScheduler {

    private static final long TOTAL_MINUTES    = 390L;
    private static final long OPEN_END_MIN     = 5L;
    private static final long MORNING_END_MIN  = 30L;
    private static final long MIDDAY_END_MIN   = 360L;
    private static final long AFTERNOON_END_MIN= 385L;

    private final long openEndNanos;
    private final long morningEndNanos;
    private final long middayEndNanos;
    private final long afternoonEndNanos;

    private volatile long startNanos;

    public BurstScheduler(long simulatedDayDurationMs) {
        long durationNanos = simulatedDayDurationMs * 1_000_000L;
        openEndNanos      = durationNanos * OPEN_END_MIN      / TOTAL_MINUTES;
        morningEndNanos   = durationNanos * MORNING_END_MIN   / TOTAL_MINUTES;
        middayEndNanos    = durationNanos * MIDDAY_END_MIN    / TOTAL_MINUTES;
        afternoonEndNanos = durationNanos * AFTERNOON_END_MIN / TOTAL_MINUTES;
        startNanos = System.nanoTime();
    }

    public int getCurrentTickRate() {
        long elapsed = System.nanoTime() - startNanos;
        if (elapsed < openEndNanos)      return 100_000;
        if (elapsed < morningEndNanos)   return  10_000;
        if (elapsed < middayEndNanos)    return   1_000;
        if (elapsed < afternoonEndNanos) return  10_000;
        return 100_000;
    }

    public void reset() {
        startNanos = System.nanoTime();
    }
}
