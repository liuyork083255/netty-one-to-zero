package io.netty.oneToZero.point;

/**
 * 堆内存
 *  HeapBuffer 即分配在 JVM 堆内存区域的缓冲区，我们可以简单理解为 HeapBuffer 就是 byte[] 数组的一种封装形式。
 *
 * 不管是直接内存还是堆内存，netty 都对它们做了池化，
 *  DirectBuffer 的池化实现对象是 {@link io.netty.buffer.PooledDirectByteBuf}
 *               非池化{@link io.netty.buffer.UnpooledDirectByteBuf}
 *  HeapBuffer 的池化实现兑现是 {@link io.netty.buffer.PooledHeapByteBuf}
 *               非池化{@link io.netty.buffer.UnpooledHeapByteBuf}
 *
 */
public class HeapBufferS {
}
