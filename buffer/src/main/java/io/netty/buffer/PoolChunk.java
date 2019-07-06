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

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

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
 * we store 2 pieces of information depth_of_id and x as two byte values in memoryMap and depthMap respectively
 *
 * memoryMap[id]= depth_of_id  is defined above
 * depthMap[id]= x  indicates that the first node which is free to be allocated is at depth x (from root)
 *
 * one-to-zero:
 *  Chunk 的大小 = 2^{maxOrder} * pageSize，maxOrder 是 pageSize 二进制0的个数，因为 Chunk 采用完全二叉树算法管理 page，
 *  数的深度就是 maxOrder，page 默认大小是 8K，chunk 默认大小是 16MiB，所以一个 chunk 内部默认有 2048 个 page，所以这个完全二叉树共有 11 层，
 *  最后一层节点数与 page 数量相等。
 *
 *  所有子节点的管理的内存也属于其父节点。如果我们想获取一个 8K 的内存，则只需在第 11 层找一个可用节点即可，而如果我们需要 16K 的数据，则需要在第 10 层找一个可用节点
 *  Note：如果一个节点的子节点已经被分配了，则该节点不能被分配。
 *
 *  1 Chunk 内部管理着一个大块的连续内存区域，将这个内存区域切分成均等的大小，每一个大小称之为一个Page -> 即 Chunk 里面全是一个个 Page
 *  2 Chunk 内部采用完全二叉树的方式对 Page 进行管理
 *  3 Chunk 可以对大于等于 PageSize 的内存申请进行分配和管理
 *  4 但是如果申请小于 PageSize 的申请也要消耗掉一个Page的话就太浪费。因此设计一个内存结构 SubPage，该结构将一个 Page 的空间进行均分后分配，内部通过位图的方式管理分配信息。
 *  5 有了 Chunk 和 SubPage 后，对于大于 PageSize 的申请走 Chunk 的分配，小于 PageSize 的申请通过 SubPage 分配
 *  6 而为了进一步加快分配和释放的性能，设计一个线程变量 PoolThreadCache，该线程变量以线程缓存的方式将一部分内存空间缓存起来，当需要申请的时候优先尝试线程缓存。
 *    通过减少并发冲突的方式提高性能。
 *
 *  分配算法（参考/doc/内存分配Page完全二叉树结构图.png）：
 *      · 叶子节点的初始容量是单位大小。其可分配的容量是0（被分配走后）或者初始容量。
 *      · 父节点的可分配容量是两个子节点可分配容量的较大值；如果2个子节点均为初始容量，则父节点可分配容量为子节点分配容量之和（也就是2倍）；这条规则尤为重要，其是整个分配的核心。
 *      · 分配时首先需要将申请大小标准化为2n大小，定义该大小为目标大小。判断根节点可分配容量是否大于目标大小，如果大于才可以执行分配。
 *      · 通过计算公式算出可允许分配的二叉树层级（该层级是节点容量大于等于目标大小的最深层级）。得到目标层级后，从根节点开始向下搜索，直到搜索到目标层级停止。
 *         搜索过程中如果左子节点可分配容量小于目标大小则切换到右子节点继续搜索。
 *      · 如果分配成功，由于本节点的可分配容量下降至0，因此需要更新父节点的可分配容量。并且该操作需要不断向父节点回溯执行，直到根节点为止
 *
 *  释放算法（释放内存就是申请内存的逆操作）：
 *      · 通过节点下标寻找申请节点。
 *      · 将申请节点的可分配容量从0恢复到初始可分配容量。
 *      · 如果当前节点和兄弟节点的可分配容量都是初始可分配容量，更新父节点的可分配容量为其初始可分配容量；
 *         否则更新父节点的可分配容量为2个子节点中的较大值。重复该动作直到根节点为止。
 *
 * Chunk 中通过完全二叉树来管理 Page，并且使用数组来代表完全二叉树（根节点从下标1开始，这样左子节点的下标就是父节点下标2倍，父节点下标就是当前节点下标除以2），
 * 该数组存储的是该下标节点当前可分配容量。当最终申请成功时，是寻找到了一个可以分配的节点，返回该节点的下标就算申请完成。
 *
 * Chunk 移动：
 *      随着内存的申请和释放，Chunk 内部空间的使用率也在不断变化中。为了提高内存的使用率和分配效率。
 *      Arena 内部使用 ChunkList 将 chunk 进行集中管理。每一个 ChunkList 都代表着不同的使用率区间。
 *      当 Chunk 的使用率变化时可能就会在不同的 ChunkList 中移动。
 *
 *
 */
