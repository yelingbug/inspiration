/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.buffer;

import io.netty.util.internal.LongCounter;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.Math.max;

abstract class PoolArena<T> implements PoolArenaMetric {
    static final boolean HAS_UNSAFE = PlatformDependent.hasUnsafe();

    enum SizeClass {
        Tiny,
        Small,
        Normal
    }

    static final int numTinySubpagePools = 512 >>> 4;

    final PooledByteBufAllocator parent;

    private final int maxOrder;
    final int pageSize;
    final int pageShifts;
    final int chunkSize;
    final int subpageOverflowMask;
    final int numSmallSubpagePools;
    final int directMemoryCacheAlignment;
    final int directMemoryCacheAlignmentMask;
    private final PoolSubpage<T>[] tinySubpagePools;
    private final PoolSubpage<T>[] smallSubpagePools;

    private final PoolChunkList<T> q050;
    private final PoolChunkList<T> q025;
    private final PoolChunkList<T> q000;
    private final PoolChunkList<T> qInit;
    private final PoolChunkList<T> q075;
    private final PoolChunkList<T> q100;

    private final List<PoolChunkListMetric> chunkListMetrics;

    // Metrics for allocations and deallocations
    private long allocationsNormal;
    // We need to use the LongCounter here as this is not guarded via synchronized block.
    private final LongCounter allocationsTiny = PlatformDependent.newLongCounter();
    private final LongCounter allocationsSmall = PlatformDependent.newLongCounter();
    private final LongCounter allocationsHuge = PlatformDependent.newLongCounter();
    private final LongCounter activeBytesHuge = PlatformDependent.newLongCounter();

    private long deallocationsTiny;
    private long deallocationsSmall;
    private long deallocationsNormal;

    // We need to use the LongCounter here as this is not guarded via synchronized block.
    private final LongCounter deallocationsHuge = PlatformDependent.newLongCounter();

    // Number of thread caches backed by this arena.
    final AtomicInteger numThreadCaches = new AtomicInteger();

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    protected PoolArena(PooledByteBufAllocator parent, int pageSize,
          int maxOrder, int pageShifts, int chunkSize, int cacheAlignment) {
        this.parent = parent;
        this.pageSize = pageSize;
        this.maxOrder = maxOrder;
        this.pageShifts = pageShifts;
        this.chunkSize = chunkSize;
        directMemoryCacheAlignment = cacheAlignment;
        directMemoryCacheAlignmentMask = cacheAlignment - 1;
        subpageOverflowMask = ~(pageSize - 1);//用于判断一个请求大小的值是否超过pageSize，pageSize肯定是２的幂次方值，如果pageSize=8192(00000000 00000000 00100000 00000000)，
        //8191就是 00000000 00000000 00011111 11111111，那么~8191就是11111111 11111111 11100000 00000000，如果小于8192，那么和~8191相&一定为0．
        tinySubpagePools = newSubpagePoolArray(numTinySubpagePools);//numTinySubpagePools=512>>>4=32，32位数组
        for (int i = 0; i < tinySubpagePools.length; i ++) {
            tinySubpagePools[i] = newSubpagePoolHead(pageSize);
        }

        numSmallSubpagePools = pageShifts - 9;
        smallSubpagePools = newSubpagePoolArray(numSmallSubpagePools);//numSmallSubpagePools=pageShifts(默认13)-9=4
        for (int i = 0; i < smallSubpagePools.length; i ++) {
            smallSubpagePools[i] = newSubpagePoolHead(pageSize);
        }

        q100 = new PoolChunkList<T>(this, null, 100, Integer.MAX_VALUE, chunkSize);
        q075 = new PoolChunkList<T>(this, q100, 75, 100, chunkSize);
        q050 = new PoolChunkList<T>(this, q075, 50, 100, chunkSize);
        q025 = new PoolChunkList<T>(this, q050, 25, 75, chunkSize);
        q000 = new PoolChunkList<T>(this, q025, 1, 50, chunkSize);
        qInit = new PoolChunkList<T>(this, q000, Integer.MIN_VALUE, 25, chunkSize);

        q100.prevList(q075);
        q075.prevList(q050);
        q050.prevList(q025);
        q025.prevList(q000);
        q000.prevList(null);
        qInit.prevList(qInit);

        List<PoolChunkListMetric> metrics = new ArrayList<PoolChunkListMetric>(6);
        metrics.add(qInit);
        metrics.add(q000);
        metrics.add(q025);
        metrics.add(q050);
        metrics.add(q075);
        metrics.add(q100);
        chunkListMetrics = Collections.unmodifiableList(metrics);
    }

