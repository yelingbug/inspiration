/*
 * Copyright 2012 LMAX Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lmax.disruptor;

import sun.misc.Unsafe;

import com.lmax.disruptor.util.Util;


class LhsPadding
{
    protected long p1, p2, p3, p4, p5, p6, p7;
}

class Value extends LhsPadding
{
    protected volatile long value;
}

class RhsPadding extends Value
{
    protected long p9, p10, p11, p12, p13, p14, p15;
}

/**
 * <p>Concurrent sequence class used for tracking the progress of
 * the ring buffer and event processors.  Support a number
 * of concurrent operations including CAS and order writes.
 *
 * <p>Also attempts to be more efficient with regards to false
 * sharing by adding padding around the volatile field.
 */
public class Sequence extends RhsPadding
{
    static final long INITIAL_VALUE = -1L;
    private static final Unsafe UNSAFE;
    private static final long VALUE_OFFSET;

    static
    {
        UNSAFE = Util.getUnsafe();
        try
        {
            VALUE_OFFSET = UNSAFE.objectFieldOffset(Value.class.getDeclaredField("value"));
        }
        catch (final Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    /**
     * Create a sequence initialised to -1.
     */
    public Sequence()
    {
        this(INITIAL_VALUE);
    }

    /**
     * Create a sequence with a specified initial value.
     *
     * @param initialValue The initial value for this sequence.
     */
    /**
     * 涉及到putOrderedLong的操作这里有描述：
     * http://ifeve.com/how-does-atomiclong-lazyset-work/中提到了putOrderedLong
     *
     * 另外，AtomicXXX系类中存在lazySet方法跟putOrderedLong这个方法也有关系，并且
     * 在Stackoverflow中也找到了这样的话(http://stackoverflow.com/questions/1468007/atomicinteger-lazyset-vs-set)：
     *
     * lazySet can be used for rmw inter thread communication, because xchg is atomic, as for visibility, when writer
     * thread process modify a cache line location, reader thread's processor will see it at the next read, because the
     * cache coherence protocol of intel cpu will garantee LazySet works, but the cache line will be updated at the next
     * read, again, the CPU has to be modern enough.
     * http://sc.tamu.edu/systems/eos/nehalem.pdf For Nehalem which is a multi-processor platform, the processors
     * have the ability to “snoop” (eavesdrop) the address bus for other processor’s accesses to system memory and to
     * their internal caches. They use this snooping ability to keep their internal caches consistent both with system
     * memory and with the caches in other interconnected processors. If through snooping one processor detects
     * that another processor intends to write to a memory location that it currently has cached in Shared state, the
     * snooping processor will invalidate its cache block forcing it to perform a cache line fill the next time it accesses
     * the same memory location.
     *
     * oracle hotspot jdk for x86 cpu architecture-> lazySet == unsafe.putOrderedLong == xchg rw( asm instruction
     * that serve as a soft barrier costing 20 cycles on nehelem intel cpu) on x86 (x86_64) such a barrier is much cheaper
     * performance-wise than volatile or AtomicLong getAndAdd ,
     *
     * In an one producer, one consumer queue scenario, xchg soft barrier can force the line of codes before the lazySet(sequence+1)
     * for producer thread to happen BEFORE any consumer thread code that will consume (work on) the new data, of course
     * consumer thread will need to check atomically that producer sequence was incremented by exactly one using a
     * compareAndSet (sequence, sequence + 1). I traced after Hotspot source code to find the exact mapping of the lazySet to
     * cpp code: http://hg.openjdk.java.net/jdk7/jdk7/hotspot/file/9b0ca45cd756/src/share/vm/prims/unsafe.cpp
     * Unsafe_setOrderedLong -> SET_FIELD_VOLATILE definition -> OrderAccess:release_store_fence. For x86_64,
     * OrderAccess:release_store_fence is defined as using the xchg instruction. You can see how it is exactly defined in jdk7
     * (doug lea is working on some new stuff for JDK 8): http://hg.openjdk.java.net/jdk7/jdk7/hotspot/file/4fc084dac61e/src/os_cpu/linux_x86/vm/orderAccess_linux_x86.inline.hpp
     * you can also use the hdis to disassemble the lazySet code's assembly in action. There is another related question:
     * Do we need mfence when using xchg
     *
     * 另外也有一个例子专门说这个问题：
     * https://github.com/mjpt777/examples
     *
     * 关于lazySet有另外一篇和set方法的区别：http://ifeve.com/juc-atomic-class-lazyset-que/
     * 其实就是lazySet中没有性能开销的store-load内存屏障指令；而set或者volatile指令有。
     * 关于内存屏障的资料：http://g.oswego.edu/dl/jmm/cookbook.html，Doug Lea大大写的。
     */
    public Sequence(final long initialValue)
    {
        UNSAFE.putOrderedLong(this, VALUE_OFFSET, initialValue);
    }

    /**
     * Perform a volatile read of this sequence's value.
     *
     * @return The current value of the sequence.
     */
    public long get()
    {
        return value;
    }

    /**
     * Perform an ordered write of this sequence.  The intent is
     * a Store/Store barrier between this write and any previous
     * store.
     *
     * @param value The new value for the sequence.
     */
    public void set(final long value)
    {
        UNSAFE.putOrderedLong(this, VALUE_OFFSET, value);
    }

    /**
     * Performs a volatile write of this sequence.  The intent is
     * a Store/Store barrier between this write and any previous
     * write and a Store/Load barrier between this write and any
     * subsequent volatile read.
     *
     * 补充说明：这儿方法里就比较详细的说了volatile的写操作用了store/store和store/load屏障指令，
     * 而lazySet写是没有store/load指令。
     *
     * @param value The new value for the sequence.
     */
    public void setVolatile(final long value)
    {
        UNSAFE.putLongVolatile(this, VALUE_OFFSET, value);
    }

    /**
     * Perform a compare and set operation on the sequence.
     *
     * @param expectedValue The expected current value.
     * @param newValue The value to update to.
     * @return true if the operation succeeds, false otherwise.
     */
    public boolean compareAndSet(final long expectedValue, final long newValue)
    {
        return UNSAFE.compareAndSwapLong(this, VALUE_OFFSET, expectedValue, newValue);
    }

    /**
     * Atomically increment the sequence by one.
     *
     * @return The value after the increment
     */
    public long incrementAndGet()
    {
        return addAndGet(1L);
    }

    /**
     * Atomically add the supplied value.
     *
     * @param increment The value to add to the sequence.
     * @return The value after the increment.
     */
    public long addAndGet(final long increment)
    {
        long currentValue;
        long newValue;

        do
        {
            currentValue = get();
            newValue = currentValue + increment;
        }
        while (!compareAndSet(currentValue, newValue));

        return newValue;
    }

    @Override
    public String toString()
    {
        return Long.toString(get());
    }
}
