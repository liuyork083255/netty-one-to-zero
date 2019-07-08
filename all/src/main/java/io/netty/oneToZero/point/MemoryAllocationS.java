package io.netty.oneToZero.point;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;

/**
 * 名词定义：
 *      Chunk: 内存块，一个连续内存空间的管理者。具体有堆内 Chunk 和堆外 Chunk。默认大小为16MB。它是由一个或者多个 page 构成，默认 2048 个 page
 *      Page: 内存页，一个单位的内存大小。Chunk 将自身申请的连续内存空间分割成相等大小的一堆 Page。通过对 Page 的分配来完成内存分配功能。 默认 8K
 *      PooledChunkList: 将有相同使用率区间的 Chunk 集中在一起形成的一个 Chunk 列表。目的在于高效的分配和管理。
 *      Arena: 竞技场，作为内存分配的总体入口，所有的内存分配动作都在这个类中对外提供。
 *
 * 相关核心类：
 *      {@link PooledByteBufAllocator}
 *      {@link io.netty.buffer.PoolArena}
 *      {@link io.netty.buffer.PoolChunk}
 *      {@link io.netty.buffer.PoolSubpage}
 *      {@link io.netty.buffer.PoolChunkList}
 *      {@link io.netty.buffer.PoolThreadCache}
 *
 *  Netty 内存分配包括了 堆内存和非堆内存 两类
 *
 * 分配区：
 *  内存池包含两层分配区：线程私有分配区和内存池公有分配区。
 *  当内存被分配给某个线程之后，在释放内存时释放的内存不会直接返回给公有分配区，而是直接在线程私有分配区中缓存，
 *  当线程频繁的申请内存时会提高分配效率，同时当线程申请内存的动作不活跃时可能会造成内存浪费的情况，
 *  这时候内存池会对线程私有分配区中的情况进行监控，当发现线程的分配活动并不活跃时会把线程缓存的内存块释放返回给公有区。
 *  Note:
 *      在整个内存分配时可能会出现分配的内存过大导致内存池无法分配的情况，这时候就需要 JVM 堆直接分配(针对堆内存)，所以严格的讲有三层分配区。
 *
 *  内存分配算法 Slab
 *      Slab 分配是将内存分割成大小不等的内存块，在用户线程请求时根据请求的内存大小分配最为贴近Size的内存快，减少内存碎片同时避免了内存浪费。
 *      分配情况：
 *          1 请求的内存大小是否超过了 chunkSize=16MiB，如果已超出说明一个该内存已经超出了一个 chunk 能分配的范围，这种内存内存池无法分配应由 JVM分配(针对堆内存)，直接返回原始大小。
 *          2 请求大小大于等于512，返回一个512的2次幂倍数当做最终的内存大小，当原始大小是512时，返回512，当原始大小在(512，1024]区间，返回1024，当在(1024，2048]区间，返回2048等等。
 *          3 请求大小小于512，返回一个16的整数倍，原始大小(0，16]区间返回16，(16，32]区间返回32，(32，48]区间返回48等等，这些大小的内存块在内存池中叫 tiny 块。
 *      1 分配的内存大小小于512时内存池分配 tiny 块，
 *      2 大小在 [512，pageSize] 区间时分配 small 块，tiny 块和 small 块基于 page 分配，
 *      3 分配的大小在(pageSize，chunkSize]区间时分配 normal 块，normal 块基于 chunk 分配，
 *      4 内存大小超过 chunk，内存池无法分配这种大内存，直接由 JVM 堆分配(针对堆内存)，内存池也不会缓存这种内存。
 *
 *  内存分配算法 Buddy
 *      Buddy 分配是把一块内存块等量分割回收时候进行合并，尽可能保证系统中有足够大的连续内存
 *  内存分配算法 Slab
 *      Slab 其实是 Buddy 的一种弥补，因为 Buddy 均分出来的page都比较大，如果申请一小块每次给的都是page，就很浪费，所以 Slab 细分，
 *      但是 Slab 的最核心思想还是缓存，netty 中使用的 PoolChunk PoolSubpage 实现，通过 PoolArena 来管理
 *
 *
 *  物理内存分配是以 chunk 为单位进行申请的，{@link io.netty.buffer.PoolArena#allocateNormal}，
 *  也就是每次都是真实分配物理内存大小为 chunkSize，是一块连续的空间，然后 page subPage 进行各自的划分，管理着自己的内存下标位置
 *
 *  通过 {@link PooledByteBufAllocator#DEFAULT} 可以获取池化堆内存或者直接内存，返回的对象分别是：PooledUnsafeHeapByteBuf 或者 PooledUnsafeDirectByteBuf；
 *  通过 {@link Unpooled} 可以获取非池化的堆内存或者直接内存，返回的对象分别是：InstrumentedUnpooledUnsafeHeapByteBuf 或者 InstrumentedUnpooledUnsafeNoCleanerDirectByteBuf
 *  经过分析，池化的内存真正存储的位置其实是 {@link io.netty.buffer.PoolChunk#memory}，所以池化的buf真正存储就是这个 memory，因为它们都是继承 {@link io.netty.buffer.PooledByteBuf}，
 *  而 {@link io.netty.buffer.PooledByteBuf#memory} 就是真正存储的物理内存，实际上就是对应的 {@link io.netty.buffer.PoolChunk#memory}
 *  如果是非池化的 buf，则他们分别继承 UnpooledHeapByteBuf 和 UnpooledUnsafeDirectByteBuf，它们各自存储的对应就是 {@link io.netty.buffer.UnpooledHeapByteBuf#array} 和
 *  {@link io.netty.buffer.UnpooledUnsafeDirectByteBuf#buffer}
 *
 *
 */
public class MemoryAllocationS {

    public void fun1() {
        PooledByteBufAllocator allocator = PooledByteBufAllocator.DEFAULT;

        ByteBuf heapBuffer = allocator.heapBuffer();


    }

}