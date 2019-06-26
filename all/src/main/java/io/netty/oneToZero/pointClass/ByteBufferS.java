package io.netty.oneToZero.pointClass;

/**
 * {@link java.nio.ByteBuffer}
 *  java 原生的 nio 字节缓冲区
 *  ByteBuffer 的底层存储结构对于堆内存和直接内存分别表现为堆上的一个byte[]对象和直接内存上分配的一块内存区域。
 *
 *  ByteBuffer 有四个重要的属性，分别为：mark、position、limit、capacity，和两个重要方法分别为：flip 和clear。
 *  四个 int 类型指针：
 *      position:   读写指针，代表当前读或写操作的位置，这个值总是小于等于limit的。
 *      mark:       在使用 ByteBuffer 的过程中，如果想要记住当前的 position，则会将当前的 position 值给 mark，让需要恢复的时候，再将 mark 的值给 position。
 *      capacity:   代表这块内存区域的大小。
 *      limit:      初始的 Buffer 中，limit 和 capacity 的值是相等的，通常在 clear 操作和 flip 操作的时候会对这个值进行操作，
 *                  在 clear 操作的时候会将这个值和 capacity 的值设置为相等，当 flip 的时候会将当前的 position 的值给 limit，
 *                  可以总结在写的时候:
 *                      limit 的值代表最大的可写位置，在读的时候，limit的值代表最大的可读位置。clear 是为了写作准备、flip 是为了读做准备。
 *
 *
 * 在JAVA NIO中，原生的 ByteBuffer 家族成员很简单，主要是 HeapByteBuffer、DirectByteBuffer 和 MappedByteBuffer
 *      HeapByteBuffer      是基于堆上字节数组为存储结构的缓冲区。
 *      DirectByteBuffer    是基于直接内存上的内存区域为存储结构的缓冲区。
 *      MappedByteBuffer    主要是文件操作相关的，它提供了一种基于虚拟内存映射的机制，使得我们可以像操作文件一样来操作文件，
 *                          而不需要每次将内容更新到文件之中，同时读写效率非常高。
 *
 */
public class ByteBufferS {
}