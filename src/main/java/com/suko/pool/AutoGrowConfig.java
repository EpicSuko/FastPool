package com.suko.pool;

/**
 * Configuration for auto-growing behavior in striped object pools.
 */
public final class AutoGrowConfig {
    public final int addStripesPerEvent;
    public final int cooldownMillis;
    public final int missBurstThreshold;
    public final int maxStripes;
    
    public AutoGrowConfig(int addStripesPerEvent, int cooldownMillis, int missBurstThreshold, int maxStripes) {
        this.addStripesPerEvent = Math.max(1, addStripesPerEvent);
        this.cooldownMillis = Math.max(0, cooldownMillis); // Allow 0 for instant growth
        this.missBurstThreshold = Math.max(1, missBurstThreshold);
        this.maxStripes = maxStripes; // <=0 means unbounded
    }
    
    public static AutoGrowConfig withDefaults(int stripeSize) {
        return new AutoGrowConfig(1, 25, stripeSize / 2, 0);
    }
}
