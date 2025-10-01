package com.suko.pool;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A lock-free, grow-only striped object pool that uses multiple independent ObjectPool instances
 * to reduce contention and support dynamic capacity growth without object migration.
 * 
 * <p>This implementation provides:
 * <ul>
 * <li>Lock-free operations using CAS loops and atomic directory swaps</li>
 * <li>Grow-only resizing by appending new stripes</li>
 * <li>Allocation-free fast path for acquire/release operations</li>
 * <li>Allocate-on-miss fallback when all stripes are empty</li>
 * <li>Thread-local probing to reduce contention</li>
 * </ul>
 * 
 * @param <T> the type of objects to pool
 */
public final class StripedObjectPool<T> implements Pool<T> {
    
    private static final int PROBE_LIMIT = 3;
    
    
    private final AtomicReference<Directory<T>> directory;
    private final Supplier<T> factory;
    private final Consumer<T> resetAction;
    private final int stripeSize;
    
    // Thread-local hint for stripe selection to reduce contention
    private final ThreadLocal<Integer> tlHint = ThreadLocal.withInitial(() -> 
        Thread.currentThread().hashCode() & Integer.MAX_VALUE);
    
    // Auto-grow related fields
    private volatile AutoGrowConfig autoCfg;
    private final AtomicBoolean growthGuard = new AtomicBoolean(false);
    private final AtomicLong missDebt = new AtomicLong(0);
    private volatile long lastGrowNanos;
    
    /**
     * Immutable directory containing the array of stripes and metadata.
     */
    private static final class Directory<T> {
        final ObjectPool<T>[] stripes;
        final int mask; // For fast modulo when length is power of 2
        
        @SuppressWarnings("unchecked")
        Directory(int stripeCount, Supplier<T> factory, Consumer<T> resetAction, int stripeSize) {
            this.stripes = new ObjectPool[stripeCount];
            this.mask = stripeCount - 1; // Assumes power of 2
            
            for (int i = 0; i < stripeCount; i++) {
                this.stripes[i] = new ObjectPool<>(factory, resetAction, stripeSize);
            }
        }
        
        Directory(ObjectPool<T>[] stripes) {
            this.stripes = stripes;
            this.mask = stripes.length - 1; // Assumes power of 2
        }
    }
    
    /**
     * Creates a new striped object pool.
     * 
     * @param factory the factory for creating new objects
     * @param resetAction the action to reset objects before returning to pool (may be null)
     * @param initialStripes the initial number of stripes (will be rounded up to power of 2)
     * @param stripeSize the size of each individual stripe (will be rounded up to power of 2)
     */
    public StripedObjectPool(Supplier<T> factory, Consumer<T> resetAction, 
                           int initialStripes, int stripeSize) {
        if (factory == null) {
            throw new IllegalArgumentException("Factory cannot be null");
        }
        if (initialStripes <= 0) {
            throw new IllegalArgumentException("Initial stripes must be positive");
        }
        if (stripeSize <= 0) {
            throw new IllegalArgumentException("Stripe size must be positive");
        }
        
        this.factory = factory;
        this.resetAction = resetAction;
        this.stripeSize = nextPowerOfTwo(stripeSize);
        
        int actualStripes = nextPowerOfTwo(initialStripes);
        this.directory = new AtomicReference<>(new Directory<>(actualStripes, factory, resetAction, this.stripeSize));
    }
    
    /**
     * Acquires an object from the pool. If all probed stripes are empty, allocates a new object.
     * 
     * @return a pooled object or a newly allocated object
     */
    public T acquire() {
        Directory<T> dir = directory.get();
        int stripeCount = dir.stripes.length;
        int startIdx = tlHint.get() & dir.mask;
        
        // Probe up to PROBE_LIMIT stripes starting from thread-local hint
        for (int i = 0; i < PROBE_LIMIT && i < stripeCount; i++) {
            int idx = (startIdx + i) & dir.mask;
            ObjectPool<T> stripe = dir.stripes[idx];
            
            // Try to acquire from this stripe without allocating
            T obj = stripe.tryAcquire();
            if (obj != null) {
                tlHint.set(idx);
                if (autoCfg != null) {
                    decayMissDebt();
                }
                return obj;
            }
        }
        
        // All probed stripes were empty, allocate new object
        if (autoCfg != null) {
            maybeGrowOnMiss();
        }
        return factory.get();
    }
    
    /**
     * Attempts to acquire an object from the pool without allocating.
     * 
     * @return a pooled object or null if all probed stripes are empty
     */
    public T tryAcquire() {
        Directory<T> dir = directory.get();
        int stripeCount = dir.stripes.length;
        int startIdx = tlHint.get() & dir.mask;
        
        // Probe up to PROBE_LIMIT stripes starting from thread-local hint
        for (int i = 0; i < PROBE_LIMIT && i < stripeCount; i++) {
            int idx = (startIdx + i) & dir.mask;
            ObjectPool<T> stripe = dir.stripes[idx];
            
            // Try to acquire from this stripe without allocating
            T obj = stripe.tryAcquire();
            if (obj != null) {
                tlHint.set(idx);
                return obj;
            }
        }
        
        return null; // All probed stripes were empty
    }
    
