/*
 * Copyright 2020 The Netty Project
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

import static io.netty.buffer.PoolThreadCache.*;

/**
 * SizeClasses requires {@code pageShifts} to be defined prior to inclusion,
 * and it in turn defines:
 * <p>
 *   LOG2_SIZE_CLASS_GROUP: Log of size class count for each size doubling.
 *   LOG2_MAX_LOOKUP_SIZE: Log of max size class in the lookup table.
 *   sizeClasses: Complete table of [index, log2Group, log2Delta, nDelta, isMultiPageSize,
 *                 isSubPage, log2DeltaLookup] tuples.
 *    index	log2Group	log2Delta	nDelta	isMultiPageSize	isSubPage	log2DeltaLookup	size	Unit: MB
 *     0	4	4	0	0	1	4	16
 *     1	4	4	1	0	1	4	32
 *     2	4	4	2	0	1	4	48
 *     3	4	4	3	0	1	4	64
 *     4	6	4	1	0	1	4	80
 *     5	6	4	2	0	1	4	96
 *     6	6	4	3	0	1	4	112
 *     7	6	4	4	0	1	4	128
 *     8	7	5	1	0	1	5	160
 *     9	7	5	2	0	1	5	192
 *     10	7	5	3	0	1	5	224
 *     11	7	5	4	0	1	5	256
 *     12	8	6	1	0	1	6	320
 *     13	8	6	2	0	1	6	384
 *     14	8	6	3	0	1	6	448
 *     15	8	6	4	0	1	6	512
 *     16	9	7	1	0	1	7	640
 *     17	9	7	2	0	1	7	768
 *     18	9	7	3	0	1	7	896
 *     19	9	7	4	0	1	7	1024
 *     20	10	8	1	0	1	8	1280
 *     21	10	8	2	0	1	8	1536
 *     22	10	8	3	0	1	8	1792
 *     23	10	8	4	0	1	8	2048
 *     24	11	9	1	0	1	9	2560
 *     25	11	9	2	0	1	9	3072
 *     26	11	9	3	0	1	9	3584
 *     27	11	9	4	0	1	9	4096
 *     28	12	10	1	0	1	0	5120
 *     29	12	10	2	0	1	0	6144
 *     30	12	10	3	0	1	0	7168
 *     31	12	10	4	1	1	0	8192	8KB
 *     32	13	11	1	0	1	0	10240	10KB
 *     33	13	11	2	0	1	0	12288	12KB
 *     34	13	11	3	0	1	0	14336	14KB
 *     35	13	11	4	1	1	0	16384	16KB
 *     36	14	12	1	0	1	0	20480	20KB
 *     37	14	12	2	1	1	0	24576	24KB
 *     38	14	12	3	0	1	0	28672	28KB
 *     39	14	12	4	1	0	0	32768	32KB
 *     40	15	13	1	1	0	0	40960	40KB
 *     41	15	13	2	1	0	0	49152	48KB
 *     42	15	13	3	1	0	0	57344	56KB
 *     43	15	13	4	1	0	0	65536	64KB
 *     44	16	14	1	1	0	0	81920	80KB
 *     45	16	14	2	1	0	0	98304	96KB
 *     46	16	14	3	1	0	0	114688	112KB
 *     47	16	14	4	1	0	0	131072	128KB
 *     48	17	15	1	1	0	0	163840	160KB
 *     49	17	15	2	1	0	0	196608	192KB
 *     50	17	15	3	1	0	0	229376	224KB
 *     51	17	15	4	1	0	0	262144	256KB
 *     52	18	16	1	1	0	0	327680	320KB
 *     53	18	16	2	1	0	0	393216	384KB
 *     54	18	16	3	1	0	0	458752	448KB
 *     55	18	16	4	1	0	0	524288	512KB
 *     56	19	17	1	1	0	0	655360	640KB
 *     57	19	17	2	1	0	0	786432	768KB
 *     58	19	17	3	1	0	0	917504	896KB
 *     59	19	17	4	1	0	0	1048576	1.0MB
 *     60	20	18	1	1	0	0	1310720	1.25MB
 *     61	20	18	2	1	0	0	1572864	1.5MB
 *     62	20	18	3	1	0	0	1835008	1.75MB
 *     63	20	18	4	1	0	0	2097152	2MB
 *     64	21	19	1	1	0	0	2621440	2.5MB
 *     65	21	19	2	1	0	0	3145728	3MB
 *     66	21	19	3	1	0	0	3670016	3.5MB
 *     67	21	19	4	1	0	0	4194304	4MB
 *     68	22	20	1	1	0	0	5242880	5MB
 *     69	22	20	2	1	0	0	6291456	6MB
 *     70	22	20	3	1	0	0	7340032	7MB
 *     71	22	20	4	1	0	0	8388608	8MB
 *     72	23	21	1	1	0	0	10485760	10MB
 *     73	23	21	2	1	0	0	12582912	12MB
 *     74	23	21	3	1	0	0	14680064	14MB
 *     75	23	21	4	1	0	0	16777216	16MB
 *
 *     从表中可以发现，不管对于哪种内存规格，它都有更细粒度的内存大小的划分。比如在 512Byte~8192Byte 范围内，现在可分为 512、640、768 等等，不再是 jemalloc3 只有 512、1024、2048 … 这种粒度比较大的规格值了。这就是 jemalloc4 最大的提升。
 *
 *     index: Size class index. //由0开始的自增序列号，表示每个 size 类型的索引。
 *     log2Group: Log of group base size (no deltas added).//表示每个 size 它所对应的组。以每 4 行为一组，一共有 19 组。第 0 组比较特殊，它是单独初始化的。因此，我们应该从第 1 组开始，起始值为 6，每组的 log2Group 是在上一组的值 +1。
 *     log2Delta: Log of delta to previous size class.//增量大小的log2值，表示当前序号所对应的 size 和前一个序号所对应的 size 的差值的 log2 的值。比如 index=6 对应的 size = 112，index=7 对应的 size= 128，因此 index=7 的 log2Delta(7) = log2(128-112)=4。不知道你们有没有发现，其实log2Delta=log2Group-2
 *     nDelta: Delta multiplier.//增量乘数，表示组内增量的倍数。第 0 组也是比较特殊，nDelta 是从 0 开始 + 1。而其余组是从 1 开始 +1。
 *     isMultiPageSize: 'yes' if a multiple of the page size, 'no' otherwise.//表示当前 size 是否是 pageSize（默认值: 8192） 的整数倍。后续会把 isMultiPageSize=1 的行单独整理成一张表，你会发现有 40 个 isMultiPageSize=1 的行。
 *     isSubPage: 'yes' if a subpage size class, 'no' otherwise.//表示当前 size 是否为一个 subPage 类型，jemalloc4 会根据这个值采取不同的内存分配策略。
 *     log2DeltaLookup: Same as log2Delta if a lookup table size class, 'no'
 *                      otherwise.//当 index<=27 时，其值和 log2Delta 相等，当index>27，其值为 0。但是在代码中没有看到具体用来做什么。
 *
 *     有了上面的信息并不够，因为最想到得到的是 index 与 size 的对应关系。
 *     在 SizeClasses 表中，无论哪一行的 size 都是由 _size = (1 << log2Group) + nDelta * (1 << log2Delta)_ 公式计算得到。因此通过计算可得出每行的 size:
 *
 *     参见：https://slowisfast.feishu.cn/docs/doccnYyE0B0QX9F7CxObPFrBglc
 *     commented by Yelin.G on 2021.11.13
 *
 * <p>
 *   nSubpages: Number of subpages size classes.
 *   nSizes: Number of size classes.
 *   nPSizes: Number of size classes that are multiples of pageSize.
 *
 *   smallMaxSizeIdx: Maximum small size class index.
 *
 *   lookupMaxclass: Maximum size class included in lookup table.
 *   log2NormalMinClass: Log of minimum normal size class.
 * <p>
 *   The first size class and spacing are 1 << LOG2_QUANTUM.
 *   Each group has 1 << LOG2_SIZE_CLASS_GROUP of size classes.
 *
 *   size = 1 << log2Group + nDelta * (1 << log2Delta)
 *
 *   The first size class has an unusual encoding, because the size has to be
 *   split between group and delta*nDelta.
 *
 *   If pageShift = 13, sizeClasses looks like this:
 *
 *   (index, log2Group, log2Delta, nDelta, isMultiPageSize, isSubPage, log2DeltaLookup)
 * <p>
 *   ( 0,     4,        4,         0,       no,             yes,        4)
 *   ( 1,     4,        4,         1,       no,             yes,        4)
 *   ( 2,     4,        4,         2,       no,             yes,        4)
 *   ( 3,     4,        4,         3,       no,             yes,        4)
 * <p>
 *   ( 4,     6,        4,         1,       no,             yes,        4)
 *   ( 5,     6,        4,         2,       no,             yes,        4)
 *   ( 6,     6,        4,         3,       no,             yes,        4)
 *   ( 7,     6,        4,         4,       no,             yes,        4)
 * <p>
 *   ( 8,     7,        5,         1,       no,             yes,        5)
 *   ( 9,     7,        5,         2,       no,             yes,        5)
 *   ( 10,    7,        5,         3,       no,             yes,        5)
 *   ( 11,    7,        5,         4,       no,             yes,        5)
 *   ...
 *   ...
 *   ( 72,    23,       21,        1,       yes,            no,        no)
 *   ( 73,    23,       21,        2,       yes,            no,        no)
 *   ( 74,    23,       21,        3,       yes,            no,        no)
 *   ( 75,    23,       21,        4,       yes,            no,        no)
 * <p>
 *   ( 76,    24,       22,        1,       yes,            no,        no)
 */
abstract class SizeClasses implements SizeClassesMetric {

    static final int LOG2_QUANTUM = 4;

    private static final int LOG2_SIZE_CLASS_GROUP = 2;
    private static final int LOG2_MAX_LOOKUP_SIZE = 12;

    private static final int INDEX_IDX = 0;
    private static final int LOG2GROUP_IDX = 1;
    private static final int LOG2DELTA_IDX = 2;
    private static final int NDELTA_IDX = 3;
    private static final int PAGESIZE_IDX = 4;
    private static final int SUBPAGE_IDX = 5;
    private static final int LOG2_DELTA_LOOKUP_IDX = 6;

    private static final byte no = 0, yes = 1;

    protected SizeClasses(int pageSize, int pageShifts, int chunkSize, int directMemoryCacheAlignment) {
        this.pageSize = pageSize;
        /**
         * 缺省值：
         *   {@link PooledByteBufAllocator#defaultPageSize()} = 8192(8k)
         *   {@link PooledByteBufAllocator#DEFAULT_DIRECT_MEMORY_CACHE_ALIGNMENT} = 0
         *
         * int pageShifts = Integer.SIZE - 1 - Integer.numberOfLeadingZeros(pageSize); //{@link PooledByteBufAllocator#validateAndCalculatePageShifts(int, int)}
         * 对缺省值8192(10000000000000)来说，pageShifts=13，即最高位后面的位个数，这里就是1后面0的个数；而chunkSize = 16M(2^24)
         * commented by Yelin.G on 2021.11.13
         */
        this.pageShifts = pageShifts;
        this.chunkSize = chunkSize;
        this.directMemoryCacheAlignment = directMemoryCacheAlignment;

        int group = log2(chunkSize) + 1 - LOG2_QUANTUM;

        //generate size classes
        //[index, log2Group, log2Delta, nDelta, isMultiPageSize, isSubPage, log2DeltaLookup]
        sizeClasses = new short[group << LOG2_SIZE_CLASS_GROUP][7];
        nSizes = sizeClasses();

        //generate lookup table
        sizeIdx2sizeTab = new int[nSizes];//nSizes代表index，即有多少粒度可以分配，即表格大小。
        pageIdx2sizeTab = new int[nPSizes];//nPSize代表有多少isMultiPageSize=1，即表格中有多少是pageSize(8192)的整数倍。
        idx2SizeTab(sizeIdx2sizeTab, pageIdx2sizeTab);

        size2idxTab = new int[lookupMaxSize >> LOG2_QUANTUM];
        size2idxTab(size2idxTab);
    }

