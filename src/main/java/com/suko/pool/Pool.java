package com.suko.pool;

/**
 * Minimal interface for object pools to avoid allocation overhead.
 * 
 * @param <T> the type of objects to pool
 */
public interface Pool<T> {
    
    /**
     * Acquires an object from the pool. May allocate a new object if the pool is empty.
     * 
     * @return a pooled object or a newly allocated object
     */
    T acquire();
    
    /**
     * Attempts to acquire an object from the pool without allocating.
     * 
     * @return a pooled object or null if the pool is empty
     */
    default T tryAcquire() {
        return null;
    }
    
    /**
     * Releases an object back to the pool.
     * 
     * @param obj the object to release
     * @return true if successfully returned to the pool, false if dropped
     */
    boolean release(T obj);
}
