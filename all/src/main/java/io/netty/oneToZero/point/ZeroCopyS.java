package io.netty.oneToZero.point;

/**
 * 零拷贝
 *  广义的零拷贝是指计算机操作的过程中，CPU不需要为数据在内存之间的拷贝消耗资源。
 *   Linux中的 sendfile()以及 Java NIO 中的 FileChannel.transferTo() 方法都实现了零拷贝的功能，
 *   而在 Netty 中也通过在 FileRegion 中包装了 NIO 的 FileChannel.transferTo() 方法实现了零拷贝。
 *
 *   Netty 中的零拷贝主要体现在三个方面：
 *      1 读写数据采用 direct-buffer
 *      2 CompositeByteBuf 类的使用
 *      3 文件传输采用 DefaultFileRegion，将文件直接通过 transferTo 发送到 Channel
 *
 */
public class ZeroCopyS {
}