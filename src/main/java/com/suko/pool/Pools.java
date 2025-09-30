package com.suko.pool;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

public enum Pools {
    INSTANCE;
    
    private final ConcurrentHashMap<Class<?>, Pool<?>> pools = new ConcurrentHashMap<>();
    
    public <T> void create(Class<T> type, Supplier<T> factory, Consumer<T> reset, int size) {
        pools.put(type, new ObjectPool<>(factory, reset, size));
    }
    
    /**
     * Creates a striped object pool for the specified type.
     * 
     * @param type the class type to pool
     * @param factory the factory for creating new objects
     * @param reset the action to reset objects before returning to pool (may be null)
     * @param initialStripes the initial number of stripes (will be rounded up to power of 2)
     * @param stripeSize the size of each individual stripe (will be rounded up to power of 2)
     */
    public <T> void createStriped(Class<T> type, Supplier<T> factory, Consumer<T> reset, 
                                 int initialStripes, int stripeSize) {
        pools.put(type, new StripedObjectPool<>(factory, reset, initialStripes, stripeSize));
    }
    
    /**
     * Creates a striped object pool for the specified type with auto-grow enabled.
     * 
     * @param type the class type to pool
     * @param factory the factory for creating new objects
     * @param reset the action to reset objects before returning to pool (may be null)
     * @param initialStripes the initial number of stripes (will be rounded up to power of 2)
     * @param stripeSize the size of each individual stripe (will be rounded up to power of 2)
     * @param config the auto-grow configuration
     */
    public <T> void createStriped(Class<T> type, Supplier<T> factory, Consumer<T> reset, 
                                 int initialStripes, int stripeSize, StripedObjectPool.AutoGrowConfig config) {
        StripedObjectPool<T> pool = new StripedObjectPool<>(factory, reset, initialStripes, stripeSize);
        pool.enableAutoGrow(config);
        pools.put(type, pool);
    }
    
    /**
     * Checks if a pool exists for the specified type.
     * 
     * @param type the class type to check
     * @return true if a pool exists for this type
     */
    public boolean hasPool(Class<?> type) {
        return pools.containsKey(type);
    }
    
    /**
     * Enables auto-grow for a striped pool (no-op if not a striped pool).
     * 
     * @param type the class type
     * @param config the auto-grow configuration
     */
    @SuppressWarnings("unchecked")
    public <T> void enableAutoGrow(Class<T> type, StripedObjectPool.AutoGrowConfig config) {
        Pool<?> pool = pools.get(type);
        if (pool instanceof StripedObjectPool) {
            ((StripedObjectPool<T>) pool).enableAutoGrow(config);
        }
    }
    
    /**
     * Disables auto-grow for a striped pool (no-op if not a striped pool).
     * 
     * @param type the class type
     */
    @SuppressWarnings("unchecked")
    public <T> void disableAutoGrow(Class<T> type) {
        Pool<?> pool = pools.get(type);
        if (pool instanceof StripedObjectPool) {
            ((StripedObjectPool<T>) pool).disableAutoGrow();
        }
    }
    
    /**
     * Ensures the pool has at least the specified total capacity (only works for striped pools).
     * 
     * @param type the class type
     * @param minTotalCapacity the minimum total capacity required
     */
    @SuppressWarnings("unchecked")
    public <T> void ensureCapacity(Class<T> type, int minTotalCapacity) {
        Pool<?> pool = pools.get(type);
        if (pool instanceof StripedObjectPool) {
            ((StripedObjectPool<T>) pool).ensureCapacity(minTotalCapacity);
        }
    }
    
    @SuppressWarnings("unchecked")
    public <T> T acquire(Class<T> type) {
        Pool<T> pool = (Pool<T>) pools.get(type);
        if (pool == null) {
            throw new IllegalStateException("Pool not configured for type: " + type.getSimpleName());
        }
        return pool.acquire();
    }
    
    @SuppressWarnings("unchecked")
    public <T> boolean release(Class<T> type, T object) {
        Pool<T> pool = (Pool<T>) pools.get(type);
        return pool != null && pool.release(object);
    }
}
