package com.hpcache.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BurstSchedulerTest {

    // Compress a full day into 200ms. Segment boundaries (approx):
    //   open ends at   2.6ms  → sleep 4ms  to land in morning
    //   morning ends at 15.4ms → sleep 18ms to land in midday
    //   midday ends at 184.6ms → sleep 187ms to land in afternoon
    //   afternoon ends at 197.4ms → sleep 199ms to land in close
    private static final long DAY_MS = 200L;

    @Test
    void initialRateIsOpenRate() {
        BurstScheduler scheduler = new BurstScheduler(DAY_MS);
        assertEquals(100_000, scheduler.getCurrentTickRate());
    }

    @Test
    void transitionsToMorningRate() throws InterruptedException {
        BurstScheduler scheduler = new BurstScheduler(DAY_MS);
        Thread.sleep(4);
        assertEquals(10_000, scheduler.getCurrentTickRate());
    }

    @Test
    void transitionsToMiddayRate() throws InterruptedException {
        BurstScheduler scheduler = new BurstScheduler(DAY_MS);
        Thread.sleep(18);
        assertEquals(1_000, scheduler.getCurrentTickRate());
    }

    @Test
    void transitionsToAfternoonRate() throws InterruptedException {
        BurstScheduler scheduler = new BurstScheduler(DAY_MS);
        Thread.sleep(187);
        assertEquals(10_000, scheduler.getCurrentTickRate());
    }

    @Test
    void transitionsToCloseRate() throws InterruptedException {
        BurstScheduler scheduler = new BurstScheduler(DAY_MS);
        Thread.sleep(199);
        assertEquals(100_000, scheduler.getCurrentTickRate());
    }

    @Test
    void resetRestartsDay() throws InterruptedException {
        BurstScheduler scheduler = new BurstScheduler(DAY_MS);
        Thread.sleep(4); // now in morning
        assertEquals(10_000, scheduler.getCurrentTickRate());
        scheduler.reset();
        assertEquals(100_000, scheduler.getCurrentTickRate()); // back to open
    }
}