final class PoolChunk<T> implements PoolChunkMetric {

    private static final int INTEGER_SIZE_MINUS_ONE = Integer.SIZE - 1;

    /** chunk所属的arena */
    final PoolArena<T> arena;
    /**
     * 物理内存，内存请求者千辛万苦拐弯抹角就是为了得到它，在 HeapArena 中它就是一个 chunkSize 大小的 byte 数组。
     * memory 是一个容量为 chunkSize 的 byte[](heap方式)或 ByteBuffer(direct方式)
     */
    final T memory;
    /**  是否非池化 */
    final boolean unpooled;
    final int offset;

    /**
     * chunk 中的 page 采用 Buddy-伙伴分配算法，
     * Netty实现的伙伴分配算法中，构造了两棵满二叉树，满二叉树非常适合使用数组存储，Netty 使用两个字节数组 memoryMap 和 depthMap 来表示两棵二叉树，
     * 其中 MemoryMap 存放分配信息，depthMap 存放节点的高度信息
     *  memoryMap：表示每个节点的编号，注意从1开始，省略0是因为这样更容易计算父子关系：子节点加倍，父节点减半，比如512的子节点为1024=512 * 2
     *  depthMap： 表示每个节点的深度，注意从0开始
     *  初始状态时，memoryMap和depthMap相等，可知一个id为512节点的初始值为9
     *
     *  depthMap 的值初始化后不再改变，memoryMap 的值则随着节点分配而改变；该节点的值设置为12（最大高度+1）表示不可用，并且会更新祖先节点的值
     *
     *
     *
     * 默认长度 4096，第一个下标索引0的位置没有使用，默认一个chunk的page个数其实2048，这里长度是4096，原始这是一个二叉树结构，具体可以看 doc/内存分配Page完全二叉树结构图.png
     * 真正的page节点其实是从2048开始，4195结束
     *
     * 数组中每个位置保存的是该节点所在的层数，有什么作用？
     *  对于节点512其层数是9则：
     *      1、如果memoryMap[512]=9，则表示其本身到下面所有的子节点都可以被分配
     *      2、如果memoryMap[512]=10，则表示节点512下有子节点已经分配过，而其子节点中的第10层还存在未分配的节点
     *      3、如果memoryMap[512]=12(即总层数+1)可分配的深度已经大于总层数, 则表示该节点下的所有子节点都已经被分配
     *
     */
    private final byte[] memoryMap;

    private final byte[] depthMap;

    /**
     * 长度为 2048 的 PoolSubpage 数组，因为16MiB均分为8k
     * 分别对应二叉树中2048个叶子节点
     */
    private final PoolSubpage<T>[] subpages;

    /** Used to determine if the requested capacity is equal to or greater than pageSize. */
    private final int subpageOverflowMask;
    /** 每个 page 的大小，默认为 8K=8192 */
    private final int pageSize;
    /** 13,   2 ^ 13 = 8192 */
    private final int pageShifts;
    /** 默认 11 */
    private final int maxOrder;
    /** 默认 16MiB */
    private final int chunkSize;

    private final int log2ChunkSize;

    /**
     * sub page 个数 默认 2048
     */
    private final int maxSubpageAllocs;

    /**
     * Used to mark memory as unusable
     * 用于标记内存不可用
     * 默认值是二叉树深度加一 即11 + 1 = 12, 当 memoryMap[id] = unusable 时，则表示id节点已被分配
     */
    private final byte unusable;

    // Use as cache for ByteBuffer created from the memory. These are just duplicates and so are only a container
    // around the memory itself. These are often needed for operations within the Pooled*ByteBuf and so
    // may produce extra GC, which can be greatly reduced by caching the duplicates.
    //
    // This may be null if the PoolChunk is unpooled as pooling the ByteBuffer instances does not make any sense here.
    private final Deque<ByteBuffer> cachedNioBuffers;

    private int freeBytes;

    PoolChunkList<T> parent;
    PoolChunk<T> prev;
    PoolChunk<T> next;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /**
     *
     * @param arena
     * @param memory    真实的物理内存，要么是数组，要么是 ByteBuffer
     * @param pageSize  8k = 8192
     * @param maxOrder  默认 11
     * @param pageShifts    默认 13
     * @param chunkSize  默认 16MiB
     * @param offset    默认 0
     */
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
        /* 2 ^ 24 = 16M */
        log2ChunkSize = log2(chunkSize);
        subpageOverflowMask = ~(pageSize - 1);
        freeBytes = chunkSize;

        assert maxOrder < 30 : "maxOrder should be < 30, but is: " + maxOrder;
        /* 2048, 最多能被分配的 SubPage 个数 */
        maxSubpageAllocs = 1 << maxOrder;

        // Generate the memory map.
        /* 长度 4096 */
        memoryMap = new byte[maxSubpageAllocs << 1];
        /* 长度 4096 */
        depthMap = new byte[memoryMap.length];
        int memoryMapIndex = 1;
        /*
         *  分配完成后：
         *  memoryMap->[0, 0, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3…]
         *  depthMap->[0, 0, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3…]
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

        /*
         *  subPages 包含了 maxSubPageAllocs(2048) 个 PoolSubPage, 每个 subPage 会从 chunk 中分配到自己的内存段，
         *  两个 subPage 不会操作相同的段，此处只是初始化一个数组，还没有实际的实例化各个元素
         */
        subpages = newSubpageArray(maxSubpageAllocs);
        cachedNioBuffers = new ArrayDeque<ByteBuffer>(8);
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
        cachedNioBuffers = null;
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

    boolean allocate(PooledByteBuf<T> buf, int reqCapacity, int normCapacity) {
        /*
         * PoolSubpage分配的最后结果是一个long整数，
         * 其中低32位表示二叉树中的分配的节点，高32位表示subPage中分配的具体位置
         */
        final long handle;
        if ((normCapacity & subpageOverflowMask) != 0) { // >= pageSize
            handle =  allocateRun(normCapacity);
        } else {
            /* 如果小于 pageSize 的采用 subPage 分配 */
            handle = allocateSubpage(normCapacity);
        }

        if (handle < 0) {
            return false;
        }
        ByteBuffer nioBuffer = cachedNioBuffers != null ? cachedNioBuffers.pollLast() : null;
        initBuf(buf, nioBuffer, handle, reqCapacity);
        return true;
    }

    /**
     * Update method used by allocate
     * This is triggered only when a successor is allocated and all its predecessors
     * need to update their state
     * The minimal depth at which subtree rooted at id has some free space
     *
     * one-to-zero:
     *  id 是二叉树中的某一个节点
     *  方法含义是这个id对应的节点被分配，那么需要更新父节点，让其知晓子节点的分配情况
     *  父节点会一直递归更新，直到更新到根节点
     *
     */
    private void updateParentsAlloc(int id) {
        while (id > 1) {
            int parentId = id >>> 1;
            byte val1 = value(id);
            byte val2 = value(id ^ 1);
            byte val = val1 < val2 ? val1 : val2;
            setValue(parentId, val);
            id = parentId;
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
     * one-to-zero:
     *  因为二叉树每一层都有很多节点，除了第一层，其余的都是2个以上，所以具体是哪个可以分配，则由当前的 allocateNode 方法完成查找
     *  参数d 表示某一层节点的深度
     *  返回的是该层具体的被分配节点的编号
     */
    private int allocateNode(int d) {
        int id = 1;
        int initial = - (1 << d); // has last d bits = 0 and rest all = 1
        byte val = value(id);
        if (val > d) { // unusable
            return -1;
        }
        while (val < d || (id & initial) == 0) { // id & initial == 1 << d for all ids at depth d, for < d it is 0
            id <<= 1;
            val = value(id);
            if (val > d) {
                id ^= 1;
                val = value(id);
            }
        }
        byte value = value(id);
        assert value == d && (id & initial) == 1 << d : String.format("val = %d, id & initial = %d, d = %d",
                value, id & initial, d);

        /* 标记节点值为12，表示这个节点已被分配 */
        setValue(id, unusable); // mark as unusable
        updateParentsAlloc(id);
        return id;
    }

    /**
     * Allocate a run of pages (>=1)
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     *
     * one-to-zero:
     *  专门分配大于 pageSize 的内存，返回值是满足分配需求容量的normCapacity大小的 memoryMap 下标
     *
     * 案例：以一个Page大小为8KB，pageShifts=13，maxOrder=11 的配置为例分析分配 32KB=2^15B 内存的过程(假设该Chunk首次分配):
     *  1 计算满足所需内存的高度d，d= maxOrder-(log2(normCapacity)-pageShifts) = 11-(log2(2^15)-13) = 9。可知，满足需求的节点的最大高度d = 9
     *  2 在高度 <9 的层从左到右寻找满足需求的节点。由于二叉树不便于按层遍历，故需要从根节点1开始遍历。本例中，找到 id 为512的节点，满足需求，将 memory[512]设置为12表示分配
     *  3 从512节点开始，依次更新祖先节点的分配信息
     *
     */
    private long allocateRun(int normCapacity) {
        /* 计算满足需求的节点的高度 */
        int d = maxOrder - (log2(normCapacity) - pageShifts);
        /* 在该高度层找到空闲的节点 */
        int id = allocateNode(d);
        /* d < 0 表示没有找到 */
        if (id < 0) {
            return id;
        }
        /* 分配后剩余的字节数 */
        freeBytes -= runLength(id);
        return id;
    }

    /**
     * Create / initialize a new PoolSubpage of normCapacity
     * Any PoolSubpage created / initialized here is added to subpage pool in the PoolArena that owns this PoolChunk
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     *
     *
     * one-to-zero:
     *  返回参数是一个long整数，其中低32位表示二叉树中的分配的节点，高32位表示subPage中分配的具体位置
     *
     */
    private long allocateSubpage(int normCapacity) {
        // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
        // This is need as we may add it back and so alter the linked-list structure.
        /* 找到 arena 中对应的 subpage 头节点 */
        PoolSubpage<T> head = arena.findSubpagePoolHead(normCapacity);
        /*
         * subpage 只能在二叉树的最大高度分配即分配叶子节点，因为分配的是 < pageSize内存，
         * 而根据二叉树结构，最底层节点就是一个 page 大小的节点，所以肯定不需要父节点去寻找了
         */
        int d = maxOrder; // subpages are only be allocated from pages i.e., leaves
        synchronized (head) {
            /* 根据二叉树深度d，查找这一层可以分配的节点 */
            int id = allocateNode(d);
            if (id < 0) {
                return id;
            }

            final PoolSubpage<T>[] subpages = this.subpages;
            final int pageSize = this.pageSize;

            freeBytes -= pageSize;

            /*
             * 得到叶子节点的偏移索引，从0开始，即2048-0,2049-1,...
             */
            int subpageIdx = subpageIdx(id);
            PoolSubpage<T> subpage = subpages[subpageIdx];
            /*
             * subpage != null的情况产生的原因是：
             *  subpage 初始化后分配了内存，但一段时间后该subpage分配的内存释放并从arena的双向链表中删除，
             *  此时subpage不为null，当再次请求分配时，只需要调用init(也就是else分支逻辑)将其加入到areana的双向链表中即可
             */
            if (subpage == null) {
                /**
                 * 这里的 normCapacity 其实就是根据用户申请大小计算出来2的N次方大小 计算规则参考 {@link PoolArena#normalizeCapacity} 备注
                 * 这里创建 chunk 中的一个 PoolSubpage 对象，然后初始化其对应的元数据：主要是描述其在 chunk 中的位置，偏移量以及块大小
                 * 创建后将之添加到 {@link  #subpages} 中
                 */
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
    void free(long handle, ByteBuffer nioBuffer) {
        int memoryMapIdx = memoryMapIdx(handle);
        int bitmapIdx = bitmapIdx(handle);

        if (bitmapIdx != 0) { // free a subpage
            PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
            assert subpage != null && subpage.doNotDestroy;

            // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
            // This is need as we may add it back and so alter the linked-list structure.
            PoolSubpage<T> head = arena.findSubpagePoolHead(subpage.elemSize);
            synchronized (head) {
                if (subpage.free(head, bitmapIdx & 0x3FFFFFFF)) {
                    return;
                }
            }
        }
        freeBytes += runLength(memoryMapIdx);
        setValue(memoryMapIdx, depth(memoryMapIdx));
        updateParentsFree(memoryMapIdx);

        if (nioBuffer != null && cachedNioBuffers != null &&
                cachedNioBuffers.size() < PooledByteBufAllocator.DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK) {
            cachedNioBuffers.offer(nioBuffer);
        }
    }

    void initBuf(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity) {
        int memoryMapIdx = memoryMapIdx(handle);
        int bitmapIdx = bitmapIdx(handle);
        if (bitmapIdx == 0) {
            byte val = value(memoryMapIdx);
            assert val == unusable : String.valueOf(val);
            buf.init(this, nioBuffer, handle, runOffset(memoryMapIdx) + offset,
                    reqCapacity, runLength(memoryMapIdx), arena.parent.threadCache());
        } else {
            initBufWithSubpage(buf, nioBuffer, handle, bitmapIdx, reqCapacity);
        }
    }

    void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity) {
        initBufWithSubpage(buf, nioBuffer, handle, bitmapIdx(handle), reqCapacity);
    }

    private void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer,
                                    long handle, int bitmapIdx, int reqCapacity) {
        assert bitmapIdx != 0;

        int memoryMapIdx = memoryMapIdx(handle);

        PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
        assert subpage.doNotDestroy;
        assert reqCapacity <= subpage.elemSize;

        buf.init(this, nioBuffer, handle,
                runOffset(memoryMapIdx) + (bitmapIdx & 0x3FFFFFFF) * subpage.elemSize + offset,
                reqCapacity, subpage.elemSize, arena.parent.threadCache());
    }

    private byte value(int id) {
        return memoryMap[id];
    }

    /**
     * 标记节点对应的值
     */
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
        return 1 << log2ChunkSize - depth(id);
    }

    private int runOffset(int id) {
        // represents the 0-based offset in #bytes from start of the byte-array chunk
        int shift = id ^ 1 << depth(id);
        return shift * runLength(id);
    }

    private int subpageIdx(int memoryMapIdx) {
        return memoryMapIdx ^ maxSubpageAllocs; // remove highest set bit, to get offset
    }

    /**
     * 获取前32位的值，也就是 二叉树中的分配的节点
     */
    private static int memoryMapIdx(long handle) {
        return (int) handle;
    }

    /**
     * 获取后32位的值，也就是高32位表示subPage中分配的具体位置
     */
    private static int bitmapIdx(long handle) {
        /* 直接移动向右移动，然后转int即可 */
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