    private PoolSubpage<T> newSubpagePoolHead(int pageSize) {
        PoolSubpage<T> head = new PoolSubpage<T>(pageSize);
        head.prev = head;
        head.next = head;
        return head;
    }

    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpagePoolArray(int size) {
        return new PoolSubpage[size];
    }

    abstract boolean isDirect();

    PooledByteBuf<T> allocate(PoolThreadCache cache, int reqCapacity, int maxCapacity) {
        PooledByteBuf<T> buf = newByteBuf(maxCapacity);
        allocate(cache, buf, reqCapacity);
        return buf;
    }

    static int tinyIdx(int normCapacity) {
        return normCapacity >>> 4;//这里右移４位的目的是因为在tiny的情况下normCapacity的最大值是５１２，而在构建tinySubPageHeapCaches/tinySubPageDirectCaches大小的时候用的是
        //PoolArena.numTinySubpagePools这个参数，这个参数就是512>>>4＝３２，也就是容量为３２的数组．
    }

    static int smallIdx(int normCapacity) {
        int tableIdx = 0;
        int i = normCapacity >>> 10;
        while (i != 0) {
            i >>>= 1;
            tableIdx ++;
        }
        return tableIdx;
    }

    // capacity < pageSize
    boolean isTinyOrSmall(int normCapacity) {
        return (normCapacity & subpageOverflowMask) == 0;
    }

    // normCapacity < 512
    static boolean isTiny(int normCapacity) {
        return (normCapacity & 0xFFFFFE00) == 0;
    }

    private void allocate(PoolThreadCache cache, PooledByteBuf<T> buf, final int reqCapacity) {
        final int normCapacity = normalizeCapacity(reqCapacity);
        if (isTinyOrSmall(normCapacity)) { // capacity < pageSize
            int tableIdx;
            PoolSubpage<T>[] table;
            boolean tiny = isTiny(normCapacity);
            if (tiny) { // < 512
                if (cache.allocateTiny(this, buf, reqCapacity, normCapacity)) {//如果是第一次申请，那么一定是false
                    // was able to allocate out of the cache so move on
                    return;
                }
                tableIdx = tinyIdx(normCapacity);
                table = tinySubpagePools;//<512用tinySubpagePools
            } else {
                if (cache.allocateSmall(this, buf, reqCapacity, normCapacity)) {
                    // was able to allocate out of the cache so move on
                    return;
                }
                tableIdx = smallIdx(normCapacity);
                table = smallSubpagePools;//>=512 && <=pageSize用smallSubpagePools
            }

            final PoolSubpage<T> head = table[tableIdx];

            /**
             * Synchronize on the head. This is needed as {@link PoolChunk#allocateSubpage(int)} and
             * {@link PoolChunk#free(long)} may modify the doubly linked list as well.
             *
             * 这里存在一个问题,我们在分配<pageSize大小的时候,怎么确定要分配的大小正好契合{@link PoolSubpage}所分出来的
             * 内存段呢?
             * 从请求分配内存的入口{@link PooledByteBufAllocator#newHeapBuffer(int, int)} 开始,这个方法有两个参数,第一个是初始大小,
             * 第二个是能分配内存的最大值,在从池{@link #tinySubpagePools}/{@link #smallSubpagePools}中获取{@link PoolSubpage}时,
             * 都是以第二个参数的规范化后的值{@code normalCapacity}通过{@link #tinyIdx(int)}/{@link #smallIdx(int)}来计算一个索引,当
             * 选中以后,这个{@link PoolSubpage}所挂的链表内的{@link PoolSubpage}都是以{@code normalCapacity}分段的内存段.
             * 以后分配通过规范化计算之后{@code normalCapacity}大小内存的时候,通过{@link #tinyIdx(int)}/{@link #smallIdx(int)}从池
             * {@link #tinySubpagePools}/{@link #smallSubpagePools}中拿到的head {@link PoolSubpage}都是相同的,而这个{@link PoolSubpage}
             * 下所挂的链表的所有{@link PoolSubpage}内都是以{@code normalCapacity}来进行分段的.
             *
             */
            synchronized (head) {
                final PoolSubpage<T> s = head.next;//初始的时候只是初始化了PoolSubpage，如果是第一次，一切都是空
                if (s != head) {//
                    assert s.doNotDestroy && s.elemSize == normCapacity;
                    long handle = s.allocate();
                    assert handle >= 0;
                    s.chunk.initBufWithSubpage(buf, handle, reqCapacity);//初始化分配的PoolByteBuf，并且和二叉树中对应的PoolSubpage对应起来．
                    incTinySmallAllocation(tiny);//计数
                    return;
                }
            }
            synchronized (this) {
                /**
                 * 如果走到这里，说明s==head，只是从池{@link #tinySubpagePools}/{@link #smallSubpagePools}中找到的{@link PoolSubpage}还没有和
                 * 具体的{@link PoolChunk}/{@link PoolSubpage}关联起来，那就有可能需要先从Chunk List {@link #q100}/{@link #q75}/{@link #q50}/{@link #q25}/
                 * {@link #q000}/{@link #qInit}中去分配，我们去到{@link #allocateNormal(PooledByteBuf, int, int)} 方法去瞅瞅．
                 */
                allocateNormal(buf, reqCapacity, normCapacity);//第一次应该走到这里，因为head.next == head，说明一切都刚刚开始
            }

            incTinySmallAllocation(tiny);//计数．
            return;
        }
        if (normCapacity <= chunkSize) {//如果>pageSize并且<chunkSize
            if (cache.allocateNormal(this, buf, reqCapacity, normCapacity)) {//先尝试从cache中分配，分配不到再走正常逻辑．
                // was able to allocate out of the cache so move on
                return;
            }
            synchronized (this) {
                allocateNormal(buf, reqCapacity, normCapacity);
                ++allocationsNormal;
            }
        } else {//如果>chunkSize，走huge逻辑．
            // Huge allocations are never served via the cache so just call allocateHuge
            allocateHuge(buf, reqCapacity);//对huge逻辑来说，就不走PoolByteBuf，而是直接分配．
        }
    }

    // Method must be called inside synchronized(this) { ... } block
    private void allocateNormal(PooledByteBuf<T> buf, int reqCapacity, int normCapacity) {
        /**
         * 接上文{@link #allocate(PoolThreadCache, PooledByteBuf, int)}方法，先从q050进行分配，然后才是q025等．．．
         * 因为q050的最大可分配为chunkSize的50%容量，而q025最大可分配chunkSize的75%的容量，q075最大可分配chunkSize的25%的容量，
         * q000和qInit最大可分配chunkSize的99%的容量(两者不同在于其maxUsage不同)．
         * 这里解释一下maxUsage的用处，在释放{@link PoolChunk}时({@link PoolChunkList#free(PoolChunk, long)})，其实并不是完全的释放，
         * 而是根据{@link PoolChunk#usage()}通过其{@link PoolChunk#freeBytes}计算一个占用的最大百分比和minUsage比较．如果小于minUsage，
         * 那么调用{@link PoolChunkList#move(PoolChunk)}将其放入到其上一个ChunkList{@link PoolChunkList#prevList}中；另外一种情况就是在
         * {@link PoolChunkList#add(PoolChunk)}增加时，如果{@link PoolChunk#usage()}大于maxUsage，那么将其放入到下一个ChunkList{@link PoolChunkList#nextList}
         * 中．
         */
        //如果是第一次，那么这里全部都是false
        if (q050.allocate(buf, reqCapacity, normCapacity) || q025.allocate(buf, reqCapacity, normCapacity) ||
            q000.allocate(buf, reqCapacity, normCapacity) || qInit.allocate(buf, reqCapacity, normCapacity) ||
            q075.allocate(buf, reqCapacity, normCapacity)) {
            return;
        }

        /**
         * 因为一开始的时候，q050/q025....其中的head都为空，也就是说，其实每个{@link PoolChunkList}都包含了多个{@link PoolChunk}
         * 的集合，而集合是以链表的形式体现的．
         *
         * 所以这里要创建一个{@link PoolChunk}，并且开始的时候都加入到qInit中，至于q050/q025...等{@link PoolChunkList}中的{@link PoolChunk}
         * 都是后续free或者add的时候根据这个方法里上面解释的逻辑增加进去的．
         */
        // Add a new chunk.
        PoolChunk<T> c = newChunk(pageSize, maxOrder, pageShifts, chunkSize);//创建一个新的PoolChunk
        long handle = c.allocate(normCapacity);//在PoolChunk中分配一个PoolSubpage或者多个PoolSubpage．
        assert handle > 0;
        c.initBuf(buf, handle, reqCapacity);
        qInit.add(c);
    }

    private void incTinySmallAllocation(boolean tiny) {
        if (tiny) {
            allocationsTiny.increment();
        } else {
            allocationsSmall.increment();
        }
    }

    private void allocateHuge(PooledByteBuf<T> buf, int reqCapacity) {
        PoolChunk<T> chunk = newUnpooledChunk(reqCapacity);
        activeBytesHuge.add(chunk.chunkSize());
        buf.initUnpooled(chunk, reqCapacity);
        allocationsHuge.increment();
    }

    void free(PoolChunk<T> chunk, long handle, int normCapacity, PoolThreadCache cache) {
        if (chunk.unpooled) {
            int size = chunk.chunkSize();
            destroyChunk(chunk);
            activeBytesHuge.add(-size);
            deallocationsHuge.increment();
        } else {
            SizeClass sizeClass = sizeClass(normCapacity);
            if (cache != null && cache.add(this, chunk, handle, normCapacity, sizeClass)) {
                // cached so not free it.
                return;
            }

            freeChunk(chunk, handle, sizeClass);
        }
    }

    private SizeClass sizeClass(int normCapacity) {
        if (!isTinyOrSmall(normCapacity)) {
            return SizeClass.Normal;
        }
        return isTiny(normCapacity) ? SizeClass.Tiny : SizeClass.Small;
    }

    void freeChunk(PoolChunk<T> chunk, long handle, SizeClass sizeClass) {
        final boolean destroyChunk;
        synchronized (this) {
            switch (sizeClass) {
            case Normal:
                ++deallocationsNormal;
                break;
            case Small:
                ++deallocationsSmall;
                break;
            case Tiny:
                ++deallocationsTiny;
                break;
            default:
                throw new Error();
            }
            destroyChunk = !chunk.parent.free(chunk, handle);
        }
        if (destroyChunk) {
            // destroyChunk not need to be called while holding the synchronized lock.
            destroyChunk(chunk);
        }
    }

    PoolSubpage<T> findSubpagePoolHead(int elemSize) {//进到这里说明elemSize不会超过pageSize，这个方法的目的是从Subpage池中(tinySubpagePools/smallSubpages)根据
        //请求的分配大小elemSize找一个PoolSubpage作为PoolChunk中{@link PoolChunk#subpages}某个索引的值(每个PoolChunk默认情况下都包含maxSubpageAllocs个PoolSubpage，
        // 并且索引中的每个PoolSubpage都挂了一个链表)
        int tableIdx;
        PoolSubpage<T>[] table;
        if (isTiny(elemSize)) { // < 512
            tableIdx = elemSize >>> 4;//因为numTinySubpagePools=512>>>4=32，保证不会超过32
            table = tinySubpagePools;
        } else {
            tableIdx = 0;
            elemSize >>>= 10;
            while (elemSize != 0) {//算法保证tableIdx不超过numSmallSubpagePools，因为smallSubpagePools构建时候的大小是根据pageShifts-9得来的．对默认的pageSize来说，
                //pageShifts=13，numSmallSubpagePools=4，elemSize最大不超过pageSize(默认就是8192，即10000000000000)，也就是说先又移10位(10位是因为smallSubpagePools
                // pageShifts减9，pageShifts是pageSize的２的幂，9+1=10)，还剩下4位，这样保证elemSize最多移动4次就会为0；如果pageSize不是默认的，而是更大，pageShifts就会
                //更大，smallSubpagePools也会更大，还是会保证elemSize的循环次数不会超过smallSubpagePools大小；一种巧妙的算法!
                elemSize >>>= 1;
                tableIdx ++;
            }
            table = smallSubpagePools;
        }

        return table[tableIdx];
    }

    int normalizeCapacity(int reqCapacity) {
        if (reqCapacity < 0) {
            throw new IllegalArgumentException("capacity: " + reqCapacity + " (expected: 0+)");
        }

        if (reqCapacity >= chunkSize) {
            return directMemoryCacheAlignment == 0 ? reqCapacity : alignCapacity(reqCapacity);
        }

        // Small和Normal 规范化到大于2的n次方的最小值，即结果值一定>=当前值,并且这个值是16的指数倍(16*2^n)，并且是2的指数的最接近的值．
        //比如：reqCapacity=513，校正的结果值是1024(=16*64=2^10)；reqCapacity=8393，那么校正的结果值是16384(=16*1024=2^14)；reqCapacith=12333，校正的结果值是16384(=16*1024=2^14)
        if (!isTiny(reqCapacity)) { // >= 512
            // Doubled

            int normalizedCapacity = reqCapacity;
            normalizedCapacity --;
            normalizedCapacity |= normalizedCapacity >>>  1;//右移１位，将从左边开始的第一个１也右移了１位，右移前后的两个数相|，结果值就变成从左边开始两个１．
            normalizedCapacity |= normalizedCapacity >>>  2;//对上面的结果值右移２位，前后两个值在相|，结果值就变成从左边开始４个１．
            normalizedCapacity |= normalizedCapacity >>>  4;//以此类推，慢慢的全部都变成了１．举个例子，假设一个数５１４，其二进制为1000000010
            normalizedCapacity |= normalizedCapacity >>>  8;//                                                                                                                       右移１位变成了0100000001，
            normalizedCapacity |= normalizedCapacity >>> 16;//                                                                                                           这两个值相|，变成了1100000000，最左边变成了两个１，
            normalizedCapacity ++;                                                   //                                                                            再对上面这个结果值右移２位，变成了0011000000，
                                                                                                          //                                                                                                         上面两个值相|，变成了1111000000，最左边变成了四个１，
                                                                                                         //以此类推，继续右移８位，最左边将会全部变成八个１，等等，一直到最后，将会全部变成了１，1111111111，即是最后的结果．
                                                                                                        //移到１６位时就停了，那就说明结果一定是１６的指数倍(16*2^n)，如果需要３２的指数倍，那就继续右移３２位．这个方式的思路
                                                                                                       //就是根据移动的最大位得出２的幂次方值，并且这个值是最大位的指数倍．

            if (normalizedCapacity < 0) {
                normalizedCapacity >>>= 1;
            }
            assert directMemoryCacheAlignment == 0 || (normalizedCapacity & directMemoryCacheAlignmentMask) == 0;

            return normalizedCapacity;
        }

        //Tiny，如果directMemoryCacheAlignment>0，说明内存需要对齐，这个值默认为０．
        //比如基准为64B，则分配的内存都需要为64B的整数倍，也就是常说的按64字节对齐
        if (directMemoryCacheAlignment > 0) {
            return alignCapacity(reqCapacity);//比如reqCapacity=177，假设要以如果directMemoryCacheAlignment=64来进行对齐，很显然177没有以按64来对齐(也就是不是64的整数倍)，
            // 那么delta=177&(64-1)=49，然后177+49-49=192=64*3，对齐了！
        }

        // Quantum-spaced
        if ((reqCapacity & 15) == 0) {//<512，并且为16的指数级,16,32,64,128,256,那么直接返回,不需要额外计算
            return reqCapacity;
        }

        return (reqCapacity & ~15) + 16;//<512，但不是16的指数级，要调整为16的指数级，比如reqCapacity=36，结果为48(=16*3)

        //总之就是用来保证分配的最小容量为16,或者16的指数级.netty中最小分配空间是一个subPage,也就是16字节
        //这里涉及到大量的位运算，比较巧妙！
    }

    int alignCapacity(int reqCapacity) {
        int delta = reqCapacity & directMemoryCacheAlignmentMask;
        return delta == 0 ? reqCapacity : reqCapacity + directMemoryCacheAlignment - delta;
    }

    void reallocate(PooledByteBuf<T> buf, int newCapacity, boolean freeOldMemory) {
        if (newCapacity < 0 || newCapacity > buf.maxCapacity()) {
            throw new IllegalArgumentException("newCapacity: " + newCapacity);
        }

        int oldCapacity = buf.length;
        if (oldCapacity == newCapacity) {
            return;
        }

        PoolChunk<T> oldChunk = buf.chunk;
        long oldHandle = buf.handle;
        T oldMemory = buf.memory;
        int oldOffset = buf.offset;
        int oldMaxLength = buf.maxLength;
        int readerIndex = buf.readerIndex();
        int writerIndex = buf.writerIndex();

        allocate(parent.threadCache(), buf, newCapacity);
        if (newCapacity > oldCapacity) {
            memoryCopy(
                    oldMemory, oldOffset,
                    buf.memory, buf.offset, oldCapacity);
        } else if (newCapacity < oldCapacity) {
            if (readerIndex < newCapacity) {
                if (writerIndex > newCapacity) {
                    writerIndex = newCapacity;
                }
                memoryCopy(
                        oldMemory, oldOffset + readerIndex,
                        buf.memory, buf.offset + readerIndex, writerIndex - readerIndex);
            } else {
                readerIndex = writerIndex = newCapacity;
            }
        }

        buf.setIndex(readerIndex, writerIndex);

        if (freeOldMemory) {
            free(oldChunk, oldHandle, oldMaxLength, buf.cache);
        }
    }

    @Override
    public int numThreadCaches() {
        return numThreadCaches.get();
    }

    @Override
    public int numTinySubpages() {
        return tinySubpagePools.length;
    }

    @Override
    public int numSmallSubpages() {
        return smallSubpagePools.length;
    }

    @Override
    public int numChunkLists() {
        return chunkListMetrics.size();
    }

    @Override
    public List<PoolSubpageMetric> tinySubpages() {
        return subPageMetricList(tinySubpagePools);
    }

    @Override
    public List<PoolSubpageMetric> smallSubpages() {
        return subPageMetricList(smallSubpagePools);
    }

    @Override
    public List<PoolChunkListMetric> chunkLists() {
        return chunkListMetrics;
    }

    private static List<PoolSubpageMetric> subPageMetricList(PoolSubpage<?>[] pages) {
        List<PoolSubpageMetric> metrics = new ArrayList<PoolSubpageMetric>();
        for (PoolSubpage<?> head : pages) {
            if (head.next == head) {
                continue;
            }
            PoolSubpage<?> s = head.next;
            for (;;) {
                metrics.add(s);
                s = s.next;
                if (s == head) {
                    break;
                }
            }
        }
        return metrics;
    }

    @Override
    public long numAllocations() {
        final long allocsNormal;
        synchronized (this) {
            allocsNormal = allocationsNormal;
        }
        return allocationsTiny.value() + allocationsSmall.value() + allocsNormal + allocationsHuge.value();
    }

    @Override
    public long numTinyAllocations() {
        return allocationsTiny.value();
    }

    @Override
    public long numSmallAllocations() {
        return allocationsSmall.value();
    }

    @Override
    public synchronized long numNormalAllocations() {
        return allocationsNormal;
    }

    @Override
    public long numDeallocations() {
        final long deallocs;
        synchronized (this) {
            deallocs = deallocationsTiny + deallocationsSmall + deallocationsNormal;
        }
        return deallocs + deallocationsHuge.value();
    }

    @Override
    public synchronized long numTinyDeallocations() {
        return deallocationsTiny;
    }

    @Override
    public synchronized long numSmallDeallocations() {
        return deallocationsSmall;
    }

    @Override
    public synchronized long numNormalDeallocations() {
        return deallocationsNormal;
    }

    @Override
    public long numHugeAllocations() {
        return allocationsHuge.value();
    }

    @Override
    public long numHugeDeallocations() {
        return deallocationsHuge.value();
    }

    @Override
    public  long numActiveAllocations() {
        long val = allocationsTiny.value() + allocationsSmall.value() + allocationsHuge.value()
                - deallocationsHuge.value();
        synchronized (this) {
            val += allocationsNormal - (deallocationsTiny + deallocationsSmall + deallocationsNormal);
        }
        return max(val, 0);
    }

    @Override
    public long numActiveTinyAllocations() {
        return max(numTinyAllocations() - numTinyDeallocations(), 0);
    }

    @Override
    public long numActiveSmallAllocations() {
        return max(numSmallAllocations() - numSmallDeallocations(), 0);
    }

    @Override
    public long numActiveNormalAllocations() {
        final long val;
        synchronized (this) {
            val = allocationsNormal - deallocationsNormal;
        }
        return max(val, 0);
    }

    @Override
    public long numActiveHugeAllocations() {
        return max(numHugeAllocations() - numHugeDeallocations(), 0);
    }

    @Override
    public long numActiveBytes() {
        long val = activeBytesHuge.value();
        synchronized (this) {
            for (int i = 0; i < chunkListMetrics.size(); i++) {
                for (PoolChunkMetric m: chunkListMetrics.get(i)) {
                    val += m.chunkSize();
                }
            }
        }
        return max(0, val);
    }

    protected abstract PoolChunk<T> newChunk(int pageSize, int maxOrder, int pageShifts, int chunkSize);
    protected abstract PoolChunk<T> newUnpooledChunk(int capacity);
    protected abstract PooledByteBuf<T> newByteBuf(int maxCapacity);
    protected abstract void memoryCopy(T src, int srcOffset, T dst, int dstOffset, int length);
    protected abstract void destroyChunk(PoolChunk<T> chunk);

    @Override
    public synchronized String toString() {
        StringBuilder buf = new StringBuilder()
            .append("Chunk(s) at 0~25%:")
            .append(StringUtil.NEWLINE)
            .append(qInit)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 0~50%:")
            .append(StringUtil.NEWLINE)
            .append(q000)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 25~75%:")
            .append(StringUtil.NEWLINE)
            .append(q025)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 50~100%:")
            .append(StringUtil.NEWLINE)
            .append(q050)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 75~100%:")
            .append(StringUtil.NEWLINE)
            .append(q075)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 100%:")
            .append(StringUtil.NEWLINE)
            .append(q100)
            .append(StringUtil.NEWLINE)
            .append("tiny subpages:");
        appendPoolSubPages(buf, tinySubpagePools);
        buf.append(StringUtil.NEWLINE)
           .append("small subpages:");
        appendPoolSubPages(buf, smallSubpagePools);
        buf.append(StringUtil.NEWLINE);

        return buf.toString();
    }

    private static void appendPoolSubPages(StringBuilder buf, PoolSubpage<?>[] subpages) {
        for (int i = 0; i < subpages.length; i ++) {
            PoolSubpage<?> head = subpages[i];
            if (head.next == head) {
                continue;
            }

            buf.append(StringUtil.NEWLINE)
                    .append(i)
                    .append(": ");
            PoolSubpage<?> s = head.next;
            for (;;) {
                buf.append(s);
                s = s.next;
                if (s == head) {
                    break;
                }
            }
        }
    }