    protected final int pageSize;
    protected final int pageShifts;
    protected final int chunkSize;
    protected final int directMemoryCacheAlignment;

    final int nSizes;
    int nSubpages;
    int nPSizes;

    int smallMaxSizeIdx;

    private int lookupMaxSize;

    private final short[][] sizeClasses;

    private final int[] pageIdx2sizeTab;

    // lookup table for sizeIdx <= smallMaxSizeIdx
    private final int[] sizeIdx2sizeTab;

    // lookup table used for size <= lookupMaxclass
    // spacing is 1 << LOG2_QUANTUM, so the size of array is lookupMaxclass >> LOG2_QUANTUM
    private final int[] size2idxTab;

    private int sizeClasses() {
        int normalMaxSize = -1;

        int index = 0;
        int size = 0;

        /**
         * log2Group，log2Delta都是从LOG2_QUANTUM开始。
         */
        int log2Group = LOG2_QUANTUM;
        int log2Delta = LOG2_QUANTUM;
        /**
         * ndeltaLimit为2^{@link LOG2_SIZE_CLASS_GROUP}，即内存块size以4个为一组进行分组
         */
        int ndeltaLimit = 1 << LOG2_SIZE_CLASS_GROUP;

        /**
         * 这是第0组，nDelta从0开始：0，1，2，3，sizeClass方法计算每个size大小。
         * 计算第0组的4个size(对照表),ndeltaLimit = 4;
         */
        //First small group, nDelta start at 0.
        //first size class is 1 << LOG2_QUANTUM
        int nDelta = 0;
        while (nDelta < ndeltaLimit) {
            size = sizeClass(index++, log2Group, log2Delta, nDelta++);
        }
        /**
         * 第0组后log2Group增加{@link LOG2_SIZE_CLASS_GROUP}，而log2Delta不变.
         */
        log2Group += LOG2_SIZE_CLASS_GROUP;

        /**
         * size递增一直到chunkSize(16M)为止，包括small，normal两个级别，其中
         * small级别的isSubpage = 1，normal级别的isSubpage = 0，见代码中
         * isSubpage的计算。
         */
        //All remaining groups, nDelta start at 1.
        while (size < chunkSize) {
            nDelta = 1;

            /**
             * nDelta每组循环：1，2，3，4
             */
            while (nDelta <= ndeltaLimit && size < chunkSize) {
                size = sizeClass(index++, log2Group, log2Delta, nDelta++);
                normalMaxSize = size;
            }

            log2Group++;
            log2Delta++;
        }

        //chunkSize must be normalMaxSize
        assert chunkSize == normalMaxSize;

        //return number of size index
        return index;
    }

    //calculate size class
    private int sizeClass(int index, int log2Group, int log2Delta, int nDelta) {
        short isMultiPageSize;
        /**
         * isMultiPageSize表示当前 size 是否是 pageSize（默认值: 8192）的整数倍。
         */
        if (log2Delta >= pageShifts) {
            /**
             * 超过pageShifts说明size肯定是pageSize(默认8192)的整数倍(因为size的算法公式，见表格上
             * log2group=13即第13组后的size)。
             */
            isMultiPageSize = yes;
        } else {
            int pageSize = 1 << pageShifts;
            /**
             * 计算size的公式。
             */
            int size = (1 << log2Group) + (1 << log2Delta) * nDelta;

            /**
             * 没有超过pageShifts(但是size可能已经会大于pageSize(默认8192)，见表格)，这个时候要
             * 计算size是否是pageSize的整数值。
             */
            isMultiPageSize = size == size / pageSize * pageSize? yes : no;
        }

        /**
         * nDelta从第0组是0，1，2，3，之后都是1，2，3，4，因此log2(nDelta)=0,1,1,2
         */
        int log2Ndelta = nDelta == 0? 0 : log2(nDelta);

        /**
         * 第0组：
         * 。。。
         * 第0组后：
         * log2Ndelta = 0, 1 << log2Ndelta = 1, nDelta = 1, remove = 0
         * log2Ndelta = 1, 1 << log2Ndelta = 2, nDelta = 2, remove = 0
         * log2Ndelta = 1, 1 << log2Ndelta = 2, nDelta = 3, remove = 1
         * log2Ndelta = 2, 1 << log2Ndelta = 4, nDelta = 4, remove = 0
         */
        byte remove = 1 << log2Ndelta < nDelta? yes : no;

        /**
         * log2Size是为了计算isSubPage
         * 对第0组来说：
         *   index=0，log2Size = log2Group+1
         *   index=1，log2Size = log2Group
         *   index=2，log2Size = log2Group
         *   index=3，log2Size = log2Group
         * 对第1组来说：
         *   index=N，log2Size = log2Group
         *   index=N + 1，log2Size = log2Group
         *   index=N + 2，log2Size = log2Group
         *   index=N + 3，log2Size = log2Group + 1
         *
         * 因此iSubpage是在logGroup = 15(pageShifts + {@link LOG2_SIZE_CLASS_GROUP})之前都是1，后面都是0，
         * 也可以理解为small级别的为1，normal级别的为0。
         */
        int log2Size = log2Delta + log2Ndelta == log2Group? log2Group + 1 : log2Group;
        if (log2Size == log2Group) {
            remove = yes;
        }

        short isSubpage = log2Size < pageShifts + LOG2_SIZE_CLASS_GROUP? yes : no;

        /**
         * logDeltaLookup在{@link LOG2_MAX_LOOKUP_SIZE}(默认值是12)以下和log2Delta值是一样的。
         */
        int log2DeltaLookup = log2Size < LOG2_MAX_LOOKUP_SIZE ||
                              log2Size == LOG2_MAX_LOOKUP_SIZE && remove == no
                ? log2Delta : no;

        short[] sz = {
                (short) index, (short) log2Group, (short) log2Delta,
                (short) nDelta, isMultiPageSize, isSubpage, (short) log2DeltaLookup
        };

        sizeClasses[index] = sz;
        /**
         * 其实就是公式：(1 << log2Group) + nDelta * (1 << log2Delta)，两者是一样的。
         */
        int size = (1 << log2Group) + (nDelta << log2Delta);

        if (sz[PAGESIZE_IDX] == yes) {
            //表格上看，nPSizes就是8192整数倍大小的个数，40个，
            nPSizes++;
        }
        if (sz[SUBPAGE_IDX] == yes) {
            nSubpages++;
            smallMaxSizeIdx = index;
        }
        /**
         * 见上面log2DeltaLookup的计算，在12以下和log2Delta是一样的，看表这里的lookupMaxSize = 4096。
         */
        if (sz[LOG2_DELTA_LOOKUP_IDX] != no) {
            lookupMaxSize = size;
        }
        return size;
    }

