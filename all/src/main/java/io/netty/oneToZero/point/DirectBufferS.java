package io.netty.oneToZero.point;

/**
 * 直接缓冲区
 * DirectBuffer 顾名思义是分配在直接内存（Direct Memory）上面的内存区域。
 *
 * 在 JDK1.4 版本开始 NIO 引入的 Channel 与 Buffer 的 IO 方式使得我们可以使用native接口来在直接内存上分配内存，
 * 并用 JVM 堆内存上的一个引用来进行操作，当 JVM 堆内存上的引用被回收之后，这块直接内存才会被操作系统回收。
 *
 * 优点：
 *  1 避免数据在内核与用户空间之间拷贝
 *  2 访问速度快
 *
 * 缺点：
 *  1 分配与回收代价相对比较大
 * 根据其缺点，DirectBuffer 适用于缓冲区可以重复使用的场景。
 *
 * 不管是直接内存还是堆内存，netty 都对它们做了池化，
 *  DirectBuffer 的池化实现对象是 {@link io.netty.buffer.PooledDirectByteBuf}
 *               非池化{@link io.netty.buffer.UnpooledDirectByteBuf}
 *  HeapBuffer 的池化实现兑现是 {@link io.netty.buffer.PooledHeapByteBuf}
 *               非池化{@link io.netty.buffer.UnpooledHeapByteBuf}
 *
 */
public class DirectBufferS {
}