    @Override
    protected final void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            destroyPoolSubPages(smallSubpagePools);
            destroyPoolSubPages(tinySubpagePools);
            destroyPoolChunkLists(qInit, q000, q025, q050, q075, q100);
        }
    }

    private static void destroyPoolSubPages(PoolSubpage<?>[] pages) {
        for (PoolSubpage<?> page : pages) {
            page.destroy();
        }
    }

    private void destroyPoolChunkLists(PoolChunkList<T>... chunkLists) {
        for (PoolChunkList<T> chunkList: chunkLists) {
            chunkList.destroy(this);
        }
    }

    static final class HeapArena extends PoolArena<byte[]> {

        HeapArena(PooledByteBufAllocator parent, int pageSize, int maxOrder,
                int pageShifts, int chunkSize, int directMemoryCacheAlignment) {
            super(parent, pageSize, maxOrder, pageShifts, chunkSize,
                    directMemoryCacheAlignment);
        }

        private static byte[] newByteArray(int size) {
            return PlatformDependent.allocateUninitializedArray(size);
        }

        @Override
        boolean isDirect() {
            return false;
        }

        @Override
        protected PoolChunk<byte[]> newChunk(int pageSize, int maxOrder, int pageShifts, int chunkSize) {
            return new PoolChunk<byte[]>(this, newByteArray(chunkSize), pageSize, maxOrder, pageShifts, chunkSize, 0);
        }

        @Override
        protected PoolChunk<byte[]> newUnpooledChunk(int capacity) {
            return new PoolChunk<byte[]>(this, newByteArray(capacity), capacity, 0);
        }

        @Override
        protected void destroyChunk(PoolChunk<byte[]> chunk) {
            // Rely on GC.
        }

        @Override
        protected PooledByteBuf<byte[]> newByteBuf(int maxCapacity) {
            return HAS_UNSAFE ? PooledUnsafeHeapByteBuf.newUnsafeInstance(maxCapacity)
                    : PooledHeapByteBuf.newInstance(maxCapacity);
        }

        @Override
        protected void memoryCopy(byte[] src, int srcOffset, byte[] dst, int dstOffset, int length) {
            if (length == 0) {
                return;
            }

            System.arraycopy(src, srcOffset, dst, dstOffset, length);
        }
    }

    static final class DirectArena extends PoolArena<ByteBuffer> {

        DirectArena(PooledByteBufAllocator parent, int pageSize, int maxOrder,
                int pageShifts, int chunkSize, int directMemoryCacheAlignment) {
            super(parent, pageSize, maxOrder, pageShifts, chunkSize,
                    directMemoryCacheAlignment);
        }

        @Override
        boolean isDirect() {
            return true;
        }

        private int offsetCacheLine(ByteBuffer memory) {
            // We can only calculate the offset if Unsafe is present as otherwise directBufferAddress(...) will
            // throw an NPE.
            return HAS_UNSAFE ?
                    (int) (PlatformDependent.directBufferAddress(memory) & directMemoryCacheAlignmentMask) : 0;
        }

        @Override
        protected PoolChunk<ByteBuffer> newChunk(int pageSize, int maxOrder,
                int pageShifts, int chunkSize) {
            if (directMemoryCacheAlignment == 0) {
                return new PoolChunk<ByteBuffer>(this,
                        allocateDirect(chunkSize), pageSize, maxOrder,
                        pageShifts, chunkSize, 0);
            }
            final ByteBuffer memory = allocateDirect(chunkSize
                    + directMemoryCacheAlignment);
            return new PoolChunk<ByteBuffer>(this, memory, pageSize,
                    maxOrder, pageShifts, chunkSize,
                    offsetCacheLine(memory));
        }

        @Override
        protected PoolChunk<ByteBuffer> newUnpooledChunk(int capacity) {
            if (directMemoryCacheAlignment == 0) {
                return new PoolChunk<ByteBuffer>(this,
                        allocateDirect(capacity), capacity, 0);
            }
            final ByteBuffer memory = allocateDirect(capacity
                    + directMemoryCacheAlignment);
            return new PoolChunk<ByteBuffer>(this, memory, capacity,
                    offsetCacheLine(memory));
        }

        private static ByteBuffer allocateDirect(int capacity) {
            return PlatformDependent.useDirectBufferNoCleaner() ?
                    PlatformDependent.allocateDirectNoCleaner(capacity) : ByteBuffer.allocateDirect(capacity);
        }

        @Override
        protected void destroyChunk(PoolChunk<ByteBuffer> chunk) {
            if (PlatformDependent.useDirectBufferNoCleaner()) {
                PlatformDependent.freeDirectNoCleaner(chunk.memory);
            } else {
                PlatformDependent.freeDirectBuffer(chunk.memory);
            }
        }

        @Override
        protected PooledByteBuf<ByteBuffer> newByteBuf(int maxCapacity) {
            if (HAS_UNSAFE) {
                return PooledUnsafeDirectByteBuf.newInstance(maxCapacity);
            } else {
                return PooledDirectByteBuf.newInstance(maxCapacity);
            }
        }

        @Override
        protected void memoryCopy(ByteBuffer src, int srcOffset, ByteBuffer dst, int dstOffset, int length) {
            if (length == 0) {
                return;
            }

            if (HAS_UNSAFE) {
                PlatformDependent.copyMemory(
                        PlatformDependent.directBufferAddress(src) + srcOffset,
                        PlatformDependent.directBufferAddress(dst) + dstOffset, length);
            } else {
                // We must duplicate the NIO buffers because they may be accessed by other Netty buffers.
                src = src.duplicate();
                dst = dst.duplicate();
                src.position(srcOffset).limit(srcOffset + length);
                dst.position(dstOffset);
                dst.put(src);
            }
        }
    }
}
