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

import static io.netty.util.internal.ObjectUtil.checkPositive;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

import java.util.List;

/**
 * A decoder that splits the received {@link ByteBuf}s by one or more
 * delimiters.  It is particularly useful for decoding the frames which ends
 * with a delimiter such as {@link Delimiters#nulDelimiter() NUL} or
 * {@linkplain Delimiters#lineDelimiter() newline characters}.
 *
 * <h3>Predefined delimiters</h3>
 * <p>
 * {@link Delimiters} defines frequently used delimiters for convenience' sake.
 *
 * <h3>Specifying more than one delimiter</h3>
 * <p>
 * {@link DelimiterBasedFrameDecoder} allows you to specify more than one
 * delimiter.  If more than one delimiter is found in the buffer, it chooses
 * the delimiter which produces the shortest frame.  For example, if you have
 * the following data in the buffer:
 * <pre>
 * +--------------+
 * | ABC\nDEF\r\n |
 * +--------------+
 * </pre>
 * a {@link DelimiterBasedFrameDecoder}({@link Delimiters#lineDelimiter() Delimiters.lineDelimiter()})
 * will choose {@code '\n'} as the first delimiter and produce two frames:
 * <pre>
 * +-----+-----+
 * | ABC | DEF |
 * +-----+-----+
 * </pre>
 * rather than incorrectly choosing {@code '\r\n'} as the first delimiter:
 * <pre>
 * +----------+
 * | ABC\nDEF |
 * +----------+
 * </pre>
 *
 * one-to-zero:
 *  特定分隔符解码器，比 {@link LineBasedFrameDecoder} 更加灵活，因为后者只能分割 \n 和 \r\n 两种分隔符
 *  DelimiterBasedFrameDecoder 还支持多个分隔符
 *
 *
 */
public class DelimiterBasedFrameDecoder extends ByteToMessageDecoder {

    /**
     * 多个分隔符 buf 对象数组
     */
    private final ByteBuf[] delimiters;

    /**
     * 如果输入的字节数超过了 maxFrameLength 还没有换行符出现，则会抛出异常
     */
    private final int maxFrameLength;

    /** 分隔符是否需要被移除 */
    private final boolean stripDelimiter;

    /**
     * true: 如果超过了 maxLength 立马抛出异常
     * false:如果超过了 maxLength 不立马抛出，而是继续解码当前的 buf，然后再抛出异常
     * 默认 true
     */
    private final boolean failFast;

    /**
     * 记录是否数据过大超过容量
     * true：表示超过容量
     * false：表示没有超过，这个值会直到找到分隔符后，从状态 true 改为 false
     *        详见 {@link #decode(ChannelHandlerContext, ByteBuf)}
     */
    private boolean discardingTooLongFrame;

    /**
     * 记录丢弃了多少字节
     */
    private int tooLongFrameLength;

    /** Set only when decoding with "\n" and "\r\n" as the delimiter.  */
    private final LineBasedFrameDecoder lineBasedDecoder;

    /**
     * Creates a new instance.
     *
     * @param maxFrameLength  the maximum length of the decoded frame.
     *                        A {@link TooLongFrameException} is thrown if
     *                        the length of the frame exceeds this value.
     * @param delimiter  the delimiter
     */
    public DelimiterBasedFrameDecoder(int maxFrameLength, ByteBuf delimiter) {
        this(maxFrameLength, true, delimiter);
    }

    /**
     * Creates a new instance.
     *
     * @param maxFrameLength  the maximum length of the decoded frame.
     *                        A {@link TooLongFrameException} is thrown if
     *                        the length of the frame exceeds this value.
     * @param stripDelimiter  whether the decoded frame should strip out the
     *                        delimiter or not
     * @param delimiter  the delimiter
     */
    public DelimiterBasedFrameDecoder(int maxFrameLength, boolean stripDelimiter, ByteBuf delimiter) {
        this(maxFrameLength, stripDelimiter, true, delimiter);
    }

    /**
     * Creates a new instance.
     *
     * @param maxFrameLength  the maximum length of the decoded frame.
     *                        A {@link TooLongFrameException} is thrown if
     *                        the length of the frame exceeds this value.
     * @param stripDelimiter  whether the decoded frame should strip out the
     *                        delimiter or not
     * @param failFast  If <tt>true</tt>, a {@link TooLongFrameException} is
     *                  thrown as soon as the decoder notices the length of the
     *                  frame will exceed <tt>maxFrameLength</tt> regardless of
     *                  whether the entire frame has been read.
     *                  If <tt>false</tt>, a {@link TooLongFrameException} is
     *                  thrown after the entire frame that exceeds
     *                  <tt>maxFrameLength</tt> has been read.
     * @param delimiter  the delimiter
     */
    public DelimiterBasedFrameDecoder(int maxFrameLength, boolean stripDelimiter, boolean failFast, ByteBuf delimiter) {
        this(maxFrameLength, stripDelimiter, failFast, new ByteBuf[] {delimiter.slice(delimiter.readerIndex(), delimiter.readableBytes())});
    }

    /**
     * Creates a new instance.
     *
     * @param maxFrameLength  the maximum length of the decoded frame.
     *                        A {@link TooLongFrameException} is thrown if
     *                        the length of the frame exceeds this value.
     * @param delimiters  the delimiters
     */
    public DelimiterBasedFrameDecoder(int maxFrameLength, ByteBuf... delimiters) {
        this(maxFrameLength, true, delimiters);
    }

    /**
     * Creates a new instance.
     *
     * @param maxFrameLength  the maximum length of the decoded frame.
     *                        A {@link TooLongFrameException} is thrown if
     *                        the length of the frame exceeds this value.
     * @param stripDelimiter  whether the decoded frame should strip out the
     *                        delimiter or not
     * @param delimiters  the delimiters
     */
    public DelimiterBasedFrameDecoder(int maxFrameLength, boolean stripDelimiter, ByteBuf... delimiters) {
        this(maxFrameLength, stripDelimiter, true, delimiters);
    }

    /**
     * Creates a new instance.
     *
     * @param maxFrameLength  the maximum length of the decoded frame.
     *                        A {@link TooLongFrameException} is thrown if
     *                        the length of the frame exceeds this value.
     * @param stripDelimiter  whether the decoded frame should strip out the
     *                        delimiter or not
     * @param failFast  If <tt>true</tt>, a {@link TooLongFrameException} is
     *                  thrown as soon as the decoder notices the length of the
     *                  frame will exceed <tt>maxFrameLength</tt> regardless of
     *                  whether the entire frame has been read.
     *                  If <tt>false</tt>, a {@link TooLongFrameException} is
     *                  thrown after the entire frame that exceeds
     *                  <tt>maxFrameLength</tt> has been read.
     * @param delimiters  the delimiters
     */
    public DelimiterBasedFrameDecoder(int maxFrameLength, boolean stripDelimiter, boolean failFast, ByteBuf... delimiters) {
        validateMaxFrameLength(maxFrameLength);
        if (delimiters == null) {
            throw new NullPointerException("delimiters");
        }
        if (delimiters.length == 0) {
            throw new IllegalArgumentException("empty delimiters");
        }

        /**
         * 如果分隔符是 换行符，则直接借用 {@link LineBasedFrameDecoder} 类来实现，而不用重新写一套逻辑了
         * 还要判断当前的解码器是否是 DelimiterBasedFrameDecoder 的子类，如果是子类也不会进入 if 分支
         */
        if (isLineBased(delimiters) && !isSubclass()) {
            lineBasedDecoder = new LineBasedFrameDecoder(maxFrameLength, stripDelimiter, failFast);
            this.delimiters = null;
        } else {
            this.delimiters = new ByteBuf[delimiters.length];
            for (int i = 0; i < delimiters.length; i ++) {
                ByteBuf d = delimiters[i];
                validateDelimiter(d);
                this.delimiters[i] = d.slice(d.readerIndex(), d.readableBytes());
            }
            lineBasedDecoder = null;
        }
        this.maxFrameLength = maxFrameLength;
        this.stripDelimiter = stripDelimiter;
        this.failFast = failFast;
    }

    /** Returns true if the delimiters are "\n" and "\r\n".  */
    private static boolean isLineBased(final ByteBuf[] delimiters) {
        if (delimiters.length != 2) {
            return false;
        }
        ByteBuf a = delimiters[0];
        ByteBuf b = delimiters[1];
        if (a.capacity() < b.capacity()) {
            a = delimiters[1];
            b = delimiters[0];
        }
        return a.capacity() == 2 && b.capacity() == 1
                && a.getByte(0) == '\r' && a.getByte(1) == '\n'
                && b.getByte(0) == '\n';
    }

    /**
     * Return {@code true} if the current instance is a subclass of DelimiterBasedFrameDecoder
     */
    private boolean isSubclass() {
        return getClass() != DelimiterBasedFrameDecoder.class;
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
        if (lineBasedDecoder != null) {
            return lineBasedDecoder.decode(ctx, buffer);
        }
        // Try all delimiters and choose the delimiter which yields the shortest frame.
        /* 初始化一个最大值 */
        int minFrameLength = Integer.MAX_VALUE;

        ByteBuf minDelim = null;
        for (ByteBuf delim: delimiters) {
            /* 从 buffer 中查找 delim 的出现的位置 */
            int frameLength = indexOf(buffer, delim);

            /*
             * 这里的逻辑是获取第一个匹配的位置
             *  buffer：abc123def456
             *  delimiters：23, ef, bc
             * 三个分隔符，而且都匹配上了，由于 bc 最小，所以这里获取的是 bc 这个 buf
             * Note：
             *      从这里看出，其实每次都是匹配所有的分隔符，效率还是蛮低的
             */
            if (frameLength >= 0 && frameLength < minFrameLength) {
                minFrameLength = frameLength;
                minDelim = delim;
            }
        }

        if (minDelim != null) {
            /* 进入这个分支说明已经找到了分隔符 */

            /* 获取分隔符字节数 */
            int minDelimLength = minDelim.capacity();
            ByteBuf frame;

            /*
             * 判断之前有没有因为字节过长没有找到分隔符导致丢弃了数据标识
             * 比如分隔符是 &，但是客户端一直发送全是数字字节，则可能早就超过了最大容量，
             *      则数据被丢失；
             *      丢失状态 discardingTooLongFrame = true；
             *      已经抛出了异常
             */
            if (discardingTooLongFrame) {
                // We've just finished discarding a very large frame.
                // Go back to the initial state.

                /* 重新置为 false，说明已经找到了分隔符 */
                discardingTooLongFrame = false;

                /* 丢弃找到这个分隔符之前的所有数据 */
                buffer.skipBytes(minFrameLength + minDelimLength);

                int tooLongFrameLength = this.tooLongFrameLength;
                this.tooLongFrameLength = 0;
                if (!failFast) {
                    fail(tooLongFrameLength);
                }
                return null;
            }

            /*
             * 这里做判断：所找到的分隔符的下标位置事是否大于了最大容量 maxFrameLength
             * 这种情况会出现在 客户端一次性发送很长数据，直接超过了最大容量 maxFrameLength
             * 这里有点不解：为什么不直接在方法最前面判断，而是到了这里才判断，导致前面做的匹配遍历
             */
            if (minFrameLength > maxFrameLength) {
                // Discard read frame.
                /*
                 * 这里并不是丢弃所有字节，而是丢弃从读索引开始加上当前这个分隔符结束的地方
                 *  buffer：abc123def456
                 *  delimiters：23
                 * 丢弃的部分是 abc123，读索引则是指向了 def456
                 */
                buffer.skipBytes(minFrameLength + minDelimLength);
                fail(minFrameLength);
                return null;
            }

            /* 这里逻辑就是裁剪当前分隔符的前一截数据，只不过判断了要不要去掉分隔符本身而已 */
            if (stripDelimiter) {
                frame = buffer.readRetainedSlice(minFrameLength);
                buffer.skipBytes(minDelimLength);
            } else {
                frame = buffer.readRetainedSlice(minFrameLength + minDelimLength);
            }

            return frame;
        } else {
            /* 进入这个分支说明没有找到了分隔符 */

            /* discardingTooLongFrame 默认值是 false，所以如果没有找到分隔符，会进入这个分支 */
            if (!discardingTooLongFrame) {
                /* 判断当前容量是否超出最大容量 */
                if (buffer.readableBytes() > maxFrameLength) {
                    // Discard the content of the buffer until a delimiter is found.
                    /* 超出了则全部丢弃 */
                    tooLongFrameLength = buffer.readableBytes();
                    buffer.skipBytes(buffer.readableBytes());
                    /*
                     * 标记已有数据被丢弃
                     * 这里很关键，只要 discardingTooLongFrame，后续逻辑就不会进入这分支，而是直接进入下面的 else，当然前提是没有找到分隔符
                     */
                    discardingTooLongFrame = true;
                    if (failFast) {
                        fail(tooLongFrameLength);
                    }
                }
            } else {
                // Still discarding the buffer since a delimiter is not found.
                /*
                 * 进入这个分支说明 一是没有找到分隔符，二是丢弃数据标识为 discardingTooLongFrame true
                 * discardingTooLongFrame 为 true 说明已经抛出异常了，没有重复抛出必要
                 * 其次是：进入这个分支说明一直没有找到分隔符，buffer 没有必要一直缓存数据
                 * 比如分隔符是 &，但是发送的全是数字字节，那么就是你来多少我就丢弃多少
                 */
                tooLongFrameLength += buffer.readableBytes();
                buffer.skipBytes(buffer.readableBytes());
            }
            return null;
        }
    }

    private void fail(long frameLength) {
        if (frameLength > 0) {
            throw new TooLongFrameException(
                            "frame length exceeds " + maxFrameLength +
                            ": " + frameLength + " - discarded");
        } else {
            throw new TooLongFrameException(
                            "frame length exceeds " + maxFrameLength +
                            " - discarding");
        }
    }

    /**
     * Returns the number of bytes between the readerIndex of the haystack and
     * the first needle found in the haystack.  -1 is returned if no needle is
     * found in the haystack.
     *
     * one-to-zero:
     *      haystack 被查找的缓冲区
     *      needle   分隔符
     *  遍历 haystack 中第一次出现 needle 的下标位置，没有匹配上则返回 -1
     *  其实就是采用双重 for 循环算法进行遍历，当中做了几个提前结束的校验，也算是优化吧
     *
     *  Note：如果找到了分隔符，这返回的 int 不是下标索引，而是基于读索引往后移动 int 个位置
     *
     */
    private static int indexOf(ByteBuf haystack, ByteBuf needle) {
        /* 从查找的缓冲区读索引开始，一直遍历到写索引 */
        for (int i = haystack.readerIndex(); i < haystack.writerIndex(); i ++) {
            int haystackIndex = i;
            int needleIndex;
            /*
             * .capacity() 方法是返回这个 buf 能否容纳的最大字节数
             * 这里 netty 做了处理，分隔符 buf 调用这个方法则返回的是分隔符真实的长度
             */
            for (needleIndex = 0; needleIndex < needle.capacity(); needleIndex ++) {
                /*
                 * 假如 src-buf 为：123456789
                 * 分隔符 buf 为：456
                 * 这里的逻辑则是先判断第一个字节是否相等
                 * 也就是获取 456 的 4 和 123456789 逐一匹配
                 *  匹配成功：
                 *      匹配成功，则开始匹配 456 的 5，
                 *  匹配失败：
                 *      说明连第一个字节都失败，那么肯定不包含分隔符
                 */
                if (haystack.getByte(haystackIndex) != needle.getByte(needleIndex)) {
                    break;
                } else {
                    /* haystack 当前的索引位置往前移动一位，继续匹配后续的字节 */
                    haystackIndex ++;

                    /*
                     * 如果 haystack 都已经到末尾了，但是 needle 都还没有结束，说明也不用继续匹配了，直接返回
                     *  haystack：123456
                     *  needle：567
                     * 虽然56 都匹配上，但是 needle 后面还有字节，而 haystack 后面没有字节了
                     */
                    if (haystackIndex == haystack.writerIndex() && needleIndex != needle.capacity() - 1) {
                        return -1;
                    }
                }
            }

            /*
             * 判断 needleIndex 和 needle 分割符的长度是否相等
             * 如果相等：则说明在第二个 for 循环中遍历结束了，并且全部匹配成功，则直接返回下标索引位置
             * 如果不等：则说明从 haystackIndex 为位置开始没有找到匹配，循环下一个
             */
            if (needleIndex == needle.capacity()) {
                // Found the needle from the haystack!
                return i - haystack.readerIndex();
            }
        }
        /* 循环结束还是没有找到则直接返回 -1 */
        return -1;
    }

    private static void validateDelimiter(ByteBuf delimiter) {
        if (delimiter == null) {
            throw new NullPointerException("delimiter");
        }
        if (!delimiter.isReadable()) {
            throw new IllegalArgumentException("empty delimiter");
        }
    }

    private static void validateMaxFrameLength(int maxFrameLength) {
        checkPositive(maxFrameLength, "maxFrameLength");
    }
}