    private void idx2SizeTab(int[] sizeIdx2sizeTab, int[] pageIdx2sizeTab) {
        int pageIdx = 0;

        for (int i = 0; i < nSizes; i++) {
            short[] sizeClass = sizeClasses[i];
            int log2Group = sizeClass[LOG2GROUP_IDX];
            int log2Delta = sizeClass[LOG2DELTA_IDX];
            int nDelta = sizeClass[NDELTA_IDX];

            int size = (1 << log2Group) + (nDelta << log2Delta);
            sizeIdx2sizeTab[i] = size;

            if (sizeClass[PAGESIZE_IDX] == yes) {
                pageIdx2sizeTab[pageIdx++] = size;
            }
        }
    }

    /**
     * 将一部分较小的size与对应index记录在size2idxTab作为位图直接查询。
     */
    private void size2idxTab(int[] size2idxTab) {
        int idx = 0;
        int size = 0;

        /**
         * #392行代码给出的结果是lookupMaxSize是4096。
         */
        for (int i = 0; size <= lookupMaxSize; i++) {
            /**
             * i = 0时，log2Delta = 4，times = 1
             *   size2idxTab[0] = 0，用于条件判断的size = 32
             *
             * i = 1时，log2Delta = 4，times = 1
             *   size2idxTab[1] = 1，用于条件判断的size = 48
             *
             * i = 2时，log2Delta = 4，times = 1
             *   size2idxTab[2] = 2，用于条件判断的size = 64
             *
             * i = 3时，log2Delta = 4，times = 1
             *   size2idxTab[3] = 3，用于条件判断的size = 80
             *
             * i = 4时，log2Delta = 4，times = 1
             *   size2idxTab[4] = 4，用于条件判断的size = 96
             *
             * i = 5时，log2Delta = 4，times = 1
             *   size2idxTab[5] = 5，用于条件判断的size = 112
             *
             * i = 6时，log2Delta = 4，times = 1
             *   size2idxTab[6] = 6，用于条件判断的size = 128
             *
             * i = 7时，log2Delta = 4，times = 1
             *   size2idxTab[7] = 7，用于条件判断的size = 144
             *
             * i = 8时，log2Delta = 5，times = 2
             *   size2idxTab[8] = 8，用于条件判断的size = 160
             *   size2idxTab[9] = 8，用于条件判断的size = 176
             * (...i一直到11，log2Delta = 5，times = 2)
             *
             * i = 12时，log2Delta = 6，times = 4
             *   size2idxTab[12] = 9，用于条件判断的size = 288
             *   size2idxTab[13] = 9，用于条件判断的size = 304
             *   size2idxTab[14] = 9，用于条件判断的size = 320
             *   size2idxTab[15] = 9，用于条件判断的size = 336
             * (...i一直到15，log2Delta = 5，times = 2)
             *
             * i = 27时，log2Delta = 9，times = 32
             *   size2idxTab[224] = 27，用于条件判断的size = 3616
             *   size2idxTab[225] = 27，用于条件判断的size = 3632
             *   ... ...
             *   size2idxTab[254] = 27，用于条件判断的size = 4080
             *   size2idxTab[255] = 27，用于条件判断的size = 4096
             * 结束
             *
             * 对照(https://slowisfast.feishu.cn/docs/doccnYyE0B0QX9F7CxObPFrBglc)表发现在log2DeltaLookup
             * 最大值以下，建立了size->index的关系。(算法比较巧妙)
             * log2DeltaLookup是如何计算的参照#367行代码，这个数组用来加速 size<=lookupMaxSize(默认值: 4096)
             * 的索引查找。也就是说，当我们需要通过 size 查找 SizeClasses 对应的数组索引时，如果此时 size<=lookupMaxSize 成立，
             */
            int log2Delta = sizeClasses[i][LOG2DELTA_IDX];
            int times = 1 << log2Delta - LOG2_QUANTUM;

            while (size <= lookupMaxSize && times-- > 0) {
                size2idxTab[idx++] = i;
                //那如何计算得到相应 size2idxTab 的索引值呢? 是由 idx=(size-1)/16 求得。
                //比如当 size=4096，由公式求得 idx=255，此时 size2idxTab[255]=27，因此
                //size=4096 对应的 SizeClasses 索引值为 27。但是要注意这里的27的计算是
                //通过公式idx=(size-1)/16计算出来对应的idx，再从idx找到i。
                size = idx + 1 << LOG2_QUANTUM;
            }
        }
    }

    @Override
    public int sizeIdx2size(int sizeIdx) {
        return sizeIdx2sizeTab[sizeIdx];
    }

    /**
     * 根据公式从index计算出size，这个方法貌似只用在了单元测试中。
     */
    @Override
    public int sizeIdx2sizeCompute(int sizeIdx) {
        int group = sizeIdx >> LOG2_SIZE_CLASS_GROUP;
        int mod = sizeIdx & (1 << LOG2_SIZE_CLASS_GROUP) - 1;

        /**
         * 这里group=0是要兼容第0组(log2group=4,index=0,1,2,3)的情况，从index=4开始才
         * 会按照规则来。
         */
        int groupSize = group == 0? 0 :
                1 << LOG2_QUANTUM + LOG2_SIZE_CLASS_GROUP - 1 << group;

        int shift = group == 0? 1 : group;
        int lgDelta = shift + LOG2_QUANTUM - 1;
        int modSize = mod + 1 << lgDelta;

        return groupSize + modSize;
    }

    @Override
    public long pageIdx2size(int pageIdx) {
        return pageIdx2sizeTab[pageIdx];
    }

    /**
     * 根据公式从pageIdx(page的意思是8192的整数倍，pageIdx是pageIdx2sizeTab数组的索引)计算出size，这个方法貌似只用在了单元测试中。
     */
    @Override
    public long pageIdx2sizeCompute(int pageIdx) {
        int group = pageIdx >> LOG2_SIZE_CLASS_GROUP;
        int mod = pageIdx & (1 << LOG2_SIZE_CLASS_GROUP) - 1;

        long groupSize = group == 0? 0 :
                1L << pageShifts + LOG2_SIZE_CLASS_GROUP - 1 << group;

        int shift = group == 0? 1 : group;
        int log2Delta = shift + pageShifts - 1;
        int modSize = mod + 1 << log2Delta;

        return groupSize + modSize;
    }

    @Override
    public int size2SizeIdx(int size) {
        if (size == 0) {
            return 0;
        }
        if (size > chunkSize) {
            return nSizes;
        }

        if (directMemoryCacheAlignment > 0) {
            size = alignSize(size);
        }

        /**
         * 看的出来在size <= lookupMaxSize时，size2idxTab[]的价值就凸显了。
         */
        if (size <= lookupMaxSize) {
            //size-1 / MIN_TINY
            return size2idxTab[size - 1 >> LOG2_QUANTUM];
        }

        /**
         * size > lookupMaxSize后的计算按照如下公式计算出通过size计算出index。
         */
        int x = log2((size << 1) - 1); //这个算法有点意思，意思就是按照2^n来规整，举个例子，如果size是2011，x=11，2^11=2048
                                           //如果是2049~4096之间的任何值，那么x=12，2^12=4096

        /**
         * 计算的主要目的是通过size逆向得到index，计算逻辑都是基于位运算，有兴趣的可以
         * 用一个main方法去运行看看其思路和过程。
         *
         * commented by Yelin.G on 2021.12.31
         */
        int shift = x < LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM + 1
                ? 0 : x - (LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM);

        int group = shift << LOG2_SIZE_CLASS_GROUP;

        int log2Delta = x < LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM + 1
                ? LOG2_QUANTUM : x - LOG2_SIZE_CLASS_GROUP - 1;

        int deltaInverseMask = -1 << log2Delta;
        int mod = (size - 1 & deltaInverseMask) >> log2Delta &
                  (1 << LOG2_SIZE_CLASS_GROUP) - 1;

        return group + mod;
    }

    @Override
    public int pages2pageIdx(int pages) {
        return pages2pageIdxCompute(pages, false);
    }

    @Override
    public int pages2pageIdxFloor(int pages) {
        /**
         * 在默认情况下，pages = chunkSize >> pageShifts，即将chunk(16M)按照pageSize(8K)划分成
         * 2048个page，2048个page的具体值(1,2,3....)通过这个方法映射到{@link SizeClasses}page表
         * 中的pageIdx。
         *
         * {@link SizeClasses}page表实际上是指isMultiPage=1的表的大小，默认情况下pages最大值是2048，
         * 计算出来的pageIdx=39，对应的size是32K正好是Normal级别的最小值。
         *
         * commented by Yelin.G on 2022.01.01
         */
        return pages2pageIdxCompute(pages, true);
    }

    private int pages2pageIdxCompute(int pages, boolean floor) {
        int pageSize = pages << pageShifts;
        if (pageSize > chunkSize) {
            return nPSizes;
        }

        int x = log2((pageSize << 1) - 1);

        int shift = x < LOG2_SIZE_CLASS_GROUP + pageShifts
                ? 0 : x - (LOG2_SIZE_CLASS_GROUP + pageShifts);

        int group = shift << LOG2_SIZE_CLASS_GROUP;

        int log2Delta = x < LOG2_SIZE_CLASS_GROUP + pageShifts + 1?
                pageShifts : x - LOG2_SIZE_CLASS_GROUP - 1;

        int deltaInverseMask = -1 << log2Delta;
        int mod = (pageSize - 1 & deltaInverseMask) >> log2Delta &
                  (1 << LOG2_SIZE_CLASS_GROUP) - 1;

        int pageIdx = group + mod;

        if (floor && pageIdx2sizeTab[pageIdx] > pages << pageShifts) {
            pageIdx--;
        }

        return pageIdx;
    }

    // Round size up to the nearest multiple of alignment.
    private int alignSize(int size) {
        int delta = size & directMemoryCacheAlignment - 1;
        return delta == 0? size : size + directMemoryCacheAlignment - delta;
    }

    @Override
    public int normalizeSize(int size) {
        if (size == 0) {
            return sizeIdx2sizeTab[0];
        }
        if (directMemoryCacheAlignment > 0) {
            size = alignSize(size);
        }

        if (size <= lookupMaxSize) {
            int ret = sizeIdx2sizeTab[size2idxTab[size - 1 >> LOG2_QUANTUM]];
            assert ret == normalizeSizeCompute(size);
            return ret;
        }
        return normalizeSizeCompute(size);
    }

    private static int normalizeSizeCompute(int size) {
        int x = log2((size << 1) - 1);
        int log2Delta = x < LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM + 1
                ? LOG2_QUANTUM : x - LOG2_SIZE_CLASS_GROUP - 1;
        int delta = 1 << log2Delta;
        int delta_mask = delta - 1;
        return size + delta_mask & ~delta_mask;
    }
}
