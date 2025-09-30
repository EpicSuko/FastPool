# Fastpool

A high-performance, lock-free object pooling library for Java with support for both single and striped pools, auto-growing capacity, and zero-latency growth.

## Features

- **Lock-free operations** using CAS (Compare-And-Swap) loops
- **Low GC pressure** with allocation-free fast paths
- **Striped pools** for reduced contention in high-concurrency scenarios
- **Auto-growing capacity** with configurable growth policies
- **Zero-latency growth** (cooldown = 0) for instant capacity expansion
- **Thread-safe** MPMC (Multiple Producer, Multiple Consumer) design
- **Allocate-on-miss** fallback when pools are exhausted

## Quick Start

### Maven Dependency

```xml
<dependency>
    <groupId>com.suko</groupId>
    <artifactId>fastpool</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Basic Usage

#### Simple Object Pool

```java
import com.suko.pool.ObjectPool;

// Create a pool with factory and reset action
ObjectPool<MyObject> pool = new ObjectPool<>(
    () -> new MyObject(),           // factory
    obj -> obj.reset(),             // reset action
    64                              // pool size
);

// Acquire and release objects
MyObject obj = pool.acquire();
try {
    // use object
} finally {
    pool.release(obj);
}
```

#### Striped Pool with Auto-Grow

```java
import com.suko.pool.StripedObjectPool;

// Create striped pool with auto-grow
StripedObjectPool<MyObject> stripedPool = new StripedObjectPool<>(
    () -> new MyObject(),
    obj -> obj.reset(),
    4,                              // initial stripes
    64                              // stripe size
);

// Enable instant auto-grow (cooldown = 0)
StripedObjectPool.AutoGrowConfig config = new StripedObjectPool.AutoGrowConfig(1, 0, 1, 0);
stripedPool.enableAutoGrow(config);

// Use the pool
MyObject obj = stripedPool.acquire();
stripedPool.release(obj);
```

#### Registry and Wrapper (Pools + Pooled)

```java
import com.suko.pool.Pools;
import com.suko.pool.Pooled;

// Create pools for different types
Pools.INSTANCE.create(MyObject.class, MyObject::new, MyObject::reset, 64);
Pools.INSTANCE.createStriped(AnotherObject.class, AnotherObject::new, AnotherObject::reset, 2, 32);

// Use with try-with-resources
try (Pooled<MyObject> pooled = Pooled.get(MyObject.class)) {
    MyObject obj = pooled.get();
    // use object - automatically released on close()
}
```

## API Reference

### Core Interfaces

- **`Pool<T>`** - Base interface for all pools
  - `T acquire()` - Acquire object (may allocate if pool empty)
  - `T tryAcquire()` - Try to acquire without allocating (returns null if empty)
  - `boolean release(T obj)` - Release object back to pool

### Implementations

- **`ObjectPool<T>`** - Single-threaded pool with pre-populated objects
- **`StripedObjectPool<T>`** - Multi-stripe pool for high concurrency
  - `enableAutoGrow(AutoGrowConfig)` - Enable automatic capacity growth
  - `disableAutoGrow()` - Disable auto-growth
  - `ensureCapacity(int minCapacity)` - Ensure minimum total capacity
  - `stripeCount()`, `stripeSize()`, `totalCapacity()` - Pool metrics

### Registry and Utilities

- **`Pools`** - Singleton registry for type-based pool management
  - `create(Class<T>, Supplier<T>, Consumer<T>, int size)` - Create simple pool
  - `createStriped(Class<T>, Supplier<T>, Consumer<T>, int stripes, int stripeSize)` - Create striped pool
  - `acquire(Class<T>)`, `release(Class<T>, T)` - Type-based acquire/release
  - `hasPool(Class<?>)` - Check if pool exists for type

- **`Pooled<T>`** - AutoCloseable wrapper for automatic resource management
  - `static <T> Pooled<T> get(Class<T>)` - Get pooled wrapper
  - `T get()` - Access wrapped object
  - `close()` - Release both wrapper and object

## Configuration

### AutoGrowConfig

```java
StripedObjectPool.AutoGrowConfig config = new StripedObjectPool.AutoGrowConfig(
    1,                              // addStripesPerEvent
    0,                              // cooldownMillis (0 = instant growth)
    5,                              // missBurstThreshold
    10                              // maxStripes (0 = unbounded)
);

// Or use defaults
StripedObjectPool.AutoGrowConfig defaults = StripedObjectPool.AutoGrowConfig.withDefaults(64);
```

## Performance Characteristics

- **Lock-free**: No blocking operations, uses CAS loops
- **Low allocation**: Fast path is allocation-free
- **High throughput**: Striped design reduces contention
- **Scalable**: Auto-grow adapts to demand
- **GC-friendly**: Reuses objects, reduces pressure

## Thread Safety

- All pools are thread-safe and support MPMC (Multiple Producer, Multiple Consumer)
- No external synchronization required
- Lock-free design prevents deadlocks

## License

Apache License 2.0 - see LICENSE file for details.
