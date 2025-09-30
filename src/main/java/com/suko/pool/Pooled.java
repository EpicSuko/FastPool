package com.suko.pool;

public class Pooled<T> implements AutoCloseable {
    private T object;
    private Class<T> type;
    private boolean closed = false;
    
    Pooled() {} // Package private
    
    void init(T object, Class<T> type) {
        this.object = object;
        this.type = type;
        this.closed = false;
    }
    
    public T get() {
        if (closed) throw new IllegalStateException("Already closed");
        return object;
    }
    
    @Override
    public void close() {
        if (!closed) {
            Pools.INSTANCE.release(type, object);
            Pools.INSTANCE.release(Pooled.class, this);
            reset();
        }
    }
    
    void reset() {
        this.object = null;
        this.type = null;
        this.closed = true;
    }
    
    @SuppressWarnings("unchecked")
    public static <T> Pooled<T> get(Class<T> type) {
        T object = Pools.INSTANCE.acquire(type);
        
        // Ensure wrapper pool exists (striped by default)
        if (!Pools.INSTANCE.hasPool(Pooled.class)) {
            Pools.INSTANCE.createStriped(Pooled.class, Pooled::new, Pooled::reset, 1, 64);
        }
        
        Pooled<T> pooled = (Pooled<T>) Pools.INSTANCE.acquire(Pooled.class);
        pooled.init(object, type);
        return pooled;
    }
}
