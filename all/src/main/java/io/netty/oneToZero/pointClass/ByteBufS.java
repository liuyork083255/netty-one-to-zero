package io.netty.oneToZero.pointClass;

import io.netty.buffer.ByteBuf;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;

/**
 * {@link ByteBuf}
 *
 *  1 ByteBuf 提供了访问原生数组和 NIO buffer 访问简便的接口
 *  2 ByteBuf 的创建建议使用 {@link io.netty.buffer.Unpooled} 工具类，而非使用其构造方法
 *  3 ByteBuf 也可以也可以理解成底层采用数组存储，所以可以采用数组方式进行访问 byte b = buffer.getByte(i);
 *  4 ByteBuf 提供两个读和写指针来操作 ByteBuf，两个指针将 ByteBuf 分为三个区域
 *      0 <= readerIndex <= writerIndex <= capacity
 *      discardable bytes | readable bytes | writable bytes（readable bytes 就是真实的当前内容）
 *  5 任何以 read skip 开头的方法都将增加 readerIndex 的索引，在读取数据的时候，尽量判断是否可读，否则容易抛出 IndexOutOfBoundsException 异常
 *      while (buffer.isReadable()) {...}
 *  6 任何以 write 开头的方法都将增加 writerIndex 的索引，在写入的时候，尽量判断是否可写，否则容易抛出 IndexOutOfBoundsException 异常
 *      while (buffer.maxWritableBytes() >= 4) { buffer.writeInt(random.nextInt()); }
 *      Note: 写入的是 int 类型，所以判断其空间至少有四个字节，如果写入byte 则调用 .writeByte 方法
 *  7 读取后的空间称为  discardable bytes，读指针一直前移，直到等于写指针，那读取后端空间可以通过 {@link ByteBuf#discardReadBytes()} 回收
 *      before discardReadBytes()
 *      +-------------------+------------------+------------------+
 *      | discardable bytes |  readable bytes  |  writable bytes  |
 *      +-------------------+------------------+------------------+
 *      |                   |                  |                  |
 *      0      <=      readerIndex   <=   writerIndex    <=    capacity
 *
 *      after discardReadBytes()
 *      +------------------+--------------------------------------+
 *      |  readable bytes  |    writable bytes (got more space)   |
 *      +------------------+--------------------------------------+
 *      |                  |                                      |
 *      readerIndex (0) <= writerIndex (decreased)        <=        capacity
 *  8 清除 ByteBuf 的两个指针 readerIndex 和 writerIndex 方法 {@link ByteBuf#clear()}，只是重置两指针为0，并不会清除数据，和 {@link ByteBuffer#clear()} 方法不同
 *  9 可以从一个以后的 ByteBuf 中获取其视图 通过调用以下方法：
 *      {@link ByteBuf#duplicate()}
 *      {@link ByteBuf#slice()}
 *      {@link ByteBuf#slice(int, int)}
 *      {@link ByteBuf#readSlice(int)}
 *      {@link ByteBuf#retainedDuplicate()}
 *      {@link ByteBuf#retainedSlice()}
 *      {@link ByteBuf#retainedSlice(int, int)}
 *      {@link ByteBuf#readRetainedSlice(int)}
 *      Note:
 *          1 派生出来的 buffer 缓冲区有自己独立的读写指针，但是数据是共享的原生 buffer 缓冲区
 *            如果不想共享数据，完全独立，则调用 {@link ByteBuf#copy()} 方法即可
 *          2 派生出来的 buffer 缓冲区引用计数问题：
 *            前四个方法派生出来的 buffer：
 *              派生出来的 buffer 不会让原来的 buffer 引用计数增加 1;
 *            后四个方法派生出来的 buffer：
 *              派生出来的 buffer 会让原来的 buffer 引用计数增加 1;
 *            8 个方法派生出来的 buffer 和原来的 buffer 共享一个引用计数，也就是不管在 新的还是旧的 buffer 上增加或减少引用，它们都保持同时生效
 *  10 如果 ByteBuf 底层是用 byte[] 实现，比如堆内 buffer，那么可以通过调用 {@link ByteBuf#array()} 将之转为 byte[]
 *     但是如果是直接内存 buffer，则不能转为，并且抛出异常，所以调用 {@link ByteBuf#hasArray()} 方法进行判断先
 *  11 如果 ByteBuf 可以转为 NIO-buffer，那么可以调用 {@link ByteBuf#nioBuffer()}，如果不能转换，也会抛出异常，调用 {@link ByteBuf#nioBufferCount()} 验证
 *  12 ByteBuf 转字符串，需要调用 {@link ByteBuf#toString(Charset)}
 *      buffer.toString(Charset.forName("utf-8"))
 *
 */
public class ByteBufS {
}