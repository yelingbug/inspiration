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

final class PoolSubpage<T> implements PoolSubpageMetric {

    final PoolChunk<T> chunk;
    private final int memoryMapIdx;
    private final int runOffset;
    private final int pageSize;
    private final long[] bitmap;

    /**
     * 每个PoolSubpage下都挂了个链表.
     * 在第一次分配PoolSubpage时,先从池{@link PoolArena#tinySubpagePools}/{@link PoolArena#smallSubpagePools}中根据请求大小规范化后的normalCapacity定位
     * 一个PoolSubpage,然后创建一个新的{@link PoolSubpage}将这个从池中拿到的{@link PoolSubpage}作为其head.
     *
     * 说明一点,在{@link PoolArena#tinySubpagePools}/{@link PoolArena#smallSubpagePools}初始化的时候,会将数组中的每个{@link PoolSubpage}通过构造函数初始化,
     * 并将其prev和next指向自己.
     */
    PoolSubpage<T> prev;
    PoolSubpage<T> next;

    boolean doNotDestroy;
    int elemSize;
    private int maxNumElems;
    private int bitmapLength;
    private int nextAvail;
    private int numAvail;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /** Special constructor that creates a linked list head */
    PoolSubpage(int pageSize) {
        chunk = null;
        memoryMapIdx = -1;
        runOffset = -1;
        elemSize = -1;
        this.pageSize = pageSize;
        bitmap = null;
    }

    PoolSubpage(PoolSubpage<T> head, PoolChunk<T> chunk, int memoryMapIdx, int runOffset, int pageSize, int elemSize) {
        this.chunk = chunk;
        this.memoryMapIdx = memoryMapIdx;
        this.runOffset = runOffset;
        this.pageSize = pageSize;
        bitmap = new long[pageSize >>> 10]; // pageSize / 16 / 64
        //除以１６的意思是把pageSize(默认8192)按照最小16个字节来划分，可以分成512个段，而除以64的，表示一个long有64位，可以描述64个内存段，那么就需要除以64就是需要的位图数
        init(head, elemSize);
    }

    void init(PoolSubpage<T> head, int elemSize) {
        doNotDestroy = true;
        this.elemSize = elemSize;
        if (elemSize != 0) {
            maxNumElems = numAvail = pageSize / elemSize;//根据请求的大小计算一个Subpage可以被拆分成几个内存段
            nextAvail = 0;
            bitmapLength = maxNumElems >>> 6;//除以64，因为每个内存段用64位位图标识
            if ((maxNumElems & 63) != 0) {//结果为0说明maxNumElems小于64，说明不够1个，要加1，也就是需要一个long来描述
                bitmapLength ++;
            }

            for (int i = 0; i < bitmapLength; i ++) {
                bitmap[i] = 0;//初始化全部为0
            }
        }
        addToPool(head);//更新PoolSubpage链表，每个PoolSubpage下面都挂着一个链表，如图：
        //  [Subpage]                  [Subpage]                      [Subpage]
        //        |(next)                         |(next)                           |(next)
        //  [Subpage]                  [Subpage]                      [Subpage]
    }

    /**
     * Returns the bitmap index of the subpage allocation.
     */
    long allocate() {
        if (elemSize == 0) {
            return toHandle(0);
        }

        if (numAvail == 0 || !doNotDestroy) {
            return -1;
        }

        final int bitmapIdx = getNextAvail();//获取一个可用的位图索引．
        int q = bitmapIdx >>> 6;//除以64，得到位于bitmap的哪个索引
        int r = bitmapIdx & 63;//计算位图索引bitmapIdx位于某个bitmap[x]对应的二进制的值．举个例子，假设请求分配的大小为64，那么默认的pageSize可以分成8192/64=128个内存段，
        //经过init()方法计算规则得到128个内存段需要128>>>6=2个元素的bitmap数组来表示128个内存段，因为128个内存段需要128个bit来表示，而一个long是64个bit，用个图来表示：
        //                                                                                                                                                                                          表示第22个内存段没有分配出去(为0)           表示第1个内存段分配出去(为1)
        //                                                                                                                                                                                                                      |                                                                       |
        //bitmap[2]表示的128个内存段分配：o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1       o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o o
        //假设通过getNextAvail()得到了bitmapIdx=22，那么q=bitmapIdx>>>6=0，表示bitmap[0]；r=bitmapIdx&63=22，就表示第22个内存段需要分配出去；下面bitmap[0] |= 1l<<22，用二进制表示
        //就是：(前面的0省略)0111111111111111111111 | 10000000000000000000000，结果就是把22个内存段的bit置为1．
        assert (bitmap[q] >>> r & 1) == 0;
        bitmap[q] |= 1L << r;//将对应的位置为1

        if (-- numAvail == 0) {
            removeFromPool();//如果当前Subpage没有可用的内存分配，从链表中移除．
        }

        return toHandle(bitmapIdx);//这个方法的出来的值是个很大的值，不过在使用的时候都是强行转换成int操作，这个方法里先将bitmapIdx<<32，再与0x4000000000000000L相与，
        //再与memoryMapIdx相与．假设
        // bitmapIdx=22：                       000000000000000000000000000000000000000000000000000000000010110
        //bitmapIdx<<32：                      000000000000000000000000001011000000000000000000000000000000000
        //0x4000000000000000L：    100000000000000000000000000000000000000000000000000000000000000
        //相|的结果：                               100000000000000000000000001011000000000000000000000000000000000
        //假设memoryMapIdx=2048：000000000000000000000000000000000000000000000000000100000000000
        //最终的结果：                             100000000000000000000000001011000000000000000000000100000000000
        //从这个结果上来看，是将bitmapIdx转换成结合二叉树的64位的分配信息，这个分配信息里前32位里包含了bitmapIdx，后32位了包含了memoryMapIdx信息(二叉树的索引)

    }

    /**
     * @return {@code true} if this subpage is in use.
     *         {@code false} if this subpage is not used by its chunk and thus it's OK to be released.
     */
    boolean free(PoolSubpage<T> head, int bitmapIdx) {
        if (elemSize == 0) {
            return true;
        }
        /**
         * 以下的逻辑和allocate时候是类似的．
         */
        int q = bitmapIdx >>> 6;
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) != 0;
        bitmap[q] ^= 1L << r;//找到对应的位清0

        setNextAvail(bitmapIdx);//并把这个bitmapIdx缓存起来，下次如果需要分配，如果这个>0，直接返回，作为一个简单缓存．

        if (numAvail ++ == 0) {
            addToPool(head);//如果是在分配完了之后第一次,那么将这个Subpage重新加入链表中.
            return true;
        }

        if (numAvail != maxNumElems) {
            return true;
        } else {//如果两个相等,说明达到了最大内存段.
            // Subpage not in use (numAvail == maxNumElems)
            if (prev == next) {
                // Do not remove if this subpage is the only one left in the pool.
                return true;
            }

            // Remove this subpage from the pool if there are other subpages left in the pool.
            doNotDestroy = false;
            removeFromPool();//在达到最大内存段时,并且还有其他的subpages存在,那么就从池中移除这个Subpage.
            return false;
        }
    }

    private void addToPool(PoolSubpage<T> head) {
        assert prev == null && next == null;
        prev = head;
        next = head.next;
        next.prev = this;
        head.next = this;
    }

    private void removeFromPool() {
        assert prev != null && next != null;
        prev.next = next;
        next.prev = prev;
        next = null;
        prev = null;
    }

    private void setNextAvail(int bitmapIdx) {
        nextAvail = bitmapIdx;
    }

    private int getNextAvail() {
        int nextAvail = this.nextAvail;
        if (nextAvail >= 0) {
            this.nextAvail = -1;
            return nextAvail;
        }
        return findNextAvail();
    }

    private int findNextAvail() {
        final long[] bitmap = this.bitmap;
        final int bitmapLength = this.bitmapLength;
        for (int i = 0; i < bitmapLength; i ++) {
            long bits = bitmap[i];
            if (~bits != 0) {//只有bits全为1，这个结果才不成立，换句话说，这个时候bits已经全部被分配出去了，只有不全为1的时候，这个结论才成立．
                return findNextAvail0(i, bits);//在当前bits中查找一个可用的位
            }
        }
        return -1;
    }

    private int findNextAvail0(int i, long bits) {
        final int maxNumElems = this.maxNumElems;
        final int baseVal = i << 6;//乘以64，基准值，因为描述内存段的是多个long型(即bitmap数组)的二进制位，这个基准值主要是确定是bitmap[i]所在的偏移．

        for (int j = 0; j < 64; j ++) {//因为bits是个long型，有64位．
            if ((bits & 1) == 0) {//结合下面的bits>>>=1这个表达式来看，是从右往左分配和查找，这里主要看bits的最低位1还是0，如果是1那么&1的结果为1表示已经分配出去了；
                // 如果是0那么&1的结果为0表示表示没有分配．
                int val = baseVal | j;//实际上就是加上上面所说的基准值的偏移．
                if (val < maxNumElems) {//结果当然不能大于最大内存段
                    return val;
                } else {
                    break;
                }
            }
            bits >>>= 1;//分配和查找从右边最低位开始，所以如果不符合条件，那么就右移1位．
        }
        return -1;
    }

    private long toHandle(int bitmapIdx) {
        return 0x4000000000000000L | (long) bitmapIdx << 32 | memoryMapIdx;//先<<然后再从左到右|
    }

    @Override
    public String toString() {
        final boolean doNotDestroy;
        final int maxNumElems;
        final int numAvail;
        final int elemSize;
        synchronized (chunk.arena) {
            if (!this.doNotDestroy) {
                doNotDestroy = false;
                // Not used for creating the String.
                maxNumElems = numAvail = elemSize = -1;
            } else {
                doNotDestroy = true;
                maxNumElems = this.maxNumElems;
                numAvail = this.numAvail;
                elemSize = this.elemSize;
            }
        }

        if (!doNotDestroy) {
            return "(" + memoryMapIdx + ": not in use)";
        }

        return "(" + memoryMapIdx + ": " + (maxNumElems - numAvail) + '/' + maxNumElems +
                ", offset: " + runOffset + ", length: " + pageSize + ", elemSize: " + elemSize + ')';
    }

    @Override
    public int maxNumElements() {
        synchronized (chunk.arena) {
            return maxNumElems;
        }
    }

    @Override
    public int numAvailable() {
        synchronized (chunk.arena) {
            return numAvail;
        }
    }

    @Override
    public int elementSize() {
        synchronized (chunk.arena) {
            return elemSize;
        }
    }

    @Override
    public int pageSize() {
        return pageSize;
    }

    void destroy() {
        if (chunk != null) {
            chunk.destroy();
        }
    }
}
