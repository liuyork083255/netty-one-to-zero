package io.netty.oneToZero.point;

/**
 * 零拷贝
 *  广义的零拷贝是指计算机操作的过程中，CPU不需要为数据在内存之间的拷贝消耗资源。
 *   Linux中的 sendfile()以及 Java NIO 中的 FileChannel.transferTo() 方法都实现了零拷贝的功能，
 *   而在 Netty 中也通过在 FileRegion 中包装了 NIO 的 FileChannel.transferTo() 方法实现了零拷贝。
 *
 */
public class ZeroCopyS {
}