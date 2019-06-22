package io.netty.oneToZero.pointClass;

/**
 * {@link io.netty.handler.stream.ChunkedWriteHandler}
 * 用于专门写入大数据流的 handler，比如大文件传输，如果茫然写入 会导致 {@link io.netty.channel.WriteBufferWaterMark} 和 socket
 * 缓冲异常，所以如果自己实现，就需要每次调用 {@link io.netty.channel.Channel#isWritable()} 判断水位线，加大了编程难度
 *
 *
 */
public class ChunkedWriteHandlerS {
}