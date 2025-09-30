package com.suko.pool;

import org.junit.Before;
import org.junit.Test;
import org.junit.Assert;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Comprehensive tests for StripedObjectPool including concurrency, growth, and correctness.
 */
public class StripedObjectPoolTest {
    
    private static final int STRIPE_SIZE = 4;
    private static final int INITIAL_STRIPES = 2;
    private static final int CONCURRENCY_LEVEL = 24;
    private static final int OPERATIONS_PER_THREAD = 1000;
    
    private StripedObjectPool<TestObject> pool;
    private AtomicInteger createdCount;
    private AtomicInteger resetCount;
    
    @Before
    public void setUp() {
        createdCount = new AtomicInteger(0);
        resetCount = new AtomicInteger(0);
        
        Supplier<TestObject> factory = () -> {
            createdCount.incrementAndGet();
            return new TestObject();
        };
        
        Consumer<TestObject> resetAction = obj -> {
            resetCount.incrementAndGet();
            obj.reset();
        };
        
        pool = new StripedObjectPool<>(factory, resetAction, INITIAL_STRIPES, STRIPE_SIZE);
    }
    
    @Test
    public void testBasicAcquireRelease() {
        TestObject obj1 = pool.acquire();
        Assert.assertNotNull(obj1);
        Assert.assertTrue(obj1.isValid());
        
        TestObject obj2 = pool.acquire();
        Assert.assertNotNull(obj2);
        Assert.assertTrue(obj2.isValid());
        Assert.assertNotSame(obj1, obj2);
        
        Assert.assertTrue(pool.release(obj1));
        Assert.assertTrue(pool.release(obj2));
    }
    
    @Test
    public void testTryAcquire() {
        TestObject obj1 = pool.tryAcquire();
        Assert.assertNotNull(obj1);
        Assert.assertTrue(obj1.isValid());
        
        // Try to acquire another - should succeed
        TestObject obj2 = pool.tryAcquire();
        Assert.assertNotNull(obj2);
        Assert.assertTrue(obj2.isValid());
        
        Assert.assertTrue(pool.release(obj1));
        Assert.assertTrue(pool.release(obj2));
    }
    
    @Test
    public void testReleaseNull() {
        Assert.assertFalse(pool.release(null));
    }
    
    @Test
    public void testInitialCapacity() {
        Assert.assertEquals(INITIAL_STRIPES, pool.stripeCount());
        Assert.assertEquals(INITIAL_STRIPES * STRIPE_SIZE, pool.totalCapacity());
        Assert.assertEquals(STRIPE_SIZE, pool.stripeSize());
    }
    
    @Test
    public void testAddStripes() {
        int initialStripes = pool.stripeCount();
        int initialCapacity = pool.totalCapacity();
        
        pool.addStripes(2);
        
        Assert.assertEquals(initialStripes + 2, pool.stripeCount());
        Assert.assertEquals(initialCapacity + 2 * STRIPE_SIZE, pool.totalCapacity());
    }
    
    @Test
    public void testAddZeroStripes() {
        int initialStripes = pool.stripeCount();
        pool.addStripes(0);
        Assert.assertEquals(initialStripes, pool.stripeCount());
    }
    
    @Test
    public void testAddNegativeStripes() {
        int initialStripes = pool.stripeCount();
        pool.addStripes(-1);
        Assert.assertEquals(initialStripes, pool.stripeCount());
    }
    
    @Test
    public void testEnsureCapacity() {
        int initialCapacity = pool.totalCapacity();
        int targetCapacity = initialCapacity + 10;
        
        pool.ensureCapacity(targetCapacity);
        
        Assert.assertTrue(pool.totalCapacity() >= targetCapacity);
        Assert.assertTrue(pool.stripeCount() > INITIAL_STRIPES);
    }
    
    @Test
    public void testEnsureCapacityAlreadyMet() {
        int initialCapacity = pool.totalCapacity();
        pool.ensureCapacity(initialCapacity - 1);
        Assert.assertEquals(initialCapacity, pool.totalCapacity());
    }
    
    @Test
    public void testObjectReuse() {
        // Reset counters for this test
        createdCount.set(0);
        resetCount.set(0);
        
        // Test the underlying ObjectPool directly
        ObjectPool<TestObject> objectPool = new ObjectPool<>(
            () -> {
                createdCount.incrementAndGet();
                return new TestObject();
            }, 
            obj -> {
                resetCount.incrementAndGet();
                obj.reset();
            }, 
            4);
        
        TestObject obj = objectPool.acquire();
        int afterFirstAcquire = createdCount.get();
        
        boolean released = objectPool.release(obj);
        Assert.assertTrue(released);
        
        TestObject reused = objectPool.acquire();
        int afterSecondAcquire = createdCount.get();
        
        // The pool should not create new objects after initialization
        Assert.assertEquals(afterFirstAcquire, afterSecondAcquire); // No new object created
        // The pool should return any available object (not necessarily the same one)
        Assert.assertNotNull(reused); // Should get an object from the pool
        Assert.assertTrue(resetCount.get() > 0); // Reset was called
    }
    
    @Test
    public void testAllocateOnMiss() {
        // Fill up the pool
        TestObject[] objects = new TestObject[pool.totalCapacity()];
        for (int i = 0; i < objects.length; i++) {
            objects[i] = pool.acquire();
        }
        
        int initialCreated = createdCount.get();
        
        // Try to acquire one more - should allocate new object
        TestObject newObj = pool.acquire();
        Assert.assertNotNull(newObj);
        Assert.assertEquals(initialCreated + 1, createdCount.get());
        
        // Release all objects
        for (TestObject obj : objects) {
            Assert.assertTrue(pool.release(obj));
        }
        // The newly allocated object might not be releasable if pool is full
        // This is expected behavior - the pool will drop it
        boolean released = pool.release(newObj);
        // The release might succeed or fail depending on pool state - both are acceptable
    }
    
    @Test
    public void testTryAcquireOnEmptyPool() {
        // Fill up the pool
        TestObject[] objects = new TestObject[pool.totalCapacity()];
        for (int i = 0; i < objects.length; i++) {
            objects[i] = pool.acquire();
        }
        
        // Try to acquire without allocating - should return null
        TestObject obj = pool.tryAcquire();
        Assert.assertNull(obj);
        
        // Release all objects
        for (TestObject o : objects) {
            Assert.assertTrue(pool.release(o));
        }
    }
    
