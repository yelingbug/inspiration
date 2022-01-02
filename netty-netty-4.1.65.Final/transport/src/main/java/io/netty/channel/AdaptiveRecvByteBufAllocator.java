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
package io.netty.channel;

import java.util.ArrayList;
import java.util.List;

import static io.netty.util.internal.ObjectUtil.checkPositive;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * The {@link RecvByteBufAllocator} that automatically increases and
 * decreases the predicted buffer size on feed back.
 * <p>
 * It gradually increases the expected number of readable bytes if the previous
 * read fully filled the allocated buffer.  It gradually decreases the expected
 * number of readable bytes if the read operation was not able to fill a certain
 * amount of the allocated buffer two times consecutively.  Otherwise, it keeps
 * returning the same prediction.
 */
public class AdaptiveRecvByteBufAllocator extends DefaultMaxMessagesRecvByteBufAllocator {

    static final int DEFAULT_MINIMUM = 64;
    // Use an initial value that is bigger than the common MTU of 1500
    static final int DEFAULT_INITIAL = 2048;
    static final int DEFAULT_MAXIMUM = 65536;

    /**
     * 在调整缓冲区大小时，若是增加缓冲区容量，那么增加的索引值。
     * 比如，当前缓冲区的大小为SIZE_TABLE[20],若预测下次需要创建的缓冲区需要增加容量大小，
     * 则新缓冲区的大小为SIZE_TABLE[20 + INDEX_INCREMENT]，即SIZE_TABLE[24]
     * commented by Yelin.G on 2021.10.16
     */
    private static final int INDEX_INCREMENT = 4;
    /**
     * 在调整缓冲区大小时，若是减少缓冲区容量，那么减少的索引值。
     * 比如，当前缓冲区的大小为SIZE_TABLE[20],若预测下次需要创建的缓冲区需要减小容量大小，
     * 则新缓冲区的大小为SIZE_TABLE[20 - INDEX_DECREMENT]，即SIZE_TABLE[19]
     * commented by Yelin.G on 2021.10.16
     */
    private static final int INDEX_DECREMENT = 1;

    private static final int[] SIZE_TABLE;

    static {
        List<Integer> sizeTable = new ArrayList<Integer>();
        for (int i = 16; i < 512; i += 16) {
            sizeTable.add(i);
        }

        /**
         * 上面填值后的结果，大小31：
         * [16, 32, 48, 64, 80, 96, 112, 128, 144, 160, 176, 192, 208, 224, 240, 256, 272, 288, 304, 320, 336, 352, 368, 384, 400, 416, 432, 448, 464, 480, 496]
         * commented by Yelin.G on 2021.10.16
         */

        // Suppress a warning since i becomes negative when an integer overflow happens
        for (int i = 512; i > 0; i <<= 1) { // lgtm[java/constant-comparison]
            sizeTable.add(i);
        }

        /**
         * 继续填值后的结果，大小53，利用int i的溢出(溢出后为负数退出循环，用这种方式有点奇怪)：
         * [16, 32, 48, 64, 80, 96, 112, 128, 144, 160, 176, 192, 208, 224, 240, 256, 272, 288, 304, 320, 336, 352, 368, 384, 400, 416, 432, 448, 464, 480, 496, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536, 131072, 262144, 524288, 1048576, 2097152, 4194304, 8388608, 16777216, 33554432, 67108864, 134217728, 268435456, 536870912, 1073741824]
         * commented by Yelin.G on 2021.10.16
         */

        SIZE_TABLE = new int[sizeTable.size()];
        for (int i = 0; i < SIZE_TABLE.length; i ++) {
            SIZE_TABLE[i] = sizeTable.get(i);
        }
    }

    /**
     * @deprecated There is state for {@link #maxMessagesPerRead()} which is typically based upon channel type.
     */
    @Deprecated
    public static final AdaptiveRecvByteBufAllocator DEFAULT = new AdaptiveRecvByteBufAllocator();

    private static int getSizeTableIndex(final int size) {
        for (int low = 0, high = SIZE_TABLE.length - 1;;) {
            if (high < low) {
                return low;
            }
            if (high == low) {
                return high;
            }

            int mid = low + high >>> 1;
            int a = SIZE_TABLE[mid];
            int b = SIZE_TABLE[mid + 1];
            if (size > b) {
                low = mid + 1;
            } else if (size < a) {
                high = mid - 1;
            } else if (size == a) {
                return mid;
            } else {
                return mid + 1;
            }
        }
    }

    private final class HandleImpl extends MaxMessageHandle {
        private final int minIndex;
        private final int maxIndex;
        private int index;
        private int nextReceiveBufferSize;
        private boolean decreaseNow;

        HandleImpl(int minIndex, int maxIndex, int initial) {
            /**
             * 被{@link AdaptiveRecvByteBufAllocator}创建，minIndex/maxIndex/initial
             * 都会基于SIZE_TABLE校验和设置。
             * commented by Yelin.G on 2021.10.16
             */
            this.minIndex = minIndex;
            this.maxIndex = maxIndex;

            index = getSizeTableIndex(initial);//二分法找到SIZE_TABLE表中和initial最近的值索引。commented by Yelin.G on 2021.10.16
            nextReceiveBufferSize = SIZE_TABLE[index];//从这个索引对应的值开始。commented by Yelin.G on 2021.10.16
        }

        @Override
        public void lastBytesRead(int bytes) {
            // If we read as much as we asked for we should check if we need to ramp up the size of our next guess.
            // This helps adjust more quickly when large amounts of data is pending and can avoid going back to
            // the selector to check for more data. Going back to the selector can add significant latency for large
            // data transfers.
            // 翻译一下：如果我们尽可能读我们请求的数据，我们应该检查是否我们需要巩固下一次要读的猜测数据大的大小(guess())，这个能
            // 帮助我们在大数据来的时候(pending)更快的调整，避免回去selector去检查是否有更多的数据；因为去selector检查在大数据
            // 传输的情况下增加更大的延迟。
            // commented by Yelin.G on 2021.10.16
            if (bytes == attemptedBytesRead()) {
                record(bytes);
            }
            super.lastBytesRead(bytes);
        }

        @Override
        public int guess() {
            return nextReceiveBufferSize;
        }

        private void record(int actualReadBytes) {
            /**
             * 逻辑上判断上次读的字节大小(lastBytesRead)如果小于初始化给出的SIZE_TABLE中index对应值(initial计算出来的index或者是上一次更新的index，参照HandlerImpl
             * 构造函数的计算)大小，第一次设置标志为true，第二次还是这样就开始逐步的将下一次要读的字节数据大小逐步减少(一次减16，对应SIZE_TABLE的索引减1)；
             * 如果大于nextReceiveBufferSize，那就增加nextReceiveBufferSize为下一次读做准备，设置回标志为false。
             * 结合当前类中的{@link #lastBytesRead(int)}的注释去看会理解。
             * commented by Yelin.G on 2021.10.16
             */
            if (actualReadBytes <= SIZE_TABLE[max(0, index - INDEX_DECREMENT)]) {
                if (decreaseNow) {
                    index = max(index - INDEX_DECREMENT, minIndex);
                    nextReceiveBufferSize = SIZE_TABLE[index];
                    decreaseNow = false;
                } else {
                    decreaseNow = true;
                }
            } else if (actualReadBytes >= nextReceiveBufferSize) {
                index = min(index + INDEX_INCREMENT, maxIndex);
                nextReceiveBufferSize = SIZE_TABLE[index];
                decreaseNow = false;
            }
        }

        @Override
        public void readComplete() {
            record(totalBytesRead());
        }
    }

    private final int minIndex;
    private final int maxIndex;
    private final int initial;

    /**
     * Creates a new predictor with the default parameters.  With the default
     * parameters, the expected buffer size starts from {@code 1024}, does not
     * go down below {@code 64}, and does not go up above {@code 65536}.
     */
    public AdaptiveRecvByteBufAllocator() {
        /**
         * 使用默认参数创建一个新的AdaptiveRecvByteBufAllocator。
         * 默认参数，预计缓冲区大小从1024开始，最小不会小于64，最大不会大于65536。
         * commented by Yelin.G on 2021.10.16
         */
        this(DEFAULT_MINIMUM, DEFAULT_INITIAL, DEFAULT_MAXIMUM);
    }

    /**
     * Creates a new predictor with the specified parameters.
     *
     * 使用指定的参数创建AdaptiveRecvByteBufAllocator对象。
     * 其中minimum、initial、maximum是正整数。然后通过getSizeTableIndex()方法获取相应容量在SIZE_TABLE中的索引位置。
     * 并将计算出来的索引赋值给相应的成员变量minIndex、maxIndex。同时保证「SIZE_TABLE[minIndex] >= minimum」以及「SIZE_TABLE[maxIndex] <= maximum」.
     * commented by Yelin.G on 2021.10.16
     *
     * @param minimum  the inclusive lower bound of the expected buffer size
     * @param initial  the initial buffer size when no feed back was received
     * @param maximum  the inclusive upper bound of the expected buffer size
     */
    public AdaptiveRecvByteBufAllocator(int minimum, int initial, int maximum) {
        checkPositive(minimum, "minimum");
        if (initial < minimum) {
            throw new IllegalArgumentException("initial: " + initial);
        }
        if (maximum < initial) {
            throw new IllegalArgumentException("maximum: " + maximum);
        }

        int minIndex = getSizeTableIndex(minimum);
        if (SIZE_TABLE[minIndex] < minimum) {
            this.minIndex = minIndex + 1;
        } else {
            this.minIndex = minIndex;
        }

        int maxIndex = getSizeTableIndex(maximum);
        if (SIZE_TABLE[maxIndex] > maximum) {
            this.maxIndex = maxIndex - 1;
        } else {
            this.maxIndex = maxIndex;
        }

        this.initial = initial;
    }

    @SuppressWarnings("deprecation")
    @Override
    public Handle newHandle() {
        return new HandleImpl(minIndex, maxIndex, initial);
    }

    @Override
    public AdaptiveRecvByteBufAllocator respectMaybeMoreData(boolean respectMaybeMoreData) {
        super.respectMaybeMoreData(respectMaybeMoreData);
        return this;
    }
}
