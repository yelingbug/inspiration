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

/**
 * Description of algorithm for PageRun/PoolSubpage allocation from PoolChunk
 *
 * Notation: The following terms are important to understand the code
 * > page  - a page is the smallest unit of memory chunk that can be allocated
 * > chunk - a chunk is a collection of pages
 * > in this code chunkSize = 2^{maxOrder} * pageSize
 *
 * To begin we allocate a byte array of size = chunkSize
 * Whenever a ByteBuf of given size needs to be created we search for the first position
 * in the byte array that has enough empty space to accommodate the requested size and
 * return a (long) handle that encodes this offset information, (this memory segment is then
 * marked as reserved so it is always used by exactly one ByteBuf and no more)
 *
 * For simplicity all sizes are normalized according to PoolArena#normalizeCapacity method
 * This ensures that when we request for memory segments of size >= pageSize the normalizedCapacity
 * equals the next nearest power of 2
 *
 * To search for the first offset in chunk that has at least requested size available we construct a
 * complete balanced binary tree and store it in an array (just like heaps) - memoryMap
 *
 * The tree looks like this (the size of each node being mentioned in the parenthesis)
 *
 * depth=0        1 node (chunkSize)
 * depth=1        2 nodes (chunkSize/2)
 * ..
 * ..
 * depth=d        2^d nodes (chunkSize/2^d)
 * ..
 * depth=maxOrder 2^maxOrder nodes (chunkSize/2^{maxOrder} = pageSize)
 *
 * depth=maxOrder is the last level and the leafs consist of pages
 *
 * With this tree available searching in chunkArray translates like this:
 * To allocate a memory segment of size chunkSize/2^k we search for the first node (from left) at height k
 * which is unused
 *
 * Algorithm:
 * ----------
 * Encode the tree in memoryMap with the notation
 *   memoryMap[id] = x => in the subtree rooted at id, the first node that is free to be allocated
 *   is at depth x (counted from depth=0) i.e., at depths [depth_of_id, x), there is no node that is free
 *
 *  As we allocate & free nodes, we update values stored in memoryMap so that the property is maintained
 *
 * Initialization -
 *   In the beginning we construct the memoryMap array by storing the depth of a node at each node
 *     i.e., memoryMap[id] = depth_of_id
 *
 * Observations:
 * -------------
 * 1) memoryMap[id] = depth_of_id  => it is free / unallocated
 * 2) memoryMap[id] > depth_of_id  => at least one of its child nodes is allocated, so we cannot allocate it, but
 *                                    some of its children can still be allocated based on their availability
 * 3) memoryMap[id] = maxOrder + 1 => the node is fully allocated & thus none of its children can be allocated, it
 *                                    is thus marked as unusable
 *
 * Algorithm: [allocateNode(d) => we want to find the first node (from left) at height h that can be allocated]
 * ----------
 * 1) start at root (i.e., depth = 0 or id = 1)
 * 2) if memoryMap[1] > d => cannot be allocated from this chunk
 * 3) if left node value <= h; we can allocate from left subtree so move to left and repeat until found
 * 4) else try in right subtree
 *
 * Algorithm: [allocateRun(size)]
 * ----------
 * 1) Compute d = log_2(chunkSize/size)
 * 2) Return allocateNode(d)
 *
 * Algorithm: [allocateSubpage(size)]
 * ----------
 * 1) use allocateNode(maxOrder) to find an empty (i.e., unused) leaf (i.e., page)
 * 2) use this handle to construct the PoolSubpage object or if it already exists just call init(normCapacity)
 *    note that this PoolSubpage object is added to subpagesPool in the PoolArena when we init() it
 *
 * Note:
 * -----
 * In the implementation for improving cache coherence,
 * we store 2 pieces of information (i.e, 2 byte vals) as a short value in memoryMap
 *
 * memoryMap[id]= (depth_of_id, x)
 * where as per convention defined above
 * the second value (i.e, x) indicates that the first node which is free to be allocated is at depth x (from root)
 */
final class PoolChunk<T> implements PoolChunkMetric {

    private static final int INTEGER_SIZE_MINUS_ONE = Integer.SIZE - 1;

    final PoolArena<T> arena;
    final T memory;
    final boolean unpooled;
    final int offset;//构造函数创建时，一般为０，只有在使用堆外内存时，才会有偏移．

    private final byte[] memoryMap;
    private final byte[] depthMap;
    private final PoolSubpage<T>[] subpages;
    /** Used to determine if the requested capacity is equal to or greater than pageSize. */
    private final int subpageOverflowMask;
    private final int pageSize;
    private final int pageShifts;
    private final int maxOrder;
    private final int chunkSize;
    private final int log2ChunkSize;
    private final int maxSubpageAllocs;
    /** Used to mark memory as unusable */
    private final byte unusable;

    private int freeBytes;

    PoolChunkList<T> parent;//PoolChunk属于那个PoolChunkList
    PoolChunk<T> prev;
    PoolChunk<T> next;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    PoolChunk(PoolArena<T> arena, T memory, int pageSize, int maxOrder, int pageShifts, int chunkSize, int offset) {
        unpooled = false;
        this.arena = arena;
        this.memory = memory;
        this.pageSize = pageSize;
        this.pageShifts = pageShifts;
        this.maxOrder = maxOrder;
        this.chunkSize = chunkSize;
        this.offset = offset;
        unusable = (byte) (maxOrder + 1);
        log2ChunkSize = log2(chunkSize);
        subpageOverflowMask = ~(pageSize - 1);
        freeBytes = chunkSize;

        assert maxOrder < 30 : "maxOrder should be < 30, but is: " + maxOrder;
        maxSubpageAllocs = 1 << maxOrder;//默认maxOrder=11，maxSubpageAllocs=2048

        // Generate the memory map.
        memoryMap = new byte[maxSubpageAllocs << 1];//构建一个有4096个节点的二叉树，如下．
        depthMap = new byte[memoryMap.length];
        int memoryMapIndex = 1;
        /**
         * 初始化memoryMap和depthMap，构建一个完美二叉树．
         * 这里maxOrder可以看做是树的高度(默认是11)，对一个高度是11(对应最大层是12，因为根节点层为１)的完美二叉树，其节点数=2^(11+1) - 1=4095，
         * 最后一层正好是2048个节点即2^(12-1)，因为第i层的节点个数最多为2^(最大层-1)．而每个chunk大小=2^maxOrder * pageSize=2^11 * 8192=16M．
         * 我们看看初始化后的memoryMap拓扑：
         *                          0                                                                                         [1](索引)=0(高度)
         *                                                                                                            /                                     \
         *                          1                                                                         [2]=1                                        [3]=1
         *                                                                                                /           \                                 /                \
         *                          2                                                         [4]=2         [5]=2                    [6]=2                 [7]=2
         *                                                                                  /      \            /         \               /        \                   /        \
         *                          3                                                 [8]=3  [9]=3  [10]=3  [11]=3  [12]=3  [13]=3  [14]=3  [15]=3
         *                          ...                                      ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ...
         *                          9                                     [512]=9  [513]=9 ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ...[1022]=9  [1023]=9
         *                                                              /         \                                                                                                                              /               \
         *                         10                        [1024]=10  [1025]=10 ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... [2046]=10  [2047]=10
         *                                                  /              \                                                                                                                                                    /             \
         *                         11             [2048]=11  [2049]=11 ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... ... [4094]=11  [4095]=11    #最后一层正好是2048个节点，每个节点8192(8K)，即
         *                                                                                                                                                                                                                                                             #这个二叉树表示的就是一个chunk大小=2^11*8192=
         *                                                                                                                                                                                                                                                             #2^maxOrder * pageSize
         *  就可以得出分配内存的时候：
         *  1，如果需要分配大小8k的内存，则只需要在第11层，找到第一个可用节点即可。
         *  2，如果需要分配大小16k的内存，则只需要在第10层，找到第一个可用节点即可；因为每个父节点下面都有两个子节点，每个子节点是８Ｋ．
         *  3，如果节点1024存在一个已经被分配的子节点2048，则该节点不能被分配，如需要分配大小16k的内存，这个时候节点2048已被分配，节点2049未被分配，
         *  就不能直接分配节点1024，因为该节点目前只剩下8k内存。
         *
         *  那如果我们请求分配内存大小X的时候，怎么快速定位是在哪一层？
         *  答：假设我想申请8394字节的内存，因为8393不是２的幂次方值，所以先要normalize(8393)，即找到>8393的最接近的２的幂次方值即8192*2=16384=2^14，
         *  层＝maxOrder - (log2(8393) - pageShifts)，pageShifts是根据pageSize(缺省8192)计算的，这里是13(=2^13=8192)，实际上就是二进制的１所在的最高位索引．
         *  log2(8393)的算法实际上就是找到大于8393的最接近的２幂次方值的幂，这里最接近的２幂次方值是１６３８４，所以幂是１４；所以层=最大高度-(高度偏移)=
         *  11-(14-13)=10，层是１０，定位在１０层，高度偏移是因为上面每一层的最左边的索引正好是其父节点的２倍，都是２的幂次方值，呈现在其二进制上就是子
         *  节点的最高位比父节点的最高位多１为再通俗地阐述一下大于某个值的最接近的２幂次方值：
         *  8393的二进制是
         *  00000000 00000000 00100000 11001001，２的幂次方值是某个位为１其他全为０，比如２^１=８(二进制是１０)，２^３=８(二进制是１０００)，所以如果
         *  要大于其并是最接近的２幂次方值那实际就是把最高位往做挪一位，其他位全置为０，即：
         *  00000000 00000000 01000000 00000000
         *
         */
        for (int d = 0; d <= maxOrder; ++ d) { // move down the tree one level at a time
            int depth = 1 << d;
            for (int p = 0; p < depth; ++ p) {
                // in each level traverse left to right and set value to the depth of subtree
                memoryMap[memoryMapIndex] = (byte) d;
                depthMap[memoryMapIndex] = (byte) d;
                memoryMapIndex ++;
            }
        }

        subpages = newSubpageArray(maxSubpageAllocs);
    }

    /** Creates a special chunk that is not pooled. */
    PoolChunk(PoolArena<T> arena, T memory, int size, int offset) {
        unpooled = true;
        this.arena = arena;
        this.memory = memory;
        this.offset = offset;
        memoryMap = null;
        depthMap = null;
        subpages = null;
        subpageOverflowMask = 0;
        pageSize = 0;
        pageShifts = 0;
        maxOrder = 0;
        unusable = (byte) (maxOrder + 1);
        chunkSize = size;
        log2ChunkSize = log2(chunkSize);
        maxSubpageAllocs = 0;
    }

    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpageArray(int size) {
        return new PoolSubpage[size];
    }

    @Override
    public int usage() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }
        return usage(freeBytes);
    }

    private int usage(int freeBytes) {
        if (freeBytes == 0) {
            return 100;
        }

        int freePercentage = (int) (freeBytes * 100L / chunkSize);
        if (freePercentage == 0) {
            return 99;
        }
        return 100 - freePercentage;
    }

    long allocate(int normCapacity) {
        if ((normCapacity & subpageOverflowMask) != 0) { // >= pageSize
            return allocateRun(normCapacity);
        } else {
            return allocateSubpage(normCapacity);
        }
    }

    /**
     * Update method used by allocate
     * This is triggered only when a successor is allocated and all its predecessors
     * need to update their state
     * The minimal depth at which subtree rooted at id has some free space
     *
     * @param id id
     */
    private void updateParentsAlloc(int id) {
        while (id > 1) {
            int parentId = id >>> 1;//找到父节点
            byte val1 = value(id);//id节点
            byte val2 = value(id ^ 1);//id的兄弟节点
            byte val = val1 < val2 ? val1 : val2;//判断两个节点的值取大的那一个，这里就是unusable=12
            setValue(parentId, val);//设置成大的值
            id = parentId;//以此类推，更新父节点的值为子节点中大的值
        }
    }

    /**
     * Update method used by free
     * This needs to handle the special case when both children are completely free
     * in which case parent be directly allocated on request of size = child-size * 2
     *
     * @param id id
     */
    private void updateParentsFree(int id) {
        int logChild = depth(id) + 1;
        while (id > 1) {
            int parentId = id >>> 1;
            byte val1 = value(id);
            byte val2 = value(id ^ 1);
            logChild -= 1; // in first iteration equals log, subsequently reduce 1 from logChild as we traverse up

            if (val1 == logChild && val2 == logChild) {
                setValue(parentId, (byte) (logChild - 1));
            } else {
                byte val = val1 < val2 ? val1 : val2;
                setValue(parentId, val);
            }

            id = parentId;
        }
    }

    /**
     * Algorithm to allocate an index in memoryMap when we query for a free node
     * at depth d
     *
     * @param d depth
     * @return index in memoryMap
     */
    private int allocateNode(int d) {
        int id = 1;
        int initial = - (1 << d); // has last d bits = 0 and rest all = 1，翻译过来就是让最后d位为０，其他的全是１．
        byte val = value(id);//从根节点开始遍历，从memoryMap初始化来看，是从索引１开始的，０被预留．
        if (val > d) { // unusable，初始化时，每层所在的节点值都初始化为当前层数．如果根节点的值比给定的值大，说明存在子节点被分配的情况，而且剩余节点的内存大小不够
            return -1;
        }
        while (val < d || (id & initial) == 0) { // id & initial == 1 << d for all ids at depth d, for < d it is 0，解释一下，就是d层的id与-(1<<d)相&都=1，如果小于d层的id与-(1<<d)相&都是0，在
            //这里就是val=d的时候也要确保id是在d层，因为d层的id与-(1<<d)相&都=1.
            id <<= 1;//下一层，从memoryMap的拓扑图来看，左节点都是上层父节点的２倍．
            val = value(id);//从每层最左边的节点开始
            if (val > d) {//如果还是大，就要找其兄弟节点
                id ^= 1;//这个其实就是把id+1，找其兄弟节点．对上面的memoryMap拓扑图来看，举个例子：1024^1=1025，1025^1=1024，1024和1025都是512的子节点；而
                //1026^1=1027，1027^1=1026，而1026和1027都是513的子节点，很神奇吧？
                val = value(id);
            }
            //如果条件还是不满足，就从兄弟节点的下一层子节点继续查找，从查找的拓扑图来看如下：
            //                                [一]
            //                             /      \
            //                          [二]      [三]
            //                                     /    \
            //                                 [四]   [五]
            //                         ......................
            //循环就是第一次，第二次，第三次，第四次．．．．直到满足条件为止．
        }
        byte value = value(id);//当val=d时，并且id&initial != 0时候说明找到了可用节点
        assert value == d && (id & initial) == 1 << d : String.format("val = %d, id & initial = %d, d = %d",
                value, id & initial, d);
        setValue(id, unusable); // mark as unusable，把扎个id标识为最大层+1，也就是maxOrder+1，说明已经被用了，不能再用了．
        updateParentsAlloc(id);//并更新父节点的值(也就是层)为id所在的这一层，比如节点2048被分配出去，更新过程如下：
        //         [10]                                     [10]                                       [11]
        //       /      \            ------------>          /     \               ------------>        /     \
        //    [11]   [11]                            [12]  [11]                            [12]   [11]
        return id;
    }

    /**
     * Allocate a run of pages (>=1)
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     */
    private long allocateRun(int normCapacity) {
        int d = maxOrder - (log2(normCapacity) - pageShifts);
        int id = allocateNode(d);
        if (id < 0) {
            return id;
        }
        freeBytes -= runLength(id);
        return id;
    }

    /**
     * Create/ initialize a new PoolSubpage of normCapacity
     * Any PoolSubpage created/ initialized here is added to subpage pool in the PoolArena that owns this PoolChunk
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     */
    private long allocateSubpage(int normCapacity) {
        // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
        // This is need as we may add it back and so alter the linked-list structure.
        PoolSubpage<T> head = arena.findSubpagePoolHead(normCapacity);
        synchronized (head) {
            int d = maxOrder; // subpages are only be allocated from pages i.e., leaves(从memoryMap的叶子节点所在的层开始)
            int id = allocateNode(d);//这里是在分配Subpage时,从最底一层确定哪个叶子节点可用.
            if (id < 0) {
                return id;
            }

            final PoolSubpage<T>[] subpages = this.subpages;
            final int pageSize = this.pageSize;

            freeBytes -= pageSize;

            int subpageIdx = subpageIdx(id);//对第一个调用者来说，这里的id就是2048，而^2048的结果就是0．
            PoolSubpage<T> subpage = subpages[subpageIdx];
            if (subpage == null) {
                subpage = new PoolSubpage<T>(head, this, id, runOffset(id), pageSize, normCapacity);
                subpages[subpageIdx] = subpage;
            } else {
                subpage.init(head, normCapacity);
            }
            return subpage.allocate();
        }
    }

    /**
     * Free a subpage or a run of pages
     * When a subpage is freed from PoolSubpage, it might be added back to subpage pool of the owning PoolArena
     * If the subpage pool in PoolArena has at least one other PoolSubpage of given elemSize, we can
     * completely free the owning Page so it is available for subsequent allocations
     *
     * @param handle handle to free
     */
    void free(long handle) {
        int memoryMapIdx = memoryMapIdx(handle);//handle里包含了二叉树的索引
        int bitmapIdx = bitmapIdx(handle);//handle里也包含了Subpage中的内存段分配信息

        if (bitmapIdx != 0) { // free a subpage，如果有bitmapIdx，那就说明从Subpage中分配了内存
            PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];//找到具体的subpages
            assert subpage != null && subpage.doNotDestroy;

            // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
            // This is need as we may add it back and so alter the linked-list structure.
            PoolSubpage<T> head = arena.findSubpagePoolHead(subpage.elemSize);//通过创建时的subpage.elemSize从池中找到当初分配时的PoolSubpage头节点(在PoolChunk中
            //的每个PoolSubpage维护了一个链表，而这个链表的头就是在创建PoolSubpage时从PoolArena的tinySubpagePool/smallSubpagePool中根据elemSize找一个PoolSubpage
            //作为链表的头．
            synchronized (head) {
                if (subpage.free(head, bitmapIdx & 0x3FFFFFFF)) {//如果决定要移除subpage,返回false.
                    return;
                }
            }
        }
        freeBytes += runLength(memoryMapIdx);//增加释放的subpage的内存大小
        setValue(memoryMapIdx, depth(memoryMapIdx));//恢复对应二叉树的节点索引为原来的索引.因为在分配出去的时候,这个索引的值会被更改为unusable.
        updateParentsFree(memoryMapIdx);//同时更新父节点的索引值.
    }

    void initBuf(PooledByteBuf<T> buf, long handle, int reqCapacity) {
        int memoryMapIdx = memoryMapIdx(handle);
        int bitmapIdx = bitmapIdx(handle);
        if (bitmapIdx == 0) {
            byte val = value(memoryMapIdx);
            assert val == unusable : String.valueOf(val);
            buf.init(this, handle, runOffset(memoryMapIdx) + offset, reqCapacity, runLength(memoryMapIdx),
                     arena.parent.threadCache());
        } else {
            initBufWithSubpage(buf, handle, bitmapIdx, reqCapacity);
        }

        /**
         * 解释一下上面if/else的两个分支中的init，一个是buf.init：这个是从非Subpage层分配，因为bitmapIdx=0说明；而initBufWithSubpage是从叶子节点也就是Subpage层
         * 开始分配．
         */
    }

    void initBufWithSubpage(PooledByteBuf<T> buf, long handle, int reqCapacity) {
        initBufWithSubpage(buf, handle, bitmapIdx(handle), reqCapacity);
    }

    private void initBufWithSubpage(PooledByteBuf<T> buf, long handle, int bitmapIdx, int reqCapacity) {
        assert bitmapIdx != 0;

        int memoryMapIdx = memoryMapIdx(handle);

        /**
         * 定位到{@link PoolChunk}中的某个{@link PoolSubpage}之后，初始化前面通过{@code normalCapacity}创建的
         * {@link PooledByteBuf}．
         *
         * 初始化时，记录{@link PooledByteBuf}在二叉树中最底层叶子节点({@link PoolSubpage})从左到右的偏移．
         */
        PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
        assert subpage.doNotDestroy;
        assert reqCapacity <= subpage.elemSize;

        buf.init(
            this, handle,
            runOffset(memoryMapIdx) + (bitmapIdx & 0x3FFFFFFF) /*这个是最底层所分配的叶子节点Subpage的索引*/* subpage.elemSize/*每个Subpage的大小*/ + offset/*二叉树中最底层叶子节点({@link PoolSubpage})从左到右的偏移*/,
                reqCapacity/*真实的请求大小*/, subpage.elemSize/*规范后的请求大小，2的幂次方*/, arena.parent.threadCache());
    }

    private byte value(int id) {
        return memoryMap[id];
    }

    private void setValue(int id, byte val) {
        memoryMap[id] = val;
    }

    private byte depth(int id) {
        return depthMap[id];
    }

    private static int log2(int val) {
        // compute the (0-based, with lsb = 0) position of highest set bit i.e, log2
        return INTEGER_SIZE_MINUS_ONE - Integer.numberOfLeadingZeros(val);
    }

    private int runLength(int id) {
        // represents the size in #bytes supported by node 'id' in the tree
        return 1 << log2ChunkSize - depth(id);//-的优先级比<<高，所以先-再<<.
    }

    private int runOffset(int id) {
        // represents the 0-based offset in #bytes from start of the byte-array chunk
        int shift = id ^ 1 << depth(id);//<<优先级要比^高，所以先<<再^；depthMap初始的时候和memoryMap的结构以及数据是一样的，其值对应的是二叉树每个id所在层的层数；
        // 而id^1的结果只有两种：偶数的时候+1，奇数的时候-1，比如说id=10，那么结果就是11；id=45，结果就是44．对二叉树的节点来说，^的结果就是左右节点互换，知道
        //左节点的索引，通过^就得到右节点的索引；同样知道右节点的索引，通过^就得到左节点的索引．
        return shift * runLength(id);

        /**
         * 首先这个方法是在分配PoolSubpage时用到的，而每个PoolChunk(在默认情况下)都包含了2048个PoolSubpage，并且都是二叉树的叶子节点，即最底一层．也是在
         * 默认情况下每个PoolSubpage都是pageSize即8192大小，最低一层的所有叶子节点加起来就是8192*2048=16777216，而16777216就是chunkSize的大小．
         *
         * 多说一点，从上面构建的二叉树来看，最底一层即11层(默认)是最小的分配单元，而父节点能分配的最大就是两个子节点的和，比如10层的每个节点能分配的就是8192+8192=16384．
         * 所以对<8192的在PoolSubpage中分配，即在最底层；而>8192时，就要至少从最底一层的上一层开始，这也契合代码的分配逻辑．
         *
         * 在分配的过程中，根据分配的大小，比如说要分配16000的空间，因为>8192，所以会走到allocateRun()方法，而这个方法的算法一定会定位在１１层之上，
         * 参照{@link #allocate(int)} 方法的实现．
         *
         * 回到{@link #runOffset(int)} 方法，比较巧妙的得到最底一层每个索引对应的字节偏移，先看看{@link #runLength(int)} ：
         * log2ChunkSize就是log2(chunkSize)，默认情况夏chunkSize是16777216=2的24次方，所以log2ChunSize=24，depth[id]保存的就是原始层数，对最底一层来说是１１，
         * 24-11=13，而1<<13就是2的13次方，等于8192，对１０层来说，24-10=14，那么1<<14就是2的14次方，等于16384，正好是左右子节点之和，对９层来说，就是
         * 2的15次方，等于16384*2=32768，正好是左右子节点之和，以此类推．．．一直到第０层，第０层就是整个chunk的大小即chunSize=16777216大小，所以对{@link #runLength(int)}
         * 的结果其实就是确定id所在层的每个索引拥有可分配的字节数．再看看shift的计算：
         * int shift = id ^ 1 << depth(id)，先1<<depth(id)=1<<11=2048，对１１层来说就是2048，那么11层的id^2048的结果其实就是索引的从左到右的偏移，2048^2048=0,2049^2048=1,
         * 2050^2048=2,2051^2048=3．．．是不是明白了？
         * 所以整个{@link #runOffset(int)} 的结果就是每层从左到右的索引偏移*每个索引拥有可分配的字节数，这样就很容易根据id算出这个id所在的索引在整个{@link #subpages}数组中
         * 的字节偏移．That's Great!
         */
    }

    private int subpageIdx(int memoryMapIdx) {
        return memoryMapIdx ^ maxSubpageAllocs; // remove highest set bit, to get offset，这个解释有点晦涩，换个直观一点的，默认情况下maxSubpageAllocs=2048，而memoryMap
        //的二叉树有4096个节点，这里memoryMapIdx是二叉树中某个节点的索引，与2048异或，在memoryMapIdx<2048时，异或的结果就是2048+memoryMapIdx，在memoryMapIdx>=2048
        //<=4095时，异或的结果是0到2048，换句话说，如果memoryMapIdx的索引是0到2047时，其结果就是2048到4095；如果memoryMapIdx的索引是2048到4095时，其结果就是
        //0到2047.
    }

    private static int memoryMapIdx(long handle) {
        return (int) handle;
    }

    private static int bitmapIdx(long handle) {
        return (int) (handle >>> Integer.SIZE);
    }

    @Override
    public int chunkSize() {
        return chunkSize;
    }

    @Override
    public int freeBytes() {
        synchronized (arena) {
            return freeBytes;
        }
    }

    @Override
    public String toString() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }

        return new StringBuilder()
                .append("Chunk(")
                .append(Integer.toHexString(System.identityHashCode(this)))
                .append(": ")
                .append(usage(freeBytes))
                .append("%, ")
                .append(chunkSize - freeBytes)
                .append('/')
                .append(chunkSize)
                .append(')')
                .toString();
    }

    void destroy() {
        arena.destroyChunk(this);
    }
}