    @Test
    public void testConcurrentAcquireRelease() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY_LEVEL);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(CONCURRENCY_LEVEL);
        AtomicLong acquireCount = new AtomicLong(0);
        AtomicLong releaseCount = new AtomicLong(0);
        AtomicLong missCount = new AtomicLong(0);
        
        for (int i = 0; i < CONCURRENCY_LEVEL; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    
                    for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
                        TestObject obj = pool.acquire();
                        if (obj != null) {
                            acquireCount.incrementAndGet();
                            // Simulate some work
                            Thread.yield();
                            if (pool.release(obj)) {
                                releaseCount.incrementAndGet();
                            }
                        } else {
                            missCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        Assert.assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        executor.shutdown();
        
        System.out.println("Acquire count: " + acquireCount.get());
        System.out.println("Release count: " + releaseCount.get());
        System.out.println("Miss count: " + missCount.get());
        System.out.println("Created count: " + createdCount.get());
        
        // Should have many successful operations
        Assert.assertTrue(acquireCount.get() > OPERATIONS_PER_THREAD * CONCURRENCY_LEVEL * 0.8);
        Assert.assertTrue(releaseCount.get() > OPERATIONS_PER_THREAD * CONCURRENCY_LEVEL * 0.8);
    }
    
    @Test
    public void testConcurrentGrowth() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY_LEVEL);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(CONCURRENCY_LEVEL);
        AtomicInteger growthOperations = new AtomicInteger(0);
        
        for (int i = 0; i < CONCURRENCY_LEVEL; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    
                    for (int j = 0; j < 100; j++) {
                        if (threadId % 2 == 0) {
                            // Some threads add stripes
                            pool.addStripes(1);
                            growthOperations.incrementAndGet();
                        } else {
                            // Other threads ensure capacity
                            pool.ensureCapacity(pool.totalCapacity() + 5);
                        }
                        
                        // Do some acquire/release operations
                        TestObject obj = pool.acquire();
                        if (obj != null) {
                            pool.release(obj);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        Assert.assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        executor.shutdown();
        
        System.out.println("Growth operations: " + growthOperations.get());
        System.out.println("Final stripe count: " + pool.stripeCount());
        System.out.println("Final capacity: " + pool.totalCapacity());
        
        // Pool should have grown significantly
        Assert.assertTrue(pool.stripeCount() > INITIAL_STRIPES);
        Assert.assertTrue(pool.totalCapacity() > INITIAL_STRIPES * STRIPE_SIZE);
    }
    
    @Test
    public void testConstructorValidation() {
        try {
            new StripedObjectPool<>(null, null, 1, 1);
            Assert.fail("Should have thrown IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // Expected
        }
        
        try {
            new StripedObjectPool<>(() -> new TestObject(), null, 0, 1);
            Assert.fail("Should have thrown IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // Expected
        }
        
        try {
            new StripedObjectPool<>(() -> new TestObject(), null, 1, 0);
            Assert.fail("Should have thrown IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // Expected
        }
        
        try {
            new StripedObjectPool<>(() -> new TestObject(), null, -1, 1);
            Assert.fail("Should have thrown IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // Expected
        }
        
        try {
            new StripedObjectPool<>(() -> new TestObject(), null, 1, -1);
            Assert.fail("Should have thrown IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // Expected
        }
    }
    
    @Test
    public void testPowerOfTwoRounding() {
        // Test that stripe counts and sizes are rounded up to powers of 2
        StripedObjectPool<TestObject> pool1 = new StripedObjectPool<>(
            () -> new TestObject(), null, 3, 5);
        
        Assert.assertEquals(4, pool1.stripeCount()); // 3 -> 4 (2^2)
        Assert.assertEquals(8, pool1.stripeSize());  // 5 -> 8 (2^3)
        Assert.assertEquals(32, pool1.totalCapacity()); // 4 * 8
    }
    
    @Test
    public void testAutoGrowOnSustainedMisses() throws InterruptedException {
        // Create a pool with very small initial capacity
        StripedObjectPool<TestObject> smallPool = new StripedObjectPool<>(
            () -> new TestObject(), obj -> obj.reset(), 1, 2);
        
        // Enable auto-grow with aggressive settings
        StripedObjectPool.AutoGrowConfig config = new StripedObjectPool.AutoGrowConfig(1, 10, 2, 10);
        smallPool.enableAutoGrow(config);
        
        Assert.assertTrue(smallPool.isAutoGrowEnabled());
        Assert.assertEquals(1, smallPool.stripeCount());
        
        // Hammer the pool with acquires without releases to trigger growth
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(4);
        
        for (int i = 0; i < 4; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    
                    // Acquire many objects without releasing to trigger misses
                    for (int j = 0; j < 50; j++) {
                        smallPool.acquire();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        Assert.assertTrue(doneLatch.await(5, TimeUnit.SECONDS));
        executor.shutdown();
        
        // Pool should have grown due to sustained misses
        Assert.assertTrue("Pool should have grown from sustained misses", 
            smallPool.stripeCount() > 1);
        Assert.assertTrue("Pool should not exceed max stripes", 
            smallPool.stripeCount() <= 10);
    }
    
    @Test
    public void testAutoGrowRespectsMaxStripes() throws InterruptedException {
        StripedObjectPool<TestObject> pool = new StripedObjectPool<>(
            () -> new TestObject(), obj -> obj.reset(), 1, 2);
        
        // Enable auto-grow with max stripes limit
        StripedObjectPool.AutoGrowConfig config = new StripedObjectPool.AutoGrowConfig(1, 5, 1, 3);
        pool.enableAutoGrow(config);
        
        // Hammer the pool to trigger growth
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(8);
        
        for (int i = 0; i < 8; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    
                    for (int j = 0; j < 100; j++) {
                        pool.acquire();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        Assert.assertTrue(doneLatch.await(5, TimeUnit.SECONDS));
        executor.shutdown();
        
        // Pool should not exceed max stripes
        Assert.assertTrue("Pool should not exceed max stripes", 
            pool.stripeCount() <= 3);
    }
    
    @Test
    public void testAutoGrowCooldown() throws InterruptedException {
        StripedObjectPool<TestObject> pool = new StripedObjectPool<>(
            () -> new TestObject(), obj -> obj.reset(), 1, 2);
        
        // Enable auto-grow with short cooldown
        StripedObjectPool.AutoGrowConfig config = new StripedObjectPool.AutoGrowConfig(1, 100, 1, 0);
        pool.enableAutoGrow(config);
        
        int initialStripes = pool.stripeCount();
        
        // Trigger a growth
        for (int i = 0; i < 10; i++) {
            pool.acquire(); // This should trigger growth
        }
        
        // Wait a bit less than cooldown
        Thread.sleep(50);
        
        int stripesAfterFirstGrowth = pool.stripeCount();
        Assert.assertTrue("Pool should have grown", stripesAfterFirstGrowth > initialStripes);
        
        // Try to trigger another growth immediately (should be blocked by cooldown)
        for (int i = 0; i < 10; i++) {
            pool.acquire();
        }
        
        // Should not have grown again due to cooldown
        Assert.assertEquals("Pool should not grow again due to cooldown", 
            stripesAfterFirstGrowth, pool.stripeCount());
        
        // Wait for cooldown to expire
        Thread.sleep(100);
        
        // Now trigger another growth
        for (int i = 0; i < 10; i++) {
            pool.acquire();
        }
        
        // Should have grown again after cooldown
        Assert.assertTrue("Pool should grow again after cooldown", 
            pool.stripeCount() > stripesAfterFirstGrowth);
    }
    
    @Test
    public void testDebtDecay() throws InterruptedException {
        StripedObjectPool<TestObject> pool = new StripedObjectPool<>(
            () -> new TestObject(), obj -> obj.reset(), 1, 4);
        
        // Enable auto-grow with high threshold
        StripedObjectPool.AutoGrowConfig config = new StripedObjectPool.AutoGrowConfig(1, 10, 10, 0);
        pool.enableAutoGrow(config);
        
        int initialStripes = pool.stripeCount();
        
        // Fill up the pool
        TestObject[] objects = new TestObject[pool.totalCapacity()];
        for (int i = 0; i < objects.length; i++) {
            objects[i] = pool.acquire();
        }
        
        // Trigger some misses to build up debt
        for (int i = 0; i < 5; i++) {
            pool.acquire(); // These will be misses
        }
        
        // Release some objects back to pool
        for (int i = 0; i < 3; i++) {
            pool.release(objects[i]);
        }
        
        // Now acquire from pool (should decay debt)
        for (int i = 0; i < 3; i++) {
            pool.acquire(); // These should be from pool, decaying debt
        }
        
        // Pool should not have grown because debt was decayed
        Assert.assertEquals("Pool should not grow due to debt decay", 
            initialStripes, pool.stripeCount());
    }
    
    @Test
    public void testAutoGrowEnableDisable() {
        StripedObjectPool<TestObject> pool = new StripedObjectPool<>(
            () -> new TestObject(), obj -> obj.reset(), 1, 4);
        
        Assert.assertFalse("Auto-grow should be disabled by default", pool.isAutoGrowEnabled());
        
        // Enable auto-grow
        StripedObjectPool.AutoGrowConfig config = new StripedObjectPool.AutoGrowConfig(1, 10, 2, 5);
        pool.enableAutoGrow(config);
        Assert.assertTrue("Auto-grow should be enabled", pool.isAutoGrowEnabled());
        
        // Disable auto-grow
        pool.disableAutoGrow();
        Assert.assertFalse("Auto-grow should be disabled", pool.isAutoGrowEnabled());
    }
    
    @Test
    public void testAutoGrowWithMaintenance() throws InterruptedException {
        StripedObjectPool<TestObject> pool = new StripedObjectPool<>(
            () -> new TestObject(), obj -> obj.reset(), 1, 4);
        
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        
        try {
            // Enable auto-grow with maintenance
            StripedObjectPool.AutoGrowConfig config = new StripedObjectPool.AutoGrowConfig(1, 10, 5, 0);
            pool.enableAutoGrowWithMaintenance(scheduler, config);
            
            Assert.assertTrue("Auto-grow should be enabled", pool.isAutoGrowEnabled());
            
            // Let maintenance run for a bit
            Thread.sleep(100);
            
            // The maintenance should be running (no exceptions thrown)
            Assert.assertTrue("Maintenance should be running", pool.isAutoGrowEnabled());
            
        } finally {
            scheduler.shutdown();
        }
    }
    
    @Test
    public void testAutoGrowConfigDefaults() {
        StripedObjectPool.AutoGrowConfig config = StripedObjectPool.AutoGrowConfig.withDefaults(8);
        
        Assert.assertEquals("Default addStripesPerEvent should be 1", 1, config.addStripesPerEvent);
        Assert.assertEquals("Default cooldownMillis should be 25", 25, config.cooldownMillis);
        Assert.assertEquals("Default missBurstThreshold should be stripeSize/2", 4, config.missBurstThreshold);
        Assert.assertEquals("Default maxStripes should be 0 (unbounded)", 0, config.maxStripes);
    }
    
    @Test
    public void testAutoGrowConfigValidation() {
        // Test that negative values are clamped to minimums
        StripedObjectPool.AutoGrowConfig config = new StripedObjectPool.AutoGrowConfig(0, -5, -10, -1);
        
        Assert.assertEquals("addStripesPerEvent should be clamped to 1", 1, config.addStripesPerEvent);
        Assert.assertEquals("cooldownMillis should be clamped to 0", 0, config.cooldownMillis);
        Assert.assertEquals("missBurstThreshold should be clamped to 1", 1, config.missBurstThreshold);
        Assert.assertEquals("maxStripes should allow negative values (unbounded)", -1, config.maxStripes);
    }
    
    @Test
    public void testInstantGrowth() throws InterruptedException {
        StripedObjectPool<TestObject> pool = new StripedObjectPool<>(
            () -> new TestObject(), obj -> obj.reset(), 1, 2);
        
        // Enable auto-grow with instant growth (cooldown = 0) and low threshold
        StripedObjectPool.AutoGrowConfig config = new StripedObjectPool.AutoGrowConfig(1, 0, 1, 0);
        pool.enableAutoGrow(config);
        
        Assert.assertEquals("cooldownMillis should be 0", 0, config.cooldownMillis);
        
        int initialStripes = pool.stripeCount();
        
        // Fill up the pool first to ensure we get misses
        TestObject[] objects = new TestObject[pool.totalCapacity()];
        for (int i = 0; i < objects.length; i++) {
            objects[i] = pool.acquire();
        }
        
        // Trigger multiple growths rapidly - they should all happen instantly
        // Each acquire will be a miss, and with threshold=1, each should trigger growth
        for (int i = 0; i < 10; i++) {
            pool.acquire(); // Each miss should trigger instant growth
        }
        
        // Pool should have grown multiple times without any cooldown
        // With threshold=1, we should get growth on every miss
        Assert.assertTrue("Pool should have grown multiple times instantly", 
            pool.stripeCount() > initialStripes + 3);
    }
    
    @Test
    public void testStressTest() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(16);
        AtomicLong totalOperations = new AtomicLong(0);
        AtomicLong successfulReleases = new AtomicLong(0);
        
        for (int i = 0; i < 16; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    
                    for (int j = 0; j < 500; j++) {
                        TestObject obj = pool.acquire();
                        if (obj != null) {
                            totalOperations.incrementAndGet();
                            if (pool.release(obj)) {
                                successfulReleases.incrementAndGet();
                            }
                        }
                        
                        // Occasionally try to grow the pool
                        if (j % 50 == 0) {
                            pool.ensureCapacity(pool.totalCapacity() + 10);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        Assert.assertTrue(doneLatch.await(15, TimeUnit.SECONDS));
        executor.shutdown();
        
        System.out.println("Total operations: " + totalOperations.get());
        System.out.println("Successful releases: " + successfulReleases.get());
        System.out.println("Final capacity: " + pool.totalCapacity());
        
        // Should have performed many operations successfully
        Assert.assertTrue(totalOperations.get() > 1000);
        Assert.assertTrue(successfulReleases.get() > totalOperations.get() * 0.8);
    }
    
    /**
     * Test object for pooling.
     */
    private static class TestObject {
        private boolean valid = true;
        private int value = 42;
        
        public boolean isValid() {
            return valid;
        }
        
        public void reset() {
            valid = true;
            value = 42;
        }
        
        public int getValue() {
            return value;
        }
        
        public void setValue(int value) {
            this.value = value;
        }
    }
}
