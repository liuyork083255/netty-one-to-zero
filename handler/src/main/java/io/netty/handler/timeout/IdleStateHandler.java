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
package io.netty.handler.timeout;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.Channel.Unsafe;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;

import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Triggers an {@link IdleStateEvent} when a {@link Channel} has not performed
 * read, write, or both operation for a while.
 *
 * <h3>Supported idle states</h3>
 * <table border="1">
 * <tr>
 * <th>Property</th><th>Meaning</th>
 * </tr>
 * <tr>
 * <td>{@code readerIdleTime}</td>
 * <td>an {@link IdleStateEvent} whose state is {@link IdleState#READER_IDLE}
 *     will be triggered when no read was performed for the specified period of
 *     time.  Specify {@code 0} to disable.</td>
 * </tr>
 * <tr>
 * <td>{@code writerIdleTime}</td>
 * <td>an {@link IdleStateEvent} whose state is {@link IdleState#WRITER_IDLE}
 *     will be triggered when no write was performed for the specified period of
 *     time.  Specify {@code 0} to disable.</td>
 * </tr>
 * <tr>
 * <td>{@code allIdleTime}</td>
 * <td>an {@link IdleStateEvent} whose state is {@link IdleState#ALL_IDLE}
 *     will be triggered when neither read nor write was performed for the
 *     specified period of time.  Specify {@code 0} to disable.</td>
 * </tr>
 * </table>
 *
 * <pre>
 * // An example that sends a ping message when there is no outbound traffic
 * // for 30 seconds.  The connection is closed when there is no inbound traffic
 * // for 60 seconds.
 *
 * public class MyChannelInitializer extends {@link ChannelInitializer}&lt;{@link Channel}&gt; {
 *     {@code @Override}
 *     public void initChannel({@link Channel} channel) {
 *         channel.pipeline().addLast("idleStateHandler", new {@link IdleStateHandler}(60, 30, 0));
 *         channel.pipeline().addLast("myHandler", new MyHandler());
 *     }
 * }
 *
 * // Handler should handle the {@link IdleStateEvent} triggered by {@link IdleStateHandler}.
 * public class MyHandler extends {@link ChannelDuplexHandler} {
 *     {@code @Override}
 *     public void userEventTriggered({@link ChannelHandlerContext} ctx, {@link Object} evt) throws {@link Exception} {
 *         if (evt instanceof {@link IdleStateEvent}) {
 *             {@link IdleStateEvent} e = ({@link IdleStateEvent}) evt;
 *             if (e.state() == {@link IdleState}.READER_IDLE) {
 *                 ctx.close();
 *             } else if (e.state() == {@link IdleState}.WRITER_IDLE) {
 *                 ctx.writeAndFlush(new PingMessage());
 *             }
 *         }
 *     }
 * }
 *
 * {@link ServerBootstrap} bootstrap = ...;
 * ...
 * bootstrap.childHandler(new MyChannelInitializer());
 * ...
 * </pre>
 *
 * @see ReadTimeoutHandler
 * @see WriteTimeoutHandler
 *
 * one-to-zero:
 *  注意： netty 的心跳机制不是使用 {@link io.netty.util.HashedWheelTimer} 实现的
 *      而是将任务全部添加到任务队列中 {@link io.netty.util.concurrent.AbstractScheduledEventExecutor#scheduledTaskQueue}
 *
 *  netty 专门设置了两个读写 handler，效果是一样的，只不过更加明细化而已
 *
 */
public class IdleStateHandler extends ChannelDuplexHandler {

    /** 将一毫秒转为纳秒 1ms = 1000000 ns */
    private static final long MIN_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

    // Not create a new ChannelFutureListener per write operation to reduce GC pressure.
    private final ChannelFutureListener writeListener = new ChannelFutureListener() {
        /** 在数据真正被 flush 到 tcp 缓冲区后，则会回调这个方法 */
        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
            /* 记录完成写的最后系统时间 */
            lastWriteTime = ticksInNanos();
            /* 设置写状态 */
            firstWriterIdleEvent = firstAllIdleEvent = true;
        }
    };

    /** 是否考虑出站时较慢的情况。默认值是false（不考虑）。 */
    private final boolean observeOutput;
    /** 读事件空闲时间，0 则禁用事件 */
    private final long readerIdleTimeNanos;
    /** 写事件空闲时间，0 则禁用事件 */
    private final long writerIdleTimeNanos;
    /** 读或写空闲时间，0 则禁用事件 */
    private final long allIdleTimeNanos;

    /** 读空闲定时任务的返回句柄 future */
    private ScheduledFuture<?> readerIdleTimeout;
    /** 最后一次读时间 */
    private long lastReadTime;
    /** 是否第一次读超时 */
    private boolean firstReaderIdleEvent = true;

    /** 写空闲定时任务的返回句柄 future */
    private ScheduledFuture<?> writerIdleTimeout;
    /** 最后一次写超时 */
    private long lastWriteTime;
    /** 是否第一次写超时 */
    private boolean firstWriterIdleEvent = true;

    /** 读或写超时定时任务的返回句柄 future */
    private ScheduledFuture<?> allIdleTimeout;
    /** 是否读或写超时 */
    private boolean firstAllIdleEvent = true;

    /** 处理器状态: 0-无状态, 1-初始化, 2-销毁 */
    private byte state; // 0 - none, 1 - initialized, 2 - destroyed

    /**
     * one-to-zero:
     *  读状态标志
     *  如果正在读取数据 {@link #channelRead(ChannelHandlerContext, Object)} 则为 true
     *  如果读取数据完成 {@link #channelReadComplete(ChannelHandlerContext)} 则为 false
     */
    private boolean reading;

    private long lastChangeCheckTimeStamp;
    private int lastMessageHashCode;
    private long lastPendingWriteBytes;
    private long lastFlushProgress;

    /**
     * Creates a new instance firing {@link IdleStateEvent}s.
     *
     * @param readerIdleTimeSeconds
     *        an {@link IdleStateEvent} whose state is {@link IdleState#READER_IDLE}
     *        will be triggered when no read was performed for the specified
     *        period of time.  Specify {@code 0} to disable.
     * @param writerIdleTimeSeconds
     *        an {@link IdleStateEvent} whose state is {@link IdleState#WRITER_IDLE}
     *        will be triggered when no write was performed for the specified
     *        period of time.  Specify {@code 0} to disable.
     * @param allIdleTimeSeconds
     *        an {@link IdleStateEvent} whose state is {@link IdleState#ALL_IDLE}
     *        will be triggered when neither read nor write was performed for
     *        the specified period of time.  Specify {@code 0} to disable.
     */
    public IdleStateHandler(
            int readerIdleTimeSeconds,
            int writerIdleTimeSeconds,
            int allIdleTimeSeconds) {

        this(readerIdleTimeSeconds, writerIdleTimeSeconds, allIdleTimeSeconds,
             TimeUnit.SECONDS);
    }

    /**
     * @see #IdleStateHandler(boolean, long, long, long, TimeUnit)
     */
    public IdleStateHandler(
            long readerIdleTime, long writerIdleTime, long allIdleTime,
            TimeUnit unit) {
        this(false, readerIdleTime, writerIdleTime, allIdleTime, unit);
    }

    /**
     * Creates a new instance firing {@link IdleStateEvent}s.
     *
     * @param observeOutput
     *        whether or not the consumption of {@code bytes} should be taken into
     *        consideration when assessing write idleness. The default is {@code false}.
     * @param readerIdleTime
     *        an {@link IdleStateEvent} whose state is {@link IdleState#READER_IDLE}
     *        will be triggered when no read was performed for the specified
     *        period of time.  Specify {@code 0} to disable.
     * @param writerIdleTime
     *        an {@link IdleStateEvent} whose state is {@link IdleState#WRITER_IDLE}
     *        will be triggered when no write was performed for the specified
     *        period of time.  Specify {@code 0} to disable.
     * @param allIdleTime
     *        an {@link IdleStateEvent} whose state is {@link IdleState#ALL_IDLE}
     *        will be triggered when neither read nor write was performed for
     *        the specified period of time.  Specify {@code 0} to disable.
     * @param unit
     *        the {@link TimeUnit} of {@code readerIdleTime},
     *        {@code writeIdleTime}, and {@code allIdleTime}
     */
    public IdleStateHandler(boolean observeOutput, long readerIdleTime, long writerIdleTime, long allIdleTime, TimeUnit unit) {
        if (unit == null) {
            throw new NullPointerException("unit");
        }

        this.observeOutput = observeOutput;

        /* 设置时间单位，可以看出，默认最小的单位就是 1 毫秒，不能比一毫秒还小的心跳时间了 */
        if (readerIdleTime <= 0) {
            readerIdleTimeNanos = 0;
        } else {
            readerIdleTimeNanos = Math.max(unit.toNanos(readerIdleTime), MIN_TIMEOUT_NANOS);
        }
        if (writerIdleTime <= 0) {
            writerIdleTimeNanos = 0;
        } else {
            writerIdleTimeNanos = Math.max(unit.toNanos(writerIdleTime), MIN_TIMEOUT_NANOS);
        }
        if (allIdleTime <= 0) {
            allIdleTimeNanos = 0;
        } else {
            allIdleTimeNanos = Math.max(unit.toNanos(allIdleTime), MIN_TIMEOUT_NANOS);
        }
    }

    /**
     * Return the readerIdleTime that was given when instance this class in milliseconds.
     *
     */
    public long getReaderIdleTimeInMillis() {
        return TimeUnit.NANOSECONDS.toMillis(readerIdleTimeNanos);
    }

    /**
     * Return the writerIdleTime that was given when instance this class in milliseconds.
     *
     */
    public long getWriterIdleTimeInMillis() {
        return TimeUnit.NANOSECONDS.toMillis(writerIdleTimeNanos);
    }

    /**
     * Return the allIdleTime that was given when instance this class in milliseconds.
     *
     */
    public long getAllIdleTimeInMillis() {
        return TimeUnit.NANOSECONDS.toMillis(allIdleTimeNanos);
    }

    /**
     * one-to-zero:
     *  当处理器被添加时,如果 channel 是注册并且是 active 状态，则初始化
     *  否则什么都不做
     */
    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        if (ctx.channel().isActive() && ctx.channel().isRegistered()) {
            // channelActive() event has been fired already, which means this.channelActive() will
            // not be invoked. We have to initialize here instead.
            initialize(ctx);
        } else {
            // channelActive() event has not been fired yet.  this.channelActive() will be invoked
            // and initialization will occur there.
        }
    }

    /**
     * one-to-zero:
     *  handler 被移除时调用 destroy 销毁
     */
    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        destroy();
    }

    /**
     * one-to-zero:
     *  当 channel 注册事件发生，如果是 channel 是激活状态，则初始化
     */
    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        // Initialize early if channel is active already.
        if (ctx.channel().isActive()) {
            initialize(ctx);
        }
        super.channelRegistered(ctx);
    }

    /**
     * one-to-zero:
     *  只有在触发 channelActive() 事件之前添加此 handler，才会调用此方法。如果用户在 channelActive()事件之后添加此 handler，
     *  beforeAdd() 将调用 initialize()。
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // This method will be invoked only if this handler was added
        // before channelActive() event is fired.  If a user adds this handler
        // after the channelActive() event, initialize() will be called by beforeAdd().
        initialize(ctx);
        super.channelActive(ctx);
    }

    /**
     * one-to-zero:
     *  通道不活跃时，调用 destroy 销毁
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        destroy();
        super.channelInactive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        /* 如果开启了读 或者 读写监听 */
        if (readerIdleTimeNanos > 0 || allIdleTimeNanos > 0) {
            /* 标记相关状态 */
            reading = true;
            firstReaderIdleEvent = firstAllIdleEvent = true;
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        /* 如果监听了读或者读写状态，且正在读标志是 true */
        if ((readerIdleTimeNanos > 0 || allIdleTimeNanos > 0) && reading) {
            /* 读取完成后，记录最后读取的时间 */
            lastReadTime = ticksInNanos();
            /* 读取完成后，标记正在读状态为 false */
            reading = false;
        }
        ctx.fireChannelReadComplete();
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        // Allow writing with void promise if handler is only configured for read timeout events.
        /*
         * 如果监听了写事件或者读写事件，则为这个写事件添加一个 void-promise，并且设置一个 监听事件
         * promise.unvoid() 其实就是 promise 自身
         */
        if (writerIdleTimeNanos > 0 || allIdleTimeNanos > 0) {
            ctx.write(msg, promise.unvoid()).addListener(writeListener);
        } else {
            ctx.write(msg, promise);
        }
    }

    private void initialize(ChannelHandlerContext ctx) {
        // Avoid the case where destroy() is called before scheduling timeouts.
        // See: https://github.com/netty/netty/issues/143
        /* 防止重复初始化 */
        switch (state) {
        case 1:
        case 2:
            return;
        }

        state = 1;

        /* 处理如果考虑出站数据较慢的情况，则处理响应的逻辑，默认是关闭的 */
        initOutputChanged(ctx);
        /* 记录最后的读和写时间为系统时间当前时间 */
        lastReadTime = lastWriteTime = ticksInNanos();

        if (readerIdleTimeNanos > 0) {
            /* 添加定时读任务，并且返回 future */
            readerIdleTimeout = schedule(ctx, new ReaderIdleTimeoutTask(ctx), readerIdleTimeNanos, TimeUnit.NANOSECONDS);
        }
        if (writerIdleTimeNanos > 0) {
            /* 添加定时写任务，并且返回 future */
            writerIdleTimeout = schedule(ctx, new WriterIdleTimeoutTask(ctx), writerIdleTimeNanos, TimeUnit.NANOSECONDS);
        }
        if (allIdleTimeNanos > 0) {
            /* 添加定时读写任务，并且返回 future */
            allIdleTimeout = schedule(ctx, new AllIdleTimeoutTask(ctx), allIdleTimeNanos, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * This method is visible for testing!
     */
    long ticksInNanos() {
        return System.nanoTime();
    }

    /**
     * This method is visible for testing!
     */
    ScheduledFuture<?> schedule(ChannelHandlerContext ctx, Runnable task, long delay, TimeUnit unit) {
        /** {@link io.netty.util.concurrent.AbstractScheduledEventExecutor#schedule(Callable, long, TimeUnit)}  }*/
        return ctx.executor().schedule(task, delay, unit);
    }

    private void destroy() {
        state = 2;

        if (readerIdleTimeout != null) {
            readerIdleTimeout.cancel(false);
            readerIdleTimeout = null;
        }
        if (writerIdleTimeout != null) {
            writerIdleTimeout.cancel(false);
            writerIdleTimeout = null;
        }
        if (allIdleTimeout != null) {
            allIdleTimeout.cancel(false);
            allIdleTimeout = null;
        }
    }

    /**
     * Is called when an {@link IdleStateEvent} should be fired. This implementation calls
     * {@link ChannelHandlerContext#fireUserEventTriggered(Object)}.
     */
    protected void channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt) throws Exception {
        ctx.fireUserEventTriggered(evt);
    }

    /**
     * Returns a {@link IdleStateEvent}.
     */
    protected IdleStateEvent newIdleStateEvent(IdleState state, boolean first) {
        switch (state) {
            case ALL_IDLE:
                return first ? IdleStateEvent.FIRST_ALL_IDLE_STATE_EVENT : IdleStateEvent.ALL_IDLE_STATE_EVENT;
            case READER_IDLE:
                return first ? IdleStateEvent.FIRST_READER_IDLE_STATE_EVENT : IdleStateEvent.READER_IDLE_STATE_EVENT;
            case WRITER_IDLE:
                return first ? IdleStateEvent.FIRST_WRITER_IDLE_STATE_EVENT : IdleStateEvent.WRITER_IDLE_STATE_EVENT;
            default:
                throw new IllegalArgumentException("Unhandled: state=" + state + ", first=" + first);
        }
    }

    /**
     * @see #hasOutputChanged(ChannelHandlerContext, boolean)
     * one-to-zero:
     *  这里的逻辑应该是处理 考虑出站时较慢的情况
     */
    private void initOutputChanged(ChannelHandlerContext ctx) {
        if (observeOutput) {
            Channel channel = ctx.channel();
            Unsafe unsafe = channel.unsafe();
            ChannelOutboundBuffer buf = unsafe.outboundBuffer();

            if (buf != null) {
                lastMessageHashCode = System.identityHashCode(buf.current());
                lastPendingWriteBytes = buf.totalPendingWriteBytes();
                lastFlushProgress = buf.currentProgress();
            }
        }
    }

    /**
     * Returns {@code true} if and only if the {@link IdleStateHandler} was constructed
     * with {@link #observeOutput} enabled and there has been an observed change in the
     * {@link ChannelOutboundBuffer} between two consecutive calls of this method.
     *
     * https://github.com/netty/netty/issues/6150
     */
    private boolean hasOutputChanged(ChannelHandlerContext ctx, boolean first) {
        if (observeOutput) {

            // We can take this shortcut if the ChannelPromises that got passed into write()
            // appear to complete. It indicates "change" on message level and we simply assume
            // that there's change happening on byte level. If the user doesn't observe channel
            // writability events then they'll eventually OOME and there's clearly a different
            // problem and idleness is least of their concerns.
            if (lastChangeCheckTimeStamp != lastWriteTime) {
                lastChangeCheckTimeStamp = lastWriteTime;

                // But this applies only if it's the non-first call.
                if (!first) {
                    return true;
                }
            }

            Channel channel = ctx.channel();
            Unsafe unsafe = channel.unsafe();
            ChannelOutboundBuffer buf = unsafe.outboundBuffer();

            if (buf != null) {
                int messageHashCode = System.identityHashCode(buf.current());
                long pendingWriteBytes = buf.totalPendingWriteBytes();

                if (messageHashCode != lastMessageHashCode || pendingWriteBytes != lastPendingWriteBytes) {
                    lastMessageHashCode = messageHashCode;
                    lastPendingWriteBytes = pendingWriteBytes;

                    if (!first) {
                        return true;
                    }
                }

                long flushProgress = buf.currentProgress();
                if (flushProgress != lastFlushProgress) {
                    lastFlushProgress = flushProgress;

                    if (!first) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * one-to-zero:
     *  对 Runnable 进行封装，主要是判断了一下当前 channel 的状态是否时候 open
     *  是 open 则运行 run
     *  否则直接返回
     */
    private abstract static class AbstractIdleTask implements Runnable {

        private final ChannelHandlerContext ctx;

        AbstractIdleTask(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void run() {
            if (!ctx.channel().isOpen()) {
                return;
            }

            run(ctx);
        }

        protected abstract void run(ChannelHandlerContext ctx);
    }

    /**
     * one-to-zero:
     *  ReaderIdleTimeoutTask 就是一个定时任务，在 netty 读取完成 IO 操作后，就会处理 task 队列，其中就包括这个任务
     *  1 获取过期时间，然后判断是否到期
     *  2 如果到期：创建对应事件 event(读、写、读写)，然后传递给下一个 handler 的 fireUserEventTriggered 的方法
     *  3 如果未到：创建定时任务，更新过期时间然后放入定时任务队列 queue 中
     */
    private final class ReaderIdleTimeoutTask extends AbstractIdleTask {

        ReaderIdleTimeoutTask(ChannelHandlerContext ctx) {
            super(ctx);
        }

        @Override
        protected void run(ChannelHandlerContext ctx) {
            /* 获取读超时 间隔时间 纳秒 eg: 读心跳 10s 则 nextDelay = 10 * 毫秒 * 纳秒 = 10000000000 */
            long nextDelay = readerIdleTimeNanos;
            /*
             * 如果没有正在读取数据，即 reading = false
             * 说明 netty 考虑到了如果 read 操作很费时间，还正在读取，那么肯定没有超时，即 nextDelay 肯定 > 0
             */
            if (!reading) {
                /*
                 * ticksInNanos() - lastReadTime  = 当前时间减去最后一次读事件的间隔 gap
                 * 用心跳时间减去这个 gap ，如果 <= 0，说明至少心跳时间内间隔没有发生心跳
                 *  如果 > 0 ，说明还没有到心跳时间
                 */
                nextDelay -= ticksInNanos() - lastReadTime;
            }

            if (nextDelay <= 0) {
                // Reader is idle - set a new timeout and notify the callback.
                /* 因为每次添加一个任务只会执行一次，所以这里再次添加该任务 */
                readerIdleTimeout = schedule(ctx, this, readerIdleTimeNanos, TimeUnit.NANOSECONDS);

                boolean first = firstReaderIdleEvent;
                firstReaderIdleEvent = false;

                try {
                    /* 创建对应事件 event，然后将这个事件传递到 pipeline 中，供后续用户的 handler 可以处理 fireUserEventTriggered 方法 */
                    IdleStateEvent event = newIdleStateEvent(IdleState.READER_IDLE, first);
                    channelIdle(ctx, event);
                } catch (Throwable t) {
                    ctx.fireExceptionCaught(t);
                }
            } else {
                // Read occurred before the timeout - set a new timeout with shorter delay.
                /* 如果没有超时，则创建新的定时任务放在 queue 中，这里的过期时间则是前面计算出来的最新时间 */
                readerIdleTimeout = schedule(ctx, this, nextDelay, TimeUnit.NANOSECONDS);
            }
        }
    }

    /**
     * one-to-zero:
     *  分析逻辑详见 {@link ReaderIdleTimeoutTask}
     */
    private final class WriterIdleTimeoutTask extends AbstractIdleTask {

        WriterIdleTimeoutTask(ChannelHandlerContext ctx) {
            super(ctx);
        }

        @Override
        protected void run(ChannelHandlerContext ctx) {

            long lastWriteTime = IdleStateHandler.this.lastWriteTime;
            long nextDelay = writerIdleTimeNanos - (ticksInNanos() - lastWriteTime);
            if (nextDelay <= 0) {
                // Writer is idle - set a new timeout and notify the callback.
                writerIdleTimeout = schedule(ctx, this, writerIdleTimeNanos, TimeUnit.NANOSECONDS);

                boolean first = firstWriterIdleEvent;
                firstWriterIdleEvent = false;

                try {
                    if (hasOutputChanged(ctx, first)) {
                        return;
                    }

                    IdleStateEvent event = newIdleStateEvent(IdleState.WRITER_IDLE, first);
                    channelIdle(ctx, event);
                } catch (Throwable t) {
                    ctx.fireExceptionCaught(t);
                }
            } else {
                // Write occurred before the timeout - set a new timeout with shorter delay.
                writerIdleTimeout = schedule(ctx, this, nextDelay, TimeUnit.NANOSECONDS);
            }
        }
    }

    /**
     * one-to-zero:
     *  分析逻辑详见 {@link ReaderIdleTimeoutTask}
     */
    private final class AllIdleTimeoutTask extends AbstractIdleTask {

        AllIdleTimeoutTask(ChannelHandlerContext ctx) {
            super(ctx);
        }

        @Override
        protected void run(ChannelHandlerContext ctx) {

            long nextDelay = allIdleTimeNanos;
            if (!reading) {
                nextDelay -= ticksInNanos() - Math.max(lastReadTime, lastWriteTime);
            }
            if (nextDelay <= 0) {
                // Both reader and writer are idle - set a new timeout and
                // notify the callback.
                allIdleTimeout = schedule(ctx, this, allIdleTimeNanos, TimeUnit.NANOSECONDS);

                boolean first = firstAllIdleEvent;
                firstAllIdleEvent = false;

                try {
                    if (hasOutputChanged(ctx, first)) {
                        return;
                    }

                    IdleStateEvent event = newIdleStateEvent(IdleState.ALL_IDLE, first);
                    channelIdle(ctx, event);
                } catch (Throwable t) {
                    ctx.fireExceptionCaught(t);
                }
            } else {
                // Either read or write occurred before the timeout - set a new
                // timeout with shorter delay.
                allIdleTimeout = schedule(ctx, this, nextDelay, TimeUnit.NANOSECONDS);
            }
        }
    }
}
