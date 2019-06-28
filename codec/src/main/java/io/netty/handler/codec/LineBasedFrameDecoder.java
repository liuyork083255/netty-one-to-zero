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
package io.netty.handler.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ByteProcessor;

import java.util.List;

/**
 * A decoder that splits the received {@link ByteBuf}s on line endings.
 * <p>
 * Both {@code "\n"} and {@code "\r\n"} are handled.
 * <p>
 * The byte stream is expected to be in UTF-8 character encoding or ASCII. The current implementation
 * uses direct {@code byte} to {@code char} cast and then compares that {@code char} to a few low range
 * ASCII characters like {@code '\n'} or {@code '\r'}. UTF-8 is not using low range [0..0x7F]
 * byte values for multibyte codepoint representations therefore fully supported by this implementation.
 * <p>
 * For a more general delimiter-based decoder, see {@link DelimiterBasedFrameDecoder}.
 *
 * one-to-zero:
 *  以行为单位进行划分的解码器，支持 "\n" "\r\n"
 *
 */
public class LineBasedFrameDecoder extends ByteToMessageDecoder {

    /**
     * Maximum length of a frame we're willing to decode.
     * 如果输入的字节数超过了 maxLength 还没有换行符出现，则会抛出异常
     */
    private final int maxLength;

    /**
     * Whether or not to throw an exception as soon as we exceed maxLength.
     *  true: 如果超过了 maxLength 立马抛出异常
     *  false:如果超过了 maxLength 不立马抛出，而是继续解码当前的 buf，然后再抛出异常
     *  默认 false
     */
    private final boolean failFast;

    /**
     * 分隔符是否需要被移除
     */
    private final boolean stripDelimiter;

    /**
     * True if we're discarding input because we're already over maxLength.
     *
     * true:表明当前缓冲区的数据已经超过最大长度 {@link #maxLength} 没有出现换行符
     *
     */
    private boolean discarding;

    private int discardedBytes;

    /** Last scan position. */
    private int offset;

    /**
     * Creates a new decoder.
     * @param maxLength  the maximum length of the decoded frame.
     *                   A {@link TooLongFrameException} is thrown if
     *                   the length of the frame exceeds this value.
     */
    public LineBasedFrameDecoder(final int maxLength) {
        this(maxLength, true, false);
    }

    /**
     * Creates a new decoder.
     * @param maxLength  the maximum length of the decoded frame.
     *                   A {@link TooLongFrameException} is thrown if
     *                   the length of the frame exceeds this value.
     * @param stripDelimiter  whether the decoded frame should strip out the
     *                        delimiter or not
     * @param failFast  If <tt>true</tt>, a {@link TooLongFrameException} is
     *                  thrown as soon as the decoder notices the length of the
     *                  frame will exceed <tt>maxFrameLength</tt> regardless of
     *                  whether the entire frame has been read.
     *                  If <tt>false</tt>, a {@link TooLongFrameException} is
     *                  thrown after the entire frame that exceeds
     *                  <tt>maxFrameLength</tt> has been read.
     */
    public LineBasedFrameDecoder(final int maxLength, final boolean stripDelimiter, final boolean failFast) {
        this.maxLength = maxLength;
        this.failFast = failFast;
        this.stripDelimiter = stripDelimiter;
    }

    @Override
    protected final void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        Object decoded = decode(ctx, in);
        if (decoded != null) {
            out.add(decoded);
        }
    }

    /**
     * Create a frame out of the {@link ByteBuf} and return it.
     *
     * @param   ctx             the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param   buffer          the {@link ByteBuf} from which to read data
     * @return  frame           the {@link ByteBuf} which represent the frame or {@code null} if no frame could
     *                          be created.
     */
    protected Object decode(ChannelHandlerContext ctx, ByteBuf buffer) throws Exception {
        final int eol = findEndOfLine(buffer);
        /*
         * 这个方法只要从 channel 中读取一次数据就会触发一次
         * 读取数据很有可能没有包含 换行符
         * 所以缓冲区可以一直添加，但是每次添加都会判断总的容量是否超过了 maxLength 最大容量，如果超过了就会修改 discarding 状态
         * 所以这里会判断
         */
        if (!discarding) {
            /* 进入这个分支表示之前的数据容量还没有超过 maxLength 最大容量 */
            if (eol >= 0) {
                /* 进入这个分支表示找到了分隔符 */
                final ByteBuf frame;
                final int length = eol - buffer.readerIndex();
                final int delimLength = buffer.getByte(eol) == '\r'? 2 : 1;

                /* 判断是否最大长度是否超过了定义值 */
                if (length > maxLength) {
                    buffer.readerIndex(eol + delimLength);
                    fail(ctx, length);
                    return null;
                }
                /* 判断是否去掉换行符 默认去除 */
                if (stripDelimiter) {
                    /* 这个方法是会移动 读索引，但是移动的范围并没有包含换行符，如果需要去掉换行符则需要主动移动读索引 */
                    frame = buffer.readRetainedSlice(length);
                    /* 移动读索引，达到去掉换行符效果 */
                    buffer.skipBytes(delimLength);
                } else {
                    frame = buffer.readRetainedSlice(length + delimLength);
                }

                return frame;
            } else {
                /* 进入这分支表示没有找到分隔符 */
                final int length = buffer.readableBytes();
                /* 判断是否超过最大值 */
                if (length > maxLength) {
                    discardedBytes = length;
                    buffer.readerIndex(buffer.writerIndex());
                    /* 标记状态 */
                    discarding = true;
                    offset = 0;
                    /* 判断是否需要立马抛出异常 */
                    if (failFast) {
                        fail(ctx, "over " + discardedBytes);
                    }
                }
                return null;
            }
        } else {
            /* 进入这个分支说明之前的数据量已经超过了最大值 maxLength */

            if (eol >= 0) {
                 /* 进入这个分支说明找到换行符 */
                final int length = discardedBytes + eol - buffer.readerIndex();

                /* 判断分隔符的长度  \n = 1； \r\n = 2 */
                final int delimLength = buffer.getByte(eol) == '\r'? 2 : 1;
                buffer.readerIndex(eol + delimLength);
                discardedBytes = 0;
                discarding = false;
                if (!failFast) {
                    fail(ctx, length);
                }
            } else {
                /* 进入这个分支说明没有找到换行符 */

                /* 只要超过了最大容量没有出现换行符，那么这些数据将被丢弃，这里记录被丢弃的字节数，便于抛出异常跟踪 */
                discardedBytes += buffer.readableBytes();

                /* 将读索引直接移到写索引，表示数据读取完了，其实是全部丢弃 */
                buffer.readerIndex(buffer.writerIndex());
                // We skip everything in the buffer, we need to set the offset to 0 again.
                offset = 0;
            }
            return null;
        }
    }

    private void fail(final ChannelHandlerContext ctx, int length) {
        fail(ctx, String.valueOf(length));
    }

    private void fail(final ChannelHandlerContext ctx, String length) {
        ctx.fireExceptionCaught(
                new TooLongFrameException(
                        "frame length (" + length + ") exceeds the allowed maximum (" + maxLength + ')'));
    }

    /**
     * Returns the index in the buffer of the end of line found.
     * Returns -1 if no end of line was found in the buffer.
     */
    private int findEndOfLine(final ByteBuf buffer) {
        int totalLength = buffer.readableBytes();

        /* 遍历 buf 查找分隔符下边索引 */
        int i = buffer.forEachByte(buffer.readerIndex() + offset, totalLength - offset, ByteProcessor.FIND_LF);
        if (i >= 0) {
            offset = 0;
            /* 判断换行符是 \n 还是 \r\n */
            if (i > 0 && buffer.getByte(i - 1) == '\r') {
                /**
                 * 如果是 \r\n 需要往后移动一个索引，因为上面的 forEachByte 查询的是 {@link  ByteProcessor#FIND_LF }
                 * 查询的是 \n，由此可见，虽然是支持两种换行符，但是其实遍历的是 \n
                 */
                i--;
            }
        } else {
            offset = totalLength;
        }
        return i;
    }
}
