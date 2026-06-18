package com.hpcache.sim;


public record MarketEvent(
    String symbol,
    double bidPrice,
    double askPrice,
    long volume,
    long timestampNanos,
    int sequenceNumber) 
{
    public MarketEvent {
        if (bidPrice >= askPrice) {
            throw new IllegalArgumentException(
            "bidPrice must be < askPrice, got bid=" + bidPrice + " ask=" + askPrice);
        }
    }

    public double spread() {
        return askPrice - bidPrice;
    }

    public double midPrice() {
        return (bidPrice + askPrice) / 2.0;
    }
    
  }
