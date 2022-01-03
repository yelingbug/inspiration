/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.buffer;

import static io.netty.buffer.PoolChunk.RUN_OFFSET_SHIFT;
import static io.netty.buffer.PoolChunk.SIZE_SHIFT;
import static io.netty.buffer.PoolChunk.IS_USED_SHIFT;
import static io.netty.buffer.PoolChunk.IS_SUBPAGE_SHIFT;
import static io.netty.buffer.SizeClasses.LOG2_QUANTUM;

final class PoolSubpage<T> implements PoolSubpageMetric {

    final PoolChunk<T> chunk;
    private final int pageShifts;
    private final int runOffset;
    private final int runSize;
    private final long[] bitmap;

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
    PoolSubpage() {
        chunk = null;
        pageShifts = -1;
        runOffset = -1;
        elemSize = -1;
        runSize = -1;
        bitmap = null;
    }

    PoolSubpage(PoolSubpage<T> head, PoolChunk<T> chunk, int pageShifts, int runOffset, int runSize, int elemSize) {
        this.chunk = chunk;
        this.pageShifts = pageShifts;
        this.runOffset = runOffset;
        this.runSize = runSize;
        this.elemSize = elemSize;
        /**
         * bitmap长度为runSize / 64 / QUANTUM，SizeClasses中最小的size=16，即2^QUANTUM，
         * runSize >>> 6 + LOG2_QUANTUM相当于runSize / 2^6 / 2^4，即runSize是最小size的多少
         * 倍，这个倍数如果用long型来表示需要多少个long，从下面maxNumElems计算逻辑来看，是runSize/elemSize，
         * 而elemSize最小是16(SizeClasses中最小size)，因此bitmap可以表示的long个数的最大值就是需要runSize/16/64，
         * 其实就是runSize/64/16=runSize >>> 6 + LOG2_QUANTUM，但实际上肯定要比这个小，因为elemSize最小是16。
         * 但elemSize肯定>16，所以对实际的elemSize来说，bitmapLength是实际的长度。
         *
         * 摘录的，供理解用，和代码不符：
         *   Subpage位图分布信息数组，数组长度8个，因为：
         *     1个long 8字节64位，即一个 long 可表示 64 个坑是否被占用。那么长度为8的 long 数组，最多可表示 512 个坑.
         *     考虑到最小的Subpage=16B，pageSize=8KB，8KB / 16B = 512个，单个Page最多可分成 512 个SubPage，使用
         *     数组长度为8的long数组，正好最多表示 512 的位图。
         *
         * commented by Yelin.G on 2021.12.27
         */
        bitmap = new long[runSize >>> 6 + LOG2_QUANTUM]; // runSize / 64 / QUANTUM

        /**
         *  摘录的，供理解用，和代码不符：延伸说明，Subpage按申请内存时指定的 capacity(elementSize)等分成 maxNumElems
         *  份，后续申请同等 elementSize 的内存时，即可从已等分好的中获取。通过 bitmap 位图的方式巧妙的管理这 maxNumElems
         *  份 Subpage，分配后打个标，释放后取消标记。
         *
         *  commented by Yelin.G on 2021.12.27
         */
        doNotDestroy = true;
        if (elemSize != 0) {
            maxNumElems = numAvail = runSize / elemSize;
            nextAvail = 0;
            //表示maxNumElems这么多个元素用多少个long型来表示(2^6=64)，还是以runSize=57344为例，maxNumElems=512
            //那么8个long的bitmap就可以表示这么多坑了
            bitmapLength = maxNumElems >>> 6;
            if ((maxNumElems & 63) != 0) {
                bitmapLength ++;
            }

            for (int i = 0; i < bitmapLength; i ++) {
                bitmap[i] = 0;
            }
        }
        /**
         * 挂载到链表中.
         */
        addToPool(head);
    }

    /**
     * Returns the bitmap index of the subpage allocation.
     */
    long allocate() {
        if (numAvail == 0 || !doNotDestroy) {
            return -1;
        }

        final int bitmapIdx = getNextAvail();
        int q = bitmapIdx >>> 6;//计算在一个long型值中的bit位置，如果bitmapIdx=66，那么q=1，即64个bit中的索引值是1，第2个位置
        int r = bitmapIdx & 63;//计算顺序值，如果bitmapIdx=66，那么r=2，第2个位置.
        assert (bitmap[q] >>> r & 1) == 0;
        bitmap[q] |= 1L << r;//把这个位置为1

        if (-- numAvail == 0) {
            removeFromPool();//可用数-1，并从链表中移除
        }

        return toHandle(bitmapIdx);//更新handle中的bitmap
    }

    /**
     * @return {@code true} if this subpage is in use.
     *         {@code false} if this subpage is not used by its chunk and thus it's OK to be released.
     */
    boolean free(PoolSubpage<T> head, int bitmapIdx) {
        if (elemSize == 0) {
            return true;
        }
        int q = bitmapIdx >>> 6;
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) != 0;
        bitmap[q] ^= 1L << r;

        setNextAvail(bitmapIdx);

        if (numAvail ++ == 0) {
            addToPool(head);
            /* When maxNumElems == 1, the maximum numAvail is also 1.
             * Each of these PoolSubpages will go in here when they do free operation.
             * If they return true directly from here, then the rest of the code will be unreachable
             * and they will not actually be recycled. So return true only on maxNumElems > 1. */
            if (maxNumElems > 1) {
                return true;
            }
        }

        if (numAvail != maxNumElems) {
            return true;
        } else {
            // Subpage not in use (numAvail == maxNumElems)
            if (prev == next) {
                // Do not remove if this subpage is the only one left in the pool.
                return true;
            }

            // Remove this subpage from the pool if there are other subpages left in the pool.
            doNotDestroy = false;
            removeFromPool();
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
            //存在一个bit位不为1，即存在可用分配内存。
            if (~bits != 0) {
                return findNextAvail0(i, bits);//找到可用的bit在long型数组中的索引位置
            }
        }
        return -1;
    }

    private int findNextAvail0(int i, long bits) {
        final int maxNumElems = this.maxNumElems;
        final int baseVal = i << 6;//计算出bitmap中i对应的第几个long的偏移

        for (int j = 0; j < 64; j ++) {//因为一个long是64个字节，即64个bit
            if ((bits & 1) == 0) {//(不断右移)检查最低位是否为0，为0表示可用
                int val = baseVal | j;//可用的情况下，计算出val即在整个bitmapLength长度中的第几位
                                      //举个例子：如果bitmapLength=8，即数组有8个long型元素，假定
                                      //这里的i=1，那么bits就是bitmap[1]对应的long型，这里baseVal = 64
                                      //遍历这个long型的64个bit(从低位开始)，判断bit是否为0，如果为0表示可用，
                                      //假定第2个bit为0，那么64｜2=66，表示在整个bitmapLength长度的long型
                                      //数组中，第66个bit是可用的，用图表示：
                                      //xxxxxxxx xxxxxxxx xxxxxxxx xxxxxxxx xxxxxxxx xxxxxxxx xxxxxxxx xxxxxxxx 第一个long(i=0)
                                      //xxxxxxxx xxxxxxxx xxxxxxxx xxxxxxxx xxxxxxxx xxxxxxxx xxxxxxxx xxxxxx0x 第二个long(i=1)
                                      //......
                                      //
                if (val < maxNumElems) {
                    return val;//按照上面的例子，这里应该就是66
                } else {
                    break;
                }
            }
            bits >>>= 1;//比较完一个bit之后右移干掉，继续循环比较下一个
        }
        return -1;
    }

    private long toHandle(int bitmapIdx) {
        int pages = runSize >> pageShifts;
        return (long) runOffset << RUN_OFFSET_SHIFT
               | (long) pages << SIZE_SHIFT
               | 1L << IS_USED_SHIFT
               | 1L << IS_SUBPAGE_SHIFT
               | bitmapIdx;
    }

    @Override
    public String toString() {
        final boolean doNotDestroy;
        final int maxNumElems;
        final int numAvail;
        final int elemSize;
        if (chunk == null) {
            // This is the head so there is no need to synchronize at all as these never change.
            doNotDestroy = true;
            maxNumElems = 0;
            numAvail = 0;
            elemSize = -1;
        } else {
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
        }

        if (!doNotDestroy) {
            return "(" + runOffset + ": not in use)";
        }

        return "(" + runOffset + ": " + (maxNumElems - numAvail) + '/' + maxNumElems +
                ", offset: " + runOffset + ", length: " + runSize + ", elemSize: " + elemSize + ')';
    }

    @Override
    public int maxNumElements() {
        if (chunk == null) {
            // It's the head.
            return 0;
        }

        synchronized (chunk.arena) {
            return maxNumElems;
        }
    }

    @Override
    public int numAvailable() {
        if (chunk == null) {
            // It's the head.
            return 0;
        }

        synchronized (chunk.arena) {
            return numAvail;
        }
    }

    @Override
    public int elementSize() {
        if (chunk == null) {
            // It's the head.
            return -1;
        }

        synchronized (chunk.arena) {
            return elemSize;
        }
    }

    @Override
    public int pageSize() {
        return 1 << pageShifts;
    }

    void destroy() {
        if (chunk != null) {
            chunk.destroy();
        }
    }
}
