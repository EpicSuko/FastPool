package com.suko.pool;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Test to verify that the Pool interface works correctly with both ObjectPool and StripedObjectPool.
 */
public class PoolInterfaceTest {
    
    private static class TestObject {
        private int value;
        
        public int getValue() { return value; }
        public void setValue(int value) { this.value = value; }
        public void reset() { this.value = 0; }
    }
    
    @Before
    public void setUp() {
        // Note: We can't clear the pools since they're in a singleton enum
        // Tests should work with existing state or create new types
    }
    
    @Test
    public void testObjectPoolInterface() {
        // Test that ObjectPool implements Pool interface correctly
        Pool<TestObject> pool = new ObjectPool<>(TestObject::new, TestObject::reset, 4);
        
        // Test acquire
        TestObject obj1 = pool.acquire();
        Assert.assertNotNull("Object should be acquired", obj1);
        
        // Test tryAcquire
        TestObject obj2 = pool.tryAcquire();
        Assert.assertNotNull("Object should be acquired via tryAcquire", obj2);
        
        // Test release
        Assert.assertTrue("Object should be released successfully", pool.release(obj1));
        Assert.assertTrue("Object should be released successfully", pool.release(obj2));
    }
    
    @Test
    public void testStripedObjectPoolInterface() {
        // Test that StripedObjectPool implements Pool interface correctly
        Pool<TestObject> pool = new StripedObjectPool<>(TestObject::new, TestObject::reset, 2, 4);
        
        // Test acquire
        TestObject obj1 = pool.acquire();
        Assert.assertNotNull("Object should be acquired", obj1);
        
        // Test tryAcquire
        TestObject obj2 = pool.tryAcquire();
        Assert.assertNotNull("Object should be acquired via tryAcquire", obj2);
        
        // Test release
        Assert.assertTrue("Object should be released successfully", pool.release(obj1));
        Assert.assertTrue("Object should be released successfully", pool.release(obj2));
    }
    
    @Test
    public void testPoolsInterfaceDispatch() {
        // Test that Pools correctly dispatches through the Pool interface
        
        // Create legacy pool for a specific test type
        Class<TestObject> testType = TestObject.class;
        Pools.INSTANCE.create(testType, TestObject::new, TestObject::reset, 4);
        
        // Test acquire through Pools
        TestObject obj1 = Pools.INSTANCE.acquire(testType);
        Assert.assertNotNull("Object should be acquired through Pools", obj1);
        
        // Test release through Pools
        Assert.assertTrue("Object should be released through Pools", 
            Pools.INSTANCE.release(testType, obj1));
    }
    
    @Test
    public void testPoolsStripedFeatures() {
        // Test striped pool features through Pools
        
        // Create striped pool with auto-grow for a specific test type
        Class<TestObject> testType = TestObject.class;
        StripedObjectPool.AutoGrowConfig config = new StripedObjectPool.AutoGrowConfig(1, 0, 1, 0);
        Pools.INSTANCE.createStriped(testType, TestObject::new, TestObject::reset, 1, 2, config);
        
        // Test hasPool
        Assert.assertTrue("Pool should exist", Pools.INSTANCE.hasPool(testType));
        
        // Test enableAutoGrow (should be no-op since already enabled)
        Pools.INSTANCE.enableAutoGrow(testType, config);
        
        // Test ensureCapacity
        Pools.INSTANCE.ensureCapacity(testType, 10);
        
        // Test disableAutoGrow
        Pools.INSTANCE.disableAutoGrow(testType);
    }
    
    @Test
    public void testPooledWithStripedWrapper() {
        // Test that Pooled.get creates a striped wrapper pool by default
        
        // Create a test pool for a specific test type
        Class<TestObject> testType = TestObject.class;
        Pools.INSTANCE.create(testType, TestObject::new, TestObject::reset, 4);
        
        // Get a pooled object (should create striped wrapper pool)
        Pooled<TestObject> pooled = Pooled.get(testType);
        Assert.assertNotNull("Pooled object should be created", pooled);
        
        // Test that wrapper pool exists
        Assert.assertTrue("Wrapper pool should exist", Pools.INSTANCE.hasPool(Pooled.class));
        
        // Test pooled object functionality
        TestObject obj = pooled.get();
        Assert.assertNotNull("Wrapped object should be accessible", obj);
        
        // Test close (should return both objects to pools)
        pooled.close();
    }
}
