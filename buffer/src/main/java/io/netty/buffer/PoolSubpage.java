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
 * one-to-zero:
 *  sub-page 是对 page 进一步细分的 buf，因为 page 默认大小为 8k
 *  所以 sub-page 进一步细分，但是大小不确定
 *
 * 分配算法：
 *      · 将申请大小标准化为2的n次方大小，定义为请求大小。
 *      · 选择合适的 Chunk 申请一个 Page。将得到的 Page 初始化为 SubPage，其内部按照请求大小均分为 h 份；并且将该 SubPage 放入 Arena 的 SubPage 列表中。
 *      · 从 SubPage 中选择一份没有使用的均分空间用于分配。设置其对应位图位置为 true（true意味着使用）
 *      · 后续有类似的请求，优先从 Arena 的 SubPage 列表中获取符合需求（SubPage的切分大小等于请求大小）的 SubPage 进行分配。
 *      · 如果一个 SubPage 的内部空间全部被分配完毕，则从 Arena 的列表中删除。
 * 释放算法：
 *      · 将 SubPage 对应区域的位图设置为false
 *      · 如果本 SubPage 的可用区域刚恢复为1，则将 SubPage 添加到 Arena 的 SubPage 列表中
 *
 * 管理方式：
 *      Chunk 本身是不进行 SubPage 的管理的，只负责 SubPage 的初始化和存储。具体的分配动作则移交给 Arena 负责。
 *      核心策略如下：
 *          · 按照请求大小区分为 tiny（区间为[0,512)）,small（区间为[512,pageSize)）,normal(区间为[pageSize,chunkSize])
 *          · 在 tiny 区间，按照 16B 为大小增长幅度，创建长度为 32 的 SubPage 数组，数组中每一个 SubPage 代表一个固定大小。
 *             SubPage 本身形成一个链表。数组中的 SubPage 作为链表的头，是一个特殊节点，不参与空间分配，只是起到标识作用。
 *          · 在 small 区间，按照每次倍增为增长服务，创建长度为 n（n为从512倍增到pageSize的次数）的 SubPage 数组，数组的含义同 tiny 的 SubPage 数组。
 *          · 每次申请小于 pageSize 的空间时，根据标准化后的申请大小，从 tiny 区间或 small 区间寻找到对应大小的数组下标，并且在 SubPage 链表上寻找 SubPage 用于分配空间。
 *          · 如果 SubPage 链表上没有可用节点时，则从 Chunk 上申请一个 Page，然后初始化一个 SubPage 用于分配。
 *
 * SubPage 实际上只是定义了一些元信息，本身不做任何存储
 *  Chunk 是真实的物理内存分配类，Chunk 会被均分为大小相同 page，但是这个 page 是虚拟的，真正的 page 就是 这里的 {@link PoolSubpage}
 *  也就是说一个大小为8k的 page 其实就是一个 {@link PoolSubpage}
 *
 *  {@link PoolSubpage} 内部是均分的，均分值{@link #elemSize} 是根据用户第一次请求buf大小计算得来的，具体计算规则参考 {@link PoolArena#normalizeCapacity} 备注
 *
 */
final class PoolSubpage<T> implements PoolSubpageMetric {

    final PoolChunk<T> chunk;

    /** 当前 page 在 chunk 中的id，其实就是在 {@link PoolChunk#memoryMap} 中的值 */
    private final int memoryMapIdx;
    /** 当前 page 在 chunk.memory 的偏移量 */
    private final int runOffset;
    private final int pageSize;
    /**
     * 通过对每一个二进制位的标记来修改一段内存的占用状态
     * bitmap = new long[pageSize >>> 10]; => 8
     * page 大小是 8KB，而其最小分配单位是 16B，这里将整个 page 均分为 16B 的存储单元，一个分了 512 个
     * 为了记录每个存储的单元的分配情况，这里采用了点位记录方式，一个 long 有 64 bit，每一个 bit 就是一个分配状态，64 * 8 = 512 刚好可以记录所有的存储单元状态
     *
     *
     * 虽然 bitmap 可以表示 512 个段状态，但是当前的 subpage 不一定就会被分隔成 512 段，被分隔的段数是由用户真实数据包大小向上 2 次幂决定
     *
     * 极端情况是
     *  normalCapacity 最小 = 16B => page 被分成 512 段，bitmap 保存 512 个状态
     *  normalCapacity 最大 = 8KB => page 被分成 1 端，bitmap 保存 1 个状态
     *
     */
    private final long[] bitmap;

    PoolSubpage<T> prev;
    PoolSubpage<T> next;

    boolean doNotDestroy;

    /** 该 page 切分后每一段的大小 */
    int elemSize;

    /** 该page包含的段数量，maxNumElems = pageSize/elemSize */
    private int maxNumElems;

    /** 表示 bitmap 的实际大小被用到了几个 long 字段来存储状态 */
    private int bitmapLength;

    /**
     * 下一个可用的位置
     */
    private int nextAvail;

    /** 可用的段数量 */
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

    /**
     * chunk
     * @param head
     * @param elemSize  这个参数决定这当前这个 page 应该怎么被均分; 计算规则参考 {@link PoolArena#normalizeCapacity} 备注
     *                  其就是根据 reqCapacity 向上首次 2 次幂计算而来
     */
    PoolSubpage(PoolSubpage<T> head, PoolChunk<T> chunk, int memoryMapIdx, int runOffset, int pageSize, int elemSize) {
        this.chunk = chunk;
        this.memoryMapIdx = memoryMapIdx;
        this.runOffset = runOffset;
        this.pageSize = pageSize;
        bitmap = new long[pageSize >>> 10]; // pageSize / 16 / 64 = 8
        init(head, elemSize);
    }

    /**
     * init 仅仅是初始化，不会分配内存，分配方法是 {@link #allocate}
     */
    void init(PoolSubpage<T> head, int elemSize) { // elemSize 最小值 16
        doNotDestroy = true;
        this.elemSize = elemSize;
        if (elemSize != 0) {
            maxNumElems = numAvail = pageSize / elemSize;
            nextAvail = 0;
            bitmapLength = maxNumElems >>> 6;  // 1 先除以 64(一个long就是64位)获取到整数倍
            if ((maxNumElems & 63) != 0) {     // 2 然后再判断是否可以被64整除，如果不能，则bitmapLength+1，说明需要多一个long来存储状态
                bitmapLength ++;
            }

            for (int i = 0; i < bitmapLength; i ++) {
                bitmap[i] = 0; // bitmap 标识哪个子page 被分配   0 标识未分配, 1 表示已分配
            }
        }
        addToPool(head);
    }

    /**
     * Returns the bitmap index of the subpage allocation.
     *
     * 返回一个64位的long类型，具体含义是：
     *  低32位表示二叉树中的分配的节点，高32位表示均等切分小块的块号
     *
     * @return handle 指向的是当前chunk 中的唯一的一块内存
     */
    long allocate() {
        if (elemSize == 0) {
            return toHandle(0);
        }

        if (numAvail == 0 || !doNotDestroy) {
            return -1;
        }

        final int bitmapIdx = getNextAvail(); // 取一个 bitmap 中可用的 bit index
        int q = bitmapIdx >>> 6; // 除以 64， 就是 bitmap 中的偏移量，也就是 bitmap 中第几个 long 字段
        int r = bitmapIdx & 63; // 除以 64 取余, 拿到了第几个 long 字段后，再获取 64 bit 中的具体偏移量
        assert (bitmap[q] >>> r & 1) == 0; //
        bitmap[q] |= 1L << r; // | 计算，有1则1

        if (-- numAvail == 0) { // 如果可用的缓存快 =0，说明当前这个page(其实就是 subpage)下已经没有可用的缓存了
            removeFromPool(); // 移除当前page
        }

        return toHandle(bitmapIdx); // 处理高32位和低32位
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
            return true;
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

    /**
     * 返回值是对应 bitmap 中可用的 bit 下标
     */
    private int getNextAvail() {
        int nextAvail = this.nextAvail;
        if (nextAvail >= 0) {
            this.nextAvail = -1;
            return nextAvail;
        }
        return findNextAvail(); // 如果 <0 则代表没有被释放的子块, 则通过 findNextAvail 方法进行查找
    }

    /**
     * 遍历 bitmap 中的每一个元素, 如果当前元素中所有的比特位并没有全部标记被使用,
     * 则通过 findNextAvail0(i,bits) 方法一个一个往后找标记未使用的比特位
     */
    private int findNextAvail() {
        final long[] bitmap = this.bitmap;
        final int bitmapLength = this.bitmapLength;
        for (int i = 0; i < bitmapLength; i ++) {
            long bits = bitmap[i];
            if (~bits != 0) { // ~全部取反，如果不等于0，说明取反前肯定有bit=0，说明 64 bit中还有可用的存储块
                return findNextAvail0(i, bits);
            }
        }
        return -1;
    }

    /**
     * 从 i 开始的下标往前左查找可用的块 index
     * @param i 表示 bitmap 的第几个 long 字段
     * @param bits 就是存储的long类型bit状态
     * @return 返回可用的被分割的块下标 index
     */
    private int findNextAvail0(int i, long bits) {
        final int maxNumElems = this.maxNumElems;
        final int baseVal = i << 6; // 乘以 64，就获取到了真实偏移量的开始位置

        for (int j = 0; j < 64; j ++) {
            if ((bits & 1) == 0) { // 判断long的bit第一位是不是0，等于0说明还没有分配
                int val = baseVal | j;  // baseVal是左移(6<<)来的，j最大就是64，所以这里的 | 计算就是相加，相加的结果就是 bitmap 中的真实bit偏移量
                if (val < maxNumElems) { // 不能大于最大切割块数
                    return val;
                } else {
                    break;
                }
            }
            bits >>>= 1; // 右移一位
        }
        return -1;
    }

    /**
     * handle 指向的是当前chunk 中的唯一的一块内存，将 bitmapIdx 左移32后分别加上 0x4000000000000000L 和 memoryMapIdx
     * 0x4000000000000000L 是0100..(64)位，也就是正数首位为1，其余全为0
     * memoryMapIdx 是当前 page 在 chunk 中的id，其实就是在 {@link PoolChunk#memoryMap} 中的下标索引 index
     *
     * 这样低32位表示二叉树中的分配的节点，高32位表示subPage中分配的具体位置
     * @param bitmapIdx bitmap 中可用的 bit index，因为最小是按16B切割，所以其最大值是512
     * @return 返回的值就可以指向 chunk 中的指定 page(subpage) 中的指定块内存
     */
    private long toHandle(int bitmapIdx) {
        return 0x4000000000000000L | (long) bitmapIdx << 32 | memoryMapIdx;
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
            return "(" + memoryMapIdx + ": not in use)";
        }

        return "(" + memoryMapIdx + ": " + (maxNumElems - numAvail) + '/' + maxNumElems +
                ", offset: " + runOffset + ", length: " + pageSize + ", elemSize: " + elemSize + ')';
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
        return pageSize;
    }

    void destroy() {
        if (chunk != null) {
            chunk.destroy();
        }
    }
}
