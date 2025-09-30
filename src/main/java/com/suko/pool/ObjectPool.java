package com.suko.pool;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class ObjectPool<T> implements Pool<T> {
    private static final VarHandle HEAD, TAIL;
    
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            HEAD = l.findVarHandle(ObjectPool.class, "head", long.class);
            TAIL = l.findVarHandle(ObjectPool.class, "tail", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
    
    private final Object[] pool;
    private final int mask;
    private final Supplier<T> factory;
    private final Consumer<T> resetAction;
    
    @SuppressWarnings("unused") // Used by VarHandle
    private volatile long head = 0L;
    @SuppressWarnings("unused") // Used by VarHandle  
    private volatile long tail;
    
    public ObjectPool(Supplier<T> factory, Consumer<T> resetAction, int size) {
        int poolSize = nextPowerOfTwo(size);
        this.pool = new Object[poolSize];
        this.mask = poolSize - 1;
        this.factory = factory;
        this.resetAction = resetAction;
        
        // Pre-populate
        for (int i = 0; i < poolSize; i++) {
            pool[i] = factory.get();
        }
        TAIL.setVolatile(this, (long) poolSize);
    }
    
    @SuppressWarnings("unchecked")
    public T acquire() {
        while (true) {
            long currentHead = (long) HEAD.getVolatile(this);
            long currentTail = (long) TAIL.getVolatile(this);
            
            if (currentHead >= currentTail) {
                return factory.get();
            }
            
            if (HEAD.compareAndSet(this, currentHead, currentHead + 1)) {
                int index = (int) (currentHead & mask);
                T object = (T) pool[index];
                pool[index] = null;
                return object;
            }
        }
    }
    
    /**
     * Attempts to acquire an object from the pool without allocating.
     * 
     * @return a pooled object or null if the pool is empty
     */
    @SuppressWarnings("unchecked")
    public T tryAcquire() {
        while (true) {
            long currentHead = (long) HEAD.getVolatile(this);
            long currentTail = (long) TAIL.getVolatile(this);
            
            if (currentHead >= currentTail) {
                return null; // Pool is empty
            }
            
            if (HEAD.compareAndSet(this, currentHead, currentHead + 1)) {
                int index = (int) (currentHead & mask);
                T object = (T) pool[index];
                pool[index] = null;
                return object;
            }
        }
    }
    
    public boolean release(T object) {
        if (resetAction != null) {
            resetAction.accept(object);
        }
        
        while (true) {
            long currentTail = (long) TAIL.getVolatile(this);
            long currentHead = (long) HEAD.getVolatile(this);
            
            if (currentTail - currentHead >= pool.length) {
                return false;
            }
            
            if (TAIL.compareAndSet(this, currentTail, currentTail + 1)) {
                int index = (int) (currentTail & mask);
                pool[index] = object;
                return true;
            }
        }
    }
    
    /**
     * Gets the current head value (for external inspection).
     * 
     * @return the current head value
     */
    public long getHead() {
        return (long) HEAD.getVolatile(this);
    }
    
    /**
     * Gets the current tail value (for external inspection).
     * 
     * @return the current tail value
     */
    public long getTail() {
        return (long) TAIL.getVolatile(this);
    }
    
    private static int nextPowerOfTwo(int n) {
        return 1 << (32 - Integer.numberOfLeadingZeros(n - 1));
    }
}
