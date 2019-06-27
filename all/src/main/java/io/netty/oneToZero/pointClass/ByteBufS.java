package io.netty.oneToZero.pointClass;

import io.netty.buffer.AbstractByteBuf;
import io.netty.buffer.ByteBuf;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;

/**
 * {@link ByteBuf}
 *
 *  1 ByteBuf 提供了访问原生数组和 NIO buffer 访问简便的接口
 *
 *  2 ByteBuf 的创建建议使用 {@link io.netty.buffer.Unpooled} 工具类，而非使用其构造方法
 *      Unpooled.directBuffer(2, 10);
 *          第一个参数时初始化大小;
 *          第二个参数是缓冲区总大小，默认是 Integer.MAX_VALUE
 *
 *  3 ByteBuf 也可以也可以理解成底层采用数组存储，所以可以采用数组方式进行访问 byte b = buffer.getByte(i);
 *
 *  4 ByteBuf 提供两个读和写指针来操作 ByteBuf，两个指针将 ByteBuf 分为三个区域
 *      0 <= readerIndex <= writerIndex <= capacity
 *      discardable bytes | readable bytes | writable bytes（readable bytes 就是真实的当前内容）
 *
 *  5 任何以 read skip 开头的方法都将增加 readerIndex 的索引，在读取数据的时候，尽量判断是否可读，否则容易抛出 IndexOutOfBoundsException 异常
 *      while (buffer.isReadable()) {...}
 *    也即是意味着调用 {@link ByteBuf#getInt(int)} 方法是不会改变 readerIndex 索引的
 *
 *  6 任何以 write 开头的方法都将增加 writerIndex 的索引，在写入的时候，尽量判断是否可写，否则容易抛出 IndexOutOfBoundsException 异常
 *      while (buffer.maxWritableBytes() >= 4) { buffer.writeInt(random.nextInt()); }
 *      Note: 写入的是 int 类型，所以判断其空间至少有四个字节，如果写入byte 则调用 .writeByte 方法
 *    也即是意味着调用 {@link ByteBuf#setInt(int, int)} 方法是不会改变 writerIndex 索引的
 *
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
 *
 *  8 清除 ByteBuf 的两个指针 readerIndex 和 writerIndex 方法 {@link ByteBuf#clear()}，只是重置两指针为0，并不会清除数据，和 {@link ByteBuffer#clear()} 方法不同
 *      这方法比 {@link ByteBuf#discardReadBytes()} 轻量的多，前提是数据都读取完毕了
 *
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
 *
 *  10 如果 ByteBuf 底层是用 byte[] 实现，比如堆内 buffer，那么可以通过调用 {@link ByteBuf#array()} 将之转为 byte[]
 *     但是如果是直接内存 buffer，则不能转为，并且抛出异常，所以调用 {@link ByteBuf#hasArray()} 方法进行判断先
 *
 *  11 如果 ByteBuf 可以转为 NIO-buffer，那么可以调用 {@link ByteBuf#nioBuffer()}，如果不能转换，也会抛出异常，调用 {@link ByteBuf#nioBufferCount()} 验证
 *
 *  12 ByteBuf 转字符串，需要调用 {@link ByteBuf#toString(Charset)}
 *      buffer.toString(Charset.forName("utf-8"))
 *
 * 直接实现类 {@link AbstractByteBuf}
 *
 */
public class ByteBufS {

    /*
     * 可以使用两种方式对 ByteBuf 进行分类：按底层实现方式和按是否使用对象池。
     */

    /**
     * 按底层实现
     *
     * 1 HeapByteBuf
     *   HeapByteBuf 的底层实现为 JAVA 堆内的字节数组。堆缓冲区与普通堆对象类似，位于 JVM 堆内存区，可由GC回收，
     *   其申请和释放效率较高。常规 JAVA 程序使用建议使用该缓冲区。
     *
     * 2 DirectByteBuf
     *   DirectByteBuf 的底层实现为操作系统内核空间的字节数组。直接缓冲区的字节数组位于 JVM 堆外的 native 堆，由操作系统管理申请和释放，
     *   而 DirectByteBuf 的引用由 JVM 管理。直接缓冲区由操作系统管理，
     *   一方面，申请和释放效率都低于堆缓冲区;
     *   另一方面，却可以大大提高 IO 效率。由于进行 IO 操作时，常规下用户空间的数据（JAVA堆缓冲区）需要拷贝到内核空间（直接缓冲区），
     *   然后内核空间写到网络 SOCKET 或者 文件中。如果在用户空间取得直接缓冲区，可直接向内核空间写数据，减少了一次拷贝，可大大提高IO效率，这也是常说的零拷贝。
     * 3 CompositeByteBuf
     *   CompositeByteBuf，顾名思义，有以上两种方式组合实现。这也是一种零拷贝技术，想象将两个缓冲区合并为一个的场景，
     *   一般情况下，需要将后一个缓冲区的数据拷贝到前一个缓冲区；而使用组合缓冲区则可以直接保存两个缓冲区，
     *   因为其内部实现组合两个缓冲区并保证用户如同操作一个普通缓冲区一样操作该组合缓冲区，从而减少拷贝操作。
     *
     */

    /**
     * 按是否使用对象池
     * 1 UnpooledByteBuf
     *   UnpooledByteBuf 为不使用对象池的缓冲区，不需要创建大量缓冲区对象时建议使用该类缓冲区。
     *
     * 2 PooledByteBuf
     *   PooledByteBuf 为对象池缓冲区，当对象释放后会归还给对象池，所以可循环使用。当需要大量且频繁创建缓冲区时，
     *   建议使用该类缓冲区。Netty4.1 默认使用对象池缓冲区，4.0 默认使用非对象池缓冲区。
     *
     *
     */

}