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
package io.netty.util.concurrent;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Abstract base class for {@link EventExecutorGroup} implementations that handles their tasks with multiple threads at
 * the same time.
 */
public abstract class MultithreadEventExecutorGroup extends AbstractEventExecutorGroup {

    /**
     * one-to-zero:
     * 封装真正的线程执行器 NioEventLoop
     */
    private final EventExecutor[] children;

    /**
     * 此 set 是对上方的执行器数组的一个副本，并且这个副本只读
     */
    private final Set<EventExecutor> readonlyChildren;

    /**
     * one-to-zero:
     * 终止的线程个数
     * 中断执行器的数量，如果 group 被中断则会遍历调用 children 的中断方法，而每个 children 被中断都会进行一个计数
     * 而 terminatedChildren 则是对中断 children 的计数
     */
    private final AtomicInteger terminatedChildren = new AtomicInteger();

    /**
     * one-to-zero:
     * 线程池终止时的异步结果
     */
    private final Promise<?> terminationFuture = new DefaultPromise(GlobalEventExecutor.INSTANCE);

    /**
     * one-to-zero:
     *  线程选择器
     *  会从 EventExecutor 数组里面选择下一个线程执行器
     */
    private final EventExecutorChooserFactory.EventExecutorChooser chooser;

    /**
     * Create a new instance.
     *
     * @param nThreads          the number of threads that will be used by this instance.
     * @param threadFactory     the ThreadFactory to use, or {@code null} if the default should be used.
     * @param args              arguments which will passed to each {@link #newChild(Executor, Object...)} call
     */
    protected MultithreadEventExecutorGroup(int nThreads, ThreadFactory threadFactory, Object... args) {
        this(nThreads, threadFactory == null ? null : new ThreadPerTaskExecutor(threadFactory), args);
    }

    /**
     * Create a new instance.
     *
     * @param nThreads          the number of threads that will be used by this instance.
     * @param executor          the Executor to use, or {@code null} if the default should be used.
     * @param args              arguments which will passed to each {@link #newChild(Executor, Object...)} call
     */
    protected MultithreadEventExecutorGroup(int nThreads, Executor executor, Object... args) {
        this(nThreads, executor, DefaultEventExecutorChooserFactory.INSTANCE, args);
    }

    /**
     * Create a new instance.
     *
     * @param nThreads          the number of threads that will be used by this instance.
     * @param executor          the Executor to use, or {@code null} if the default should be used.
     * @param chooserFactory    the {@link EventExecutorChooserFactory} to use.
     * @param args              arguments which will passed to each {@link #newChild(Executor, Object...)} call
     */
    protected MultithreadEventExecutorGroup(int nThreads, Executor executor,
                                            EventExecutorChooserFactory chooserFactory, Object... args) {
        if (nThreads <= 0) {
            throw new IllegalArgumentException(String.format("nThreads: %d (expected: > 0)", nThreads));
        }

        if (executor == null) {
            /**
             * one-to-zero:
             *  创建一个线程执行器，里面封装了默认的线程工厂
             *  获取默认线程工厂:
             *      调用 MultithreadEventLoopGroup 类的 newDefaultThreadFactory 方法创建 DefaultThreadFactory
             *
             * 这个 executor 就是用于创建 {@link io.netty.channel.nio.NioEventLoop#thread}
             */
            executor = new ThreadPerTaskExecutor(newDefaultThreadFactory());
        }

        /* 创建一个数组对象 */
        children = new EventExecutor[nThreads];

        for (int i = 0; i < nThreads; i ++) {
            boolean success = false;
            try {
                /**
                 * one-to-zero:
                 * 调用 NioEventLoopGroup 类的 newChild 方法创建 {@link io.netty.channel.nio.NioEventLoop} 对象
                 * 进入 {@link io.netty.channel.nio.NioEventLoopGroup#newChild(Executor, Object...)}
                 */
                children[i] = newChild(executor, args);

                success = true;
            } catch (Exception e) {
                // TODO: Think about if this is a good exception type
                throw new IllegalStateException("failed to create a child event loop", e);
            } finally {
                /**
                 * one-to-zero:
                 * 如果创建 NioEventLoop 对象失败，则逐个优雅关闭
                 */
                if (!success) {
                    for (int j = 0; j < i; j ++) {
                        /**
                         * one-to-zero:
                         * shutdownGracefully()只是通知线程池该关闭，但什么时候关闭由线程池决定
                         */
                        children[j].shutdownGracefully();
                    }
                    /* 确保已经实例化的线程终止 */
                    for (int j = 0; j < i; j ++) {
                        EventExecutor e = children[j];
                        try {
                            /* 使用e.isTerminated()来判断线程池是否真正关闭 */
                            while (!e.isTerminated()) {
                                e.awaitTermination(Integer.MAX_VALUE, TimeUnit.SECONDS);
                            }
                        } catch (InterruptedException interrupted) {
                            // Let the caller handle the interruption.
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        }

        /**
         * one-to-zero:
         * 创建线程选择器，简单的轮训算法
         */
        chooser = chooserFactory.newChooser(children);

        /* 创建一个 future 的监听器用于监听终止结果 */
        final FutureListener<Object> terminationListener = new FutureListener<Object>() {
            @Override
            public void operationComplete(Future<Object> future) throws Exception {
                /* 当此执行组中的执行器被关闭的时候回调用此方法进入这里，这里进行终止数加一然后比较是否已经达到了执行器的总数 */
                if (terminatedChildren.incrementAndGet() == children.length) {
                    /* 如果没有则跳过，如果有则设置当前执行器的终止 future 为 success 为 null */
                    terminationFuture.setSuccess(null);
                }
            }
        };

        /* 遍历创建好的执行器动态添加终止 future 的结果监听器，当监听器触发则会进入上方的内部类实现 */
        for (EventExecutor e: children) {
            e.terminationFuture().addListener(terminationListener);
        }

        /* 创建一个 children 的镜像 set */
        Set<EventExecutor> childrenSet = new LinkedHashSet<EventExecutor>(children.length);
        /* 拷贝这个 set */
        Collections.addAll(childrenSet, children);
        /* 并且设置此 set 内的所有数据不允许修改然后返回设置给 readonlyChildren */
        readonlyChildren = Collections.unmodifiableSet(childrenSet);
    }

    /**
     * 获取默认的线程工厂并且传入当前类名
     */
    protected ThreadFactory newDefaultThreadFactory() {
        return new DefaultThreadFactory(getClass());
    }

    @Override
    public EventExecutor next() {
        /**
         * one-to-zero:
         * 每一个连接上来，都会调用这个方法选择一个线程执行器
         * next 则是使用了选择器的next方法
         */
        return chooser.next();
    }

    @Override
    public Iterator<EventExecutor> iterator() {
        return readonlyChildren.iterator();
    }

    /**
     * Return the number of {@link EventExecutor} this implementation uses. This number is the maps
     * 1:1 to the threads it use.
     */
    public final int executorCount() {
        return children.length;
    }

    /**
     * Create a new EventExecutor which will later then accessible via the {@link #next()}  method. This method will be
     * called for each thread that will serve this {@link MultithreadEventExecutorGroup}.
     *
     * one-to-zero:
     * 这个方法是为group创建线程，它们是真实的 I/O 线程
     * 真正实现是 io.netty.channel.nio.NioEventLoopGroup
     */
    protected abstract EventExecutor newChild(Executor executor, Object... args) throws Exception;

    /**
     * 之前说过调用线程组的关闭其实就是遍历执行器集合的关闭方法
     * 因为之前加了监听器去处理返回结果所以此处返回的 future 用于监听是否执行结束了
     */
    @Override
    public Future<?> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit) {
        for (EventExecutor l: children) {
            l.shutdownGracefully(quietPeriod, timeout, unit);
        }
        return terminationFuture();
    }

    @Override
    public Future<?> terminationFuture() {
        return terminationFuture;
    }

    @Override
    @Deprecated
    public void shutdown() {
        for (EventExecutor l: children) {
            l.shutdown();
        }
    }

    @Override
    public boolean isShuttingDown() {
        for (EventExecutor l: children) {
            if (!l.isShuttingDown()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isShutdown() {
        for (EventExecutor l: children) {
            if (!l.isShutdown()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isTerminated() {
        for (EventExecutor l: children) {
            if (!l.isTerminated()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 等待时间范围是否执行终止完成
     */
    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        loop: for (EventExecutor l: children) {
            for (;;) {
                long timeLeft = deadline - System.nanoTime();
                if (timeLeft <= 0) {
                    break loop;
                }
                if (l.awaitTermination(timeLeft, TimeUnit.NANOSECONDS)) {
                    break;
                }
            }
        }
        return isTerminated();
    }
}