    /**
     * Releases an object back to the pool. Attempts to return to a stripe, drops if all are full.
     * 
     * @param obj the object to release
     * @return true if successfully returned to a stripe, false if dropped
     */
    public boolean release(T obj) {
        if (obj == null) {
            return false;
        }
        
        Directory<T> dir = directory.get();
        int stripeCount = dir.stripes.length;
        int startIdx = tlHint.get() & dir.mask;
        
        // Try to release to probed stripes first
        for (int i = 0; i < PROBE_LIMIT && i < stripeCount; i++) {
            int idx = (startIdx + i) & dir.mask;
            ObjectPool<T> stripe = dir.stripes[idx];
            if (stripe.release(obj)) {
                tlHint.set(idx);
                if (autoCfg != null) {
                    decayMissDebt();
                }
                return true;
            }
        }
        
        // If all probed stripes are full, try a few more before giving up
        for (int i = PROBE_LIMIT; i < Math.min(PROBE_LIMIT * 2, stripeCount); i++) {
            int idx = (startIdx + i) & dir.mask;
            ObjectPool<T> stripe = dir.stripes[idx];
            if (stripe.release(obj)) {
                tlHint.set(idx);
                if (autoCfg != null) {
                    decayMissDebt();
                }
                return true;
            }
        }
        
        return false; // All stripes are full, drop the object
    }
    
    /**
     * Adds the specified number of new stripes to the pool.
     * 
     * @param count the number of stripes to add
     */
    public void addStripes(int count) {
        if (count <= 0) {
            return;
        }
        
        while (true) {
            Directory<T> current = directory.get();
            ObjectPool<T>[] currentStripes = current.stripes;
            int currentLength = currentStripes.length;
            
            // Create new array with additional stripes
            ObjectPool<T>[] newStripes = Arrays.copyOf(currentStripes, currentLength + count);
            
            // Initialize new stripes
            for (int i = currentLength; i < newStripes.length; i++) {
                newStripes[i] = new ObjectPool<>(factory, resetAction, stripeSize);
            }
            
            Directory<T> newDir = new Directory<>(newStripes);
            
            // Atomically swap the directory
            if (directory.compareAndSet(current, newDir)) {
                break;
            }
            // CAS failed, retry
        }
    }
    
    /**
     * Ensures the pool has at least the specified total capacity by adding stripes if necessary.
     * 
     * @param minTotalCapacity the minimum total capacity required
     */
    public void ensureCapacity(int minTotalCapacity) {
        int currentCapacity = totalCapacity();
        if (currentCapacity >= minTotalCapacity) {
            return;
        }
        
        int neededCapacity = minTotalCapacity - currentCapacity;
        int stripesToAdd = (neededCapacity + stripeSize - 1) / stripeSize; // Ceiling division
        
        if (stripesToAdd > 0) {
            addStripes(stripesToAdd);
        }
    }
    
    /**
     * Returns the current number of stripes in the pool.
     * 
     * @return the number of stripes
     */
    public int stripeCount() {
        return directory.get().stripes.length;
    }
    
    /**
     * Returns the total capacity of the pool (sum of all stripe capacities).
     * 
     * @return the total capacity
     */
    public int totalCapacity() {
        return stripeCount() * stripeSize;
    }
    
    /**
     * Returns the size of each individual stripe.
     * 
     * @return the stripe size
     */
    public int stripeSize() {
        return stripeSize;
    }
    
    /**
     * Enables auto-growing with the specified configuration.
     * 
     * @param config the auto-grow configuration
     */
    public void enableAutoGrow(AutoGrowConfig config) {
        this.autoCfg = config;
    }
    
    /**
     * Disables auto-growing.
     */
    public void disableAutoGrow() {
        this.autoCfg = null;
    }
    
    /**
     * Returns whether auto-growing is enabled.
     * 
     * @return true if auto-growing is enabled
     */
    public boolean isAutoGrowEnabled() {
        return autoCfg != null;
    }
    
    /**
     * Enables auto-growing with background maintenance using a scheduled executor.
     * 
     * @param executor the scheduled executor for background maintenance
     * @param config the auto-grow configuration
     */
    public void enableAutoGrowWithMaintenance(ScheduledExecutorService executor, AutoGrowConfig config) {
        this.autoCfg = config;
        
        // Schedule periodic maintenance to decay debt
        executor.scheduleAtFixedRate(() -> {
            long currentDebt = missDebt.get();
            if (currentDebt > 0) {
                missDebt.addAndGet(-Math.min(currentDebt, 1));
            }
        }, 10, 10, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Attempts to grow the pool based on miss pressure and configuration.
     * This method is called on allocation-on-miss in acquire().
     */
    private void maybeGrowOnMiss() {
        AutoGrowConfig config = autoCfg;
        if (config == null) {
            return;
        }
        
        long debt = missDebt.incrementAndGet();
        
        // Check if we've hit the threshold
        if (debt < config.missBurstThreshold) {
            return;
        }
        
        // Check cooldown (skip if cooldownMillis is 0 for instant growth)
        if (config.cooldownMillis > 0) {
            long now = System.nanoTime();
            long cooldownNanos = config.cooldownMillis * 1_000_000L;
            if (now - lastGrowNanos < cooldownNanos) {
                return;
            }
        }
        
        // Check max stripes limit
        if (config.maxStripes > 0 && stripeCount() >= config.maxStripes) {
            return;
        }
        
        // Try to acquire growth guard and grow
        if (growthGuard.compareAndSet(false, true)) {
            try {
                addStripes(config.addStripesPerEvent);
                if (config.cooldownMillis > 0) {
                    lastGrowNanos = System.nanoTime();
                }
                missDebt.addAndGet(-config.missBurstThreshold);
            } finally {
                growthGuard.set(false);
            }
        }
    }
    
    /**
     * Decays miss debt when a pooled object is successfully acquired or released.
     * This helps prevent runaway growth in steady state.
     */
    private void decayMissDebt() {
        long currentDebt = missDebt.get();
        if (currentDebt > 0) {
            missDebt.addAndGet(-Math.min(currentDebt, 1));
        }
    }
    
    /**
     * Calculates the next power of 2 greater than or equal to n.
     */
    private static int nextPowerOfTwo(int n) {
        return 1 << (32 - Integer.numberOfLeadingZeros(n - 1));
    }
}
