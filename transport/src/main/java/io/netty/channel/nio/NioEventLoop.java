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
package io.netty.channel.nio;

import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopException;
import io.netty.channel.SelectStrategy;
import io.netty.channel.SingleThreadEventLoop;
import io.netty.util.IntSupplier;
import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.ReflectionUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.Selector;
import java.nio.channels.SelectionKey;

import java.nio.channels.spi.SelectorProvider;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link SingleThreadEventLoop} implementation which register the {@link Channel}'s to a
 * {@link Selector} and so does the multi-plexing of these in the event loop.
 *
 * one-to-zero:
 * 继承路线：
 *  NioEventLoop
 *      --> {@link SingleThreadEventLoop}
 *      --> {@link io.netty.util.concurrent.SingleThreadEventExecutor}
 *      --> {@link io.netty.util.concurrent.AbstractScheduledEventExecutor}
 *      --> {@link io.netty.util.concurrent.AbstractEventExecutor}
 *      --> {@link java.util.concurrent.AbstractExecutorService}
 *
 * NioEventLoop 需要处理网络IO读写事件，因此必然聚合一个多路复用器对象 {@link this#selector}
 * NioEventLoop 就是核心的IO线程，因此必然聚合了一个线程，就在父类 {@link io.netty.util.concurrent.SingleThreadEventExecutor#thread} 中
 *
 * NioEventLoop 提交任务：
 *  netty当发现任务队列里面没有任务要处理，就会执行selector的阻塞方法select（timeout），
 *  如果这时候突然有外部线程调用channel去发送一个消息，那么 netty 会把改消息包装成一个任务放入队列，
 *  放入队列的时候 netty 会检测当前selector是否处于阻塞，如果是则调用 wakeup 方法让 selector 从阻塞中恢复过来进行 loop 的 run 方法执行。
 *
 * netty 要执行的任务队列其实三个：
 *      1 后置队列（tailTasks）{@link SingleThreadEventLoop#tailTasks}
 *      2 常规任务队列（taskQueue）{@link io.netty.util.concurrent.SingleThreadEventExecutor#taskQueue}
 *      3 定时任务队列（scheduledTaskQueue）{@link io.netty.util.concurrent.AbstractScheduledEventExecutor#scheduledTaskQueue}
 *
 *
 */
public final class NioEventLoop extends SingleThreadEventLoop {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioEventLoop.class);

    private static final int CLEANUP_INTERVAL = 256; // XXX Hard-coded value, but won't need customization.

    /**
     * one-to-zero:
     * 默认对 selector 的 selectedKeys 进行了优化，可以通过 io.netty.noKeySetOptimization 开关决定是否开启优化功能
     * 默认是优化打开
     */
    private static final boolean DISABLE_KEY_SET_OPTIMIZATION =
            SystemPropertyUtil.getBoolean("io.netty.noKeySetOptimization", false);

    private static final int MIN_PREMATURE_SELECTOR_RETURNS = 3;
    private static final int SELECTOR_AUTO_REBUILD_THRESHOLD;

    private final IntSupplier selectNowSupplier = new IntSupplier() {
        @Override
        public int get() throws Exception {
            return selectNow();
        }
    };

    // Workaround for JDK NIO bug.
    //
    // See:
    // - http://bugs.sun.com/view_bug.do?bug_id=6427854
    // - https://github.com/netty/netty/issues/203

    /**
     * one-to-zero:
     * nio 中存在空轮训bug，netty做了修复处理
     * 静态代码块中主要是做一些配置工作，比如配置空轮训次数  bug级别等等
     *
     */
    static {
        final String key = "sun.nio.ch.bugLevel";
        final String bugLevel = SystemPropertyUtil.get(key);
        if (bugLevel == null) {
            try {
                AccessController.doPrivileged(new PrivilegedAction<Void>() {
                    @Override
                    public Void run() {
                        System.setProperty(key, "");
                        return null;
                    }
                });
            } catch (final SecurityException e) {
                logger.debug("Unable to get/set System Property: " + key, e);
            }
        }

        /**
         * one-to-zero:
         * 设置空轮训次数
         * 如果设置的空轮训小于3 则直接等于0
         */
        int selectorAutoRebuildThreshold = SystemPropertyUtil.getInt("io.netty.selectorAutoRebuildThreshold", 512);
        if (selectorAutoRebuildThreshold < MIN_PREMATURE_SELECTOR_RETURNS) {
            /**
             * one-to-zero:
             * 如果等于0，表明发生了死循环，也不会重构 selector
             * 判断逻辑见代码 929 行
             */
            selectorAutoRebuildThreshold = 0;  //one-to-zero: 不需要重构选择器
        }

        SELECTOR_AUTO_REBUILD_THRESHOLD = selectorAutoRebuildThreshold;

        if (logger.isDebugEnabled()) {
            logger.debug("-Dio.netty.noKeySetOptimization: {}", DISABLE_KEY_SET_OPTIMIZATION);
            logger.debug("-Dio.netty.selectorAutoRebuildThreshold: {}", SELECTOR_AUTO_REBUILD_THRESHOLD);
        }
    }

    /**
     * The NIO {@link Selector}.
     */
    /**
     * one-to-zero:
     * 就绪选择key集合优化后的选择器
     */
    private Selector selector;
    /**
     * one-to-zero:
     * 没有包装过的选择器，即选择器提供者打开的原始选择器
     */
    private Selector unwrappedSelector;
    /**
     * one-to-zero:
     * 选择key就绪集合
     */
    private SelectedSelectionKeySet selectedKeys;

    private final SelectorProvider provider;//选择器提供者

    /**
     * Boolean that controls determines if a blocked Selector.select should
     * break out of its selection process. In our case we use a timeout for
     * the select method and the select method will block for that time unless
     * waken up.
     * one-to-zero:
     *  当选择器的选择操作阻塞时，wakenUp属性决定是否应该break选择操作过程。在我们的实现中，
     *  我们给选择操作一个超时时间， 除非选择操作被wakeup，否则选择操作达到超时时间，则break选择操作。
     */
    private final AtomicBoolean wakenUp = new AtomicBoolean();

    private final SelectStrategy selectStrategy;//选择策略

    /**
     * one-to-zero:
     *  用来限制执行执行任务 task 时间占比
     *  如果
     *      100， 则会每次都会先执行IO事件，然后在 finally 里面执行任务task，并且是全部执行
     *      否则 会每次都会先执行IO事件，然后在 finally 里面执行任务task，并且设置一个执行时间段
     *
     *  这不是绝对的：
     *      只能是大致限制比，如我们限制3秒钟，那么我们执行到第二个任务才2秒，那netty会继续执行，
     *      但是第二个任务耗时4秒，则其一共执行的时间就会超过之前的3秒。
     *
     *  如果IO操作多于定时任务和task，可以将该值调大，该值越大，task分的时间就越少
     */
    private volatile int ioRatio = 50;//one-to-zero: Nio处理Io事件的时间占比，以便可以处理器其他非IO事件
    private int cancelledKeys;//one-to-zero: 取消选择key计数器
    private boolean needsToSelectAgain;//one-to-zero: 是否需要重新选择

    NioEventLoop(NioEventLoopGroup parent, Executor executor, SelectorProvider selectorProvider,
                 SelectStrategy strategy, RejectedExecutionHandler rejectedExecutionHandler) {
        super(parent, executor, false, DEFAULT_MAX_PENDING_TASKS, rejectedExecutionHandler);
        if (selectorProvider == null) {
            throw new NullPointerException("selectorProvider");
        }
        if (strategy == null) {
            throw new NullPointerException("selectStrategy");
        }
        provider = selectorProvider;
        final SelectorTuple selectorTuple = openSelector();
        selector = selectorTuple.selector;
        unwrappedSelector = selectorTuple.unwrappedSelector;
        selectStrategy = strategy;
    }

    private static final class SelectorTuple {
        final Selector unwrappedSelector;
        final Selector selector;

        SelectorTuple(Selector unwrappedSelector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = unwrappedSelector;
        }

        SelectorTuple(Selector unwrappedSelector, Selector selector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = selector;
        }
    }

    /**
     * one-to-zero:
     * 初始化 selector 多路复用器
     * 需要注意：这个方法在服务端启动的时候会被执行
     *  比如 boss 一个线程、 worker 四个线程，那么这个方法会被调用 5 次，也就是每个IO线程都会在初始化的时候就开启 selector
     *  note：这里仅仅是为每一个线程初始化好 selector 实例，并不是就开始监听了，因为还没有channel，更没有完成绑定注册事件到selector上
     *
     */
    private SelectorTuple openSelector() {
        final Selector unwrappedSelector;
        try {
            unwrappedSelector = provider.openSelector();//从选择器提供者打开一个选择器，刚打开的选择器是未包装的选择器，裸选择器
        } catch (IOException e) {
            throw new ChannelException("failed to open a new selector", e);
        }


        if (DISABLE_KEY_SET_OPTIMIZATION) { //如果key集合不优化，则选择器默认为选择器提供者打开的选择器
            return new SelectorTuple(unwrappedSelector);
        }

        /* ===== 下面代码估计是所说的 netty 对 selector 的 selectedKeys 进行了优化逻辑   暂时不做分析 ===== */

        //在当前线程访问控制选择下，加载选择器实现类，不初始化
        Object maybeSelectorImplClass = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    return Class.forName(
                            "sun.nio.ch.SelectorImpl",
                            false,
                            PlatformDependent.getSystemClassLoader());
                } catch (Throwable cause) {
                    return cause;
                }
            }
        });

        //如果从系统类加载器加载的选择key实现类不是Class实例，或不是裸选择器类型，不进行选择器key集合优化
        if (!(maybeSelectorImplClass instanceof Class) ||
            // ensure the current selector implementation is what we can instrument.
            !((Class<?>) maybeSelectorImplClass).isAssignableFrom(unwrappedSelector.getClass())) {
            if (maybeSelectorImplClass instanceof Throwable) {
                Throwable t = (Throwable) maybeSelectorImplClass;
                logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, t);
            }
            return new SelectorTuple(unwrappedSelector);
        }

        final Class<?> selectorImplClass = (Class<?>) maybeSelectorImplClass;
        final SelectedSelectionKeySet selectedKeySet = new SelectedSelectionKeySet();

        Object maybeException = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    //在当前线程相同访问控制权限下，获取系统选择器实现类的
                    //选择器就绪key集合selectedKeysField及其代理publicSelectedKeysField
                    Field selectedKeysField = selectorImplClass.getDeclaredField("selectedKeys");
                    Field publicSelectedKeysField = selectorImplClass.getDeclaredField("publicSelectedKeys");

                    if (PlatformDependent.javaVersion() >= 9 && PlatformDependent.hasUnsafe()) {
                        // Let us try to use sun.misc.Unsafe to replace the SelectionKeySet.
                        // This allows us to also do this in Java9+ without any extra flags.
                        long selectedKeysFieldOffset = PlatformDependent.objectFieldOffset(selectedKeysField);
                        long publicSelectedKeysFieldOffset =
                                PlatformDependent.objectFieldOffset(publicSelectedKeysField);

                        if (selectedKeysFieldOffset != -1 && publicSelectedKeysFieldOffset != -1) {
                            PlatformDependent.putObject(
                                    unwrappedSelector, selectedKeysFieldOffset, selectedKeySet);
                            PlatformDependent.putObject(
                                    unwrappedSelector, publicSelectedKeysFieldOffset, selectedKeySet);
                            return null;
                        }
                        // We could not retrieve the offset, lets try reflection as last-resort.
                    }

                    Throwable cause = ReflectionUtil.trySetAccessible(selectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }
                    cause = ReflectionUtil.trySetAccessible(publicSelectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }

                    //将系统选择器的就绪key集合selectedKeysField及其代理publicSelectedKeysField
                    //设置为selectedKeySet
                    selectedKeysField.set(unwrappedSelector, selectedKeySet);
                    publicSelectedKeysField.set(unwrappedSelector, selectedKeySet);
                    return null;
                } catch (NoSuchFieldException e) {
                    return e;
                } catch (IllegalAccessException e) {
                    return e;
                }
            }
        });

        if (maybeException instanceof Exception) {
            selectedKeys = null;
            Exception e = (Exception) maybeException;
            logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, e);
            return new SelectorTuple(unwrappedSelector);
        }
        //初始化选择key集合
        selectedKeys = selectedKeySet;
        logger.trace("instrumented a special java.util.Set into: {}", unwrappedSelector);
        return new SelectorTuple(unwrappedSelector,
                                 new SelectedSelectionKeySetSelector(unwrappedSelector, selectedKeySet));
    }

    /**
     * Returns the {@link SelectorProvider} used by this {@link NioEventLoop} to obtain the {@link Selector}.
     */
    public SelectorProvider selectorProvider() {
        return provider;
    }

    /**
     * one-to-zero:
     *
     * netty中的queue
     * 使用了 MpscChunkedArrayQueue 对象，Mpsc含义就是 ：Multi Producer Single Consumer
     * 该队列是支持多生产单消费者的。该模型正好符合netty的线程执行模型，即可以有多个外部线程将任务提交给loop，而loop只有一个线程处理。
     * MpscChunkedArrayQueue 有点：
     *  1 取消了锁，通过自旋+cas来将任务存放到队列中，当然如果并发频率太高也会使得我们的cpu使用率比较高
     *  2 消除了伪共享，通过缓存填充方式，让一个任务就是一个完成的缓存行，从而不会影响别的数据
     *  3 这个是数组实现的，但是支持动态扩容
     *  4 网上做了一个测试，MpscChunkedArrayQueue 性能比 LinkedBlockingQueue 和 ArrayBlockingQueue 要好
     *
     * 为什么 LinkedBlockingQueue 和 ArrayBlockingQueue 容易缓存失效
     *      LinkedBlockingQueue的head和last是相邻的，ArrayBlockingQueue的takeIndex和putIndex是相邻的;
     *      而我们都知道CPU将数据加载到缓存实际上是按照缓存行加载的，因此可能出现明明没有修改last，
     *      但由于出列操作修改了head，导致整个缓存行失效，需要重新进行加载；
     */
    @Override
    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
        // This event loop never calls takeTask()
        return maxPendingTasks == Integer.MAX_VALUE ? PlatformDependent.<Runnable>newMpscQueue()
                                                    : PlatformDependent.<Runnable>newMpscQueue(maxPendingTasks);
    }

    /**
     * Registers an arbitrary {@link SelectableChannel}, not necessarily created by Netty, to the {@link Selector}
     * of this event loop.  Once the specified {@link SelectableChannel} is registered, the specified {@code task} will
     * be executed by this event loop when the {@link SelectableChannel} is ready.
     */
    public void register(final SelectableChannel ch, final int interestOps, final NioTask<?> task) {
        if (ch == null) {
            throw new NullPointerException("ch");
        }
        if (interestOps == 0) {
            throw new IllegalArgumentException("interestOps must be non-zero.");
        }
        if ((interestOps & ~ch.validOps()) != 0) {
            throw new IllegalArgumentException(
                    "invalid interestOps: " + interestOps + "(validOps: " + ch.validOps() + ')');
        }
        if (task == null) {
            throw new NullPointerException("task");
        }

        if (isShutdown()) {
            throw new IllegalStateException("event loop shut down");
        }

        if (inEventLoop()) {
            register0(ch, interestOps, task);
        } else {
            try {
                // Offload to the EventLoop as otherwise java.nio.channels.spi.AbstractSelectableChannel.register
                // may block for a long time while trying to obtain an internal lock that may be hold while selecting.
                submit(new Runnable() {
                    @Override
                    public void run() {
                        register0(ch, interestOps, task);
                    }
                }).sync();
            } catch (InterruptedException ignore) {
                // Even if interrupted we did schedule it so just mark the Thread as interrupted.
                Thread.currentThread().interrupt();
            }
        }
    }

    private void register0(SelectableChannel ch, int interestOps, NioTask<?> task) {
        try {
            ch.register(unwrappedSelector, interestOps, task);
        } catch (Exception e) {
            throw new EventLoopException("failed to register a channel", e);
        }
    }

    /**
     * Returns the percentage of the desired amount of time spent for I/O in the event loop.
     */
    public int getIoRatio() {
        return ioRatio;
    }

    /**
     * Sets the percentage of the desired amount of time spent for I/O in the event loop.  The default value is
     * {@code 50}, which means the event loop will try to spend the same amount of time for I/O as for non-I/O tasks.
     */
    public void setIoRatio(int ioRatio) {
        if (ioRatio <= 0 || ioRatio > 100) {
            throw new IllegalArgumentException("ioRatio: " + ioRatio + " (expected: 0 < ioRatio <= 100)");
        }
        this.ioRatio = ioRatio;
    }

    /**
     * Replaces the current {@link Selector} of this event loop with newly created {@link Selector}s to work
     * around the infamous epoll 100% CPU bug.
     */
    public void rebuildSelector() {
        if (!inEventLoop()) {
            execute(new Runnable() {
                @Override
                public void run() {
                    rebuildSelector0();
                }
            });
            return;
        }
        rebuildSelector0();
    }

    @Override
    public int registeredChannels() {
        return selector.keys().size() - cancelledKeys;
    }

    /**
     * one-to-zero:
     * 当出现 selector 死循环bug后，就会重新selector，然后将老的连接全部转移到新的selector上
     */
    private void rebuildSelector0() {
        final Selector oldSelector = selector;
        final SelectorTuple newSelectorTuple;

        if (oldSelector == null) {
            return;
        }

        try {
            newSelectorTuple = openSelector();
        } catch (Exception e) {
            logger.warn("Failed to create a new Selector.", e);
            return;
        }

        // Register all channels to the new Selector.
        int nChannels = 0;
        /**
         * one-to-zero:
         * 1 遍历老的所有key
         * 2 判断key是否有效，并且取消和旧的selector的注册事件
         * 3 将key对应的channel注册到新的selector上
         * 4 重新绑定channel和新的key的关系
         *
         */
        for (SelectionKey key: oldSelector.keys()) {
            Object a = key.attachment();
            try {
                if (!key.isValid() || key.channel().keyFor(newSelectorTuple.unwrappedSelector) != null) {
                    continue;
                }

                int interestOps = key.interestOps();
                key.cancel();
                /* 将key对应的channel注册到新的selector上 */
                SelectionKey newKey = key.channel().register(newSelectorTuple.unwrappedSelector, interestOps, a);
                if (a instanceof AbstractNioChannel) {
                    // Update SelectionKey
                    ((AbstractNioChannel) a).selectionKey = newKey;
                }
                nChannels ++;
            } catch (Exception e) {
                logger.warn("Failed to re-register a Channel to the new Selector.", e);
                if (a instanceof AbstractNioChannel) {
                    AbstractNioChannel ch = (AbstractNioChannel) a;
                    ch.unsafe().close(ch.unsafe().voidPromise());
                } else {
                    @SuppressWarnings("unchecked")
                    NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                    invokeChannelUnregistered(task, key, e);
                }
            }
        }

        selector = newSelectorTuple.selector;
        unwrappedSelector = newSelectorTuple.unwrappedSelector;

        try {
            // time to close the old selector as everything else is registered to the new one
            oldSelector.close();
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("Failed to close the old Selector.", t);
            }
        }

        if (logger.isInfoEnabled()) {
            logger.info("Migrated " + nChannels + " channel(s) to the new Selector.");
        }
    }

    /**
     * one-to-zero:
     * 这个方法是 nio 线程核心方法
     *
     *
     */
    @Override
    protected void run() {
        for (; ; ) {
            try {
                try {
                    /**
                     * one-to-zero:
                     *  判断有没有任务，
                     *      如果有任务则会实际会调用返回 selector.selectNow(),这个方法返回值肯定 >= 0
                     *      如果没有任务，则会调用返回 SelectStrategy.SELECT，就是下面的第三个类型
                     *
                     *  通过看 selectStrategy 的实现{@link DefaultSelectStrategy} 其返回值
                     *      要么是 selector.selectNow()
                     *      要么是 SelectStrategy.SELECT
                     *      所以下面的第一、第二两种情况都不会遇到
                     */
                    switch (selectStrategy.calculateStrategy(selectNowSupplier, hasTasks())) {
                        case SelectStrategy.CONTINUE:
                            continue;

                        case SelectStrategy.BUSY_WAIT:
                            // fall-through to SELECT since the busy-wait is not supported with NIO

                        case SelectStrategy.SELECT:
                            select(wakenUp.getAndSet(false));

                            // 'wakenUp.compareAndSet(false, true)' is always evaluated
                            // before calling 'selector.wakeup()' to reduce the wake-up
                            // overhead. (Selector.wakeup() is an expensive operation.)
                            //
                            // However, there is a race condition in this approach.
                            // The race condition is triggered when 'wakenUp' is set to
                            // true too early.
                            //
                            // 'wakenUp' is set to true too early if:
                            // 1) Selector is waken up between 'wakenUp.set(false)' and
                            //    'selector.select(...)'. (BAD)
                            // 2) Selector is waken up between 'selector.select(...)' and
                            //    'if (wakenUp.get()) { ... }'. (OK)
                            //
                            // In the first case, 'wakenUp' is set to true and the
                            // following 'selector.select(...)' will wake up immediately.
                            // Until 'wakenUp' is set to false again in the next round,
                            // 'wakenUp.compareAndSet(false, true)' will fail, and therefore
                            // any attempt to wake up the Selector will fail, too, causing
                            // the following 'selector.select(...)' call to block
                            // unnecessarily.
                            //
                            // To fix this problem, we wake up the selector again if wakenUp
                            // is true immediately after selector.select(...).
                            // It is inefficient in that it wakes up the selector for both
                            // the first case (BAD - wake-up required) and the second case
                            // (OK - no wake-up required).

                            if (wakenUp.get()) {
                                selector.wakeup();
                            }
                            // fall through
                        default:
                    }
                } catch (IOException e) {
                    // If we receive an IOException here its because the Selector is messed up. Let's rebuild
                    // the selector and retry. https://github.com/netty/netty/issues/8566
                    rebuildSelector0();
                    handleLoopException(e);
                    continue;
                }

                cancelledKeys = 0;
                needsToSelectAgain = false;
                final int ioRatio = this.ioRatio;
                if (ioRatio == 100) {
                    try {
                        processSelectedKeys();
                    } finally {
                        // Ensure we always run tasks.
                        runAllTasks();
                    }
                } else {
                    final long ioStartTime = System.nanoTime();
                    try {
                        processSelectedKeys();
                    } finally {
                        // Ensure we always run tasks.
                        /**
                         * one-to-zero:
                         *  处理完IO事件后，就会处理任务 task，默认情况下，执行任务会设置一个时间，不会让其一直执行，
                         *  防止影响IO事件一直处于长时间等待
                         *
                         *  task执行的时间是根据 本次IO操作的执行时间计算得来
                         *  IO执行的越久，那么 ioTime 就越大，计算式 ioTime * (100 - ioRatio) / ioRatio 就越大
                         *  e.g.
                         *      如果 {@link this#ioRatio} 等于50，说明IO执行多久时间，那么 task就应该多久时间
                         *
                         */
                        final long ioTime = System.nanoTime() - ioStartTime;
                        runAllTasks(ioTime * (100 - ioRatio) / ioRatio);
                    }
                }
            } catch (Throwable t) {
                handleLoopException(t);
            }
            // Always handle shutdown even if the loop processing threw an exception.
            /**
             * one-to-zero:
             *  每一次循环末尾都要判断一次IO线程是否被关闭，作相应的处理
             */
            try {
                if (isShuttingDown()) {
                    closeAll();
                    if (confirmShutdown()) {
                        return;
                    }
                }
            } catch (Throwable t) {
                handleLoopException(t);
            }
        }
    }

    private static void handleLoopException(Throwable t) {
        logger.warn("Unexpected exception in the selector loop.", t);

        // Prevent possible consecutive immediate failures that lead to
        // excessive CPU consumption.
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // Ignore.
        }
    }

    /**
     * one-to-zero:
     *      该方法就是执行就绪事件,netty还做了一个优化,就是通过反射把Selector的实现类中两个属性selectedKeys（就绪key集合）
     *      和 publicSelectedKeys（给外部访问就绪io事件的集合）从hashSet替换数组。
     * 优化原因：
     *      首先当netty发现有io事件就绪就会把selectionKey塞入上述set集合，如果采用set，那么当io事件变多导致hash冲突进而形成链表，那么我们add操作就不是常数复杂度
     * 数组优势：
     *      如果采用数组我们不仅省去了计算hash的事件也不会存在因为要把key插入链表需要迭代，直接插入数组尾部即可。插入操作的复杂度永远是常数范围。
     *
     */
    private void processSelectedKeys() {
        if (selectedKeys != null) {
            processSelectedKeysOptimized(); // 开启优化则会进入该分支
        } else {
            processSelectedKeysPlain(selector.selectedKeys());// 未开启优化则进入该分支
        }
    }

    @Override
    protected void cleanup() {
        try {
            selector.close();
        } catch (IOException e) {
            logger.warn("Failed to close a selector.", e);
        }
    }

    void cancel(SelectionKey key) {
        key.cancel();
        cancelledKeys ++;
        if (cancelledKeys >= CLEANUP_INTERVAL) {
            cancelledKeys = 0;
            needsToSelectAgain = true;
        }
    }

    @Override
    protected Runnable pollTask() {
        Runnable task = super.pollTask();
        if (needsToSelectAgain) {
            selectAgain();
        }
        return task;
    }

    private void processSelectedKeysPlain(Set<SelectionKey> selectedKeys) {
        // check if the set is empty and if so just return to not create garbage by
        // creating a new Iterator every time even if there is nothing to process.
        // See https://github.com/netty/netty/issues/597
        /**
         * one-to-zero:
         *  这个方法执行非常快，频率很高，如果很多次都是空集合，那么没有必要往下执行，虽然下面代码没有问题，但是会创建
         *  一个迭代器对象，增加 gc
         */
        if (selectedKeys.isEmpty()) {
            return;
        }

        Iterator<SelectionKey> i = selectedKeys.iterator();
        for (;;) {
            final SelectionKey k = i.next();
            final Object a = k.attachment();
            i.remove();

            /*
             * one-to-zero:
             *  如果是 AbstractNioChannel 类型，说明它是 NioServerSocketChannel 或者是 NioSocketChannel
             *  需要进行IO读写相关操作
             */
            if (a instanceof AbstractNioChannel) {
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                /*
                 * 不是 AbstractNioChannel 类型说明是 NioTask
                 * 由于 netty 自身没有实现这个接口，说以通常情况下不会执行该分支，除非用户自行注册该 Task 到多路复用器
                 */
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            if (!i.hasNext()) {
                break;
            }

            if (needsToSelectAgain) {
                selectAgain();
                selectedKeys = selector.selectedKeys();

                // Create the iterator again to avoid ConcurrentModificationException
                if (selectedKeys.isEmpty()) {
                    break;
                } else {
                    i = selectedKeys.iterator();
                }
            }
        }
    }

    private void processSelectedKeysOptimized() {
        for (int i = 0; i < selectedKeys.size; ++i) {
            final SelectionKey k = selectedKeys.keys[i];
            // null out entry in the array to allow to have it GC'ed once the Channel close
            // See https://github.com/netty/netty/issues/2363
            selectedKeys.keys[i] = null;// one-to-zero:SelectedSelectionKeySet may hold strong reference to SelectionKey after Channel is closed

            final Object a = k.attachment();

            if (a instanceof AbstractNioChannel) {
                /*
                 * one-to-zero:
                 *  如果是 AbstractNioChannel 类型，说明它是 NioServerSocketChannel 或者是 NioSocketChannel
                 *  需要进行IO读写相关操作
                 */
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                /*
                 * 不是 AbstractNioChannel 类型说明是 NioTask
                 * 由于 netty 自身没有实现这个接口，说以通常情况下不会执行该分支，除非用户自行注册该 Task 到多路复用器
                 */
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            if (needsToSelectAgain) {
                // null out entries in the array to allow to have it GC'ed once the Channel close
                // See https://github.com/netty/netty/issues/2363
                selectedKeys.reset(i + 1);

                selectAgain();
                i = -1;
            }
        }
    }

    private void processSelectedKey(SelectionKey k, AbstractNioChannel ch) {
        /**
         * one-to-zero:
         *  如果是注册事件，那么这个 channel 是 NioServerSocketChannel，因为是发生在 boss 上的 selector
         *  如果是读事件，那么这个 channel 是 NioSocketChannel，因为是发生在 worker 上的 selector
         *
         *
         */
        final AbstractNioChannel.NioUnsafe unsafe = ch.unsafe();
        /* 如果这个键是不可用的，那么需要关闭 */
        if (!k.isValid()) {
            final EventLoop eventLoop;
            try {
                eventLoop = ch.eventLoop();
            } catch (Throwable ignored) {
                // If the channel implementation throws an exception because there is no event loop, we ignore this
                // because we are only trying to determine if ch is registered to this event loop and thus has authority
                // to close ch.
                return;
            }
            // Only close ch if ch is still registered to this EventLoop. ch could have deregistered from the event loop
            // and thus the SelectionKey could be cancelled as part of the deregistration process, but the channel is
            // still healthy and should not be closed.
            // See https://github.com/netty/netty/issues/5125
            if (eventLoop != this || eventLoop == null) {
                return;
            }
            // close the channel if the key is not valid anymore
            unsafe.close(unsafe.voidPromise());
            return;
        }

        try {
            int readyOps = k.readyOps();
            // We first need to call finishConnect() before try to trigger a read(...) or write(...) as otherwise
            // the NIO JDK channel implementation may throw a NotYetConnectedException.
            if ((readyOps & SelectionKey.OP_CONNECT) != 0) {
                // remove OP_CONNECT as otherwise Selector.select(..) will always return without blocking
                // See https://github.com/netty/netty/issues/924
                int ops = k.interestOps();
                ops &= ~SelectionKey.OP_CONNECT;
                k.interestOps(ops);

                unsafe.finishConnect();
            }

            /**
             *  如果是写操作，则说明有半包消息尚未发送完成，需要继续使用flush方法进行发送
             */
            // Process OP_WRITE first as we may be able to write some queued buffers and so free memory.
            if ((readyOps & SelectionKey.OP_WRITE) != 0) {
                // Call forceFlush which will also take care of clear the OP_WRITE once there is nothing left to write
                ch.unsafe().forceFlush();
            }

            // Also check for readOps of 0 to workaround possible JDK bug which may otherwise lead
            // to a spin loop
            /**
             * one-to-zero:
             *  unsafe的实现主要是 NioServerSocketChannel 和 NioSocketChannel 实现
             *  如果是 NioServerSocketChannel 实现，那么新连接接入会进入 {@link AbstractNioMessageChannel} 类中的 NioMessageUnsafe#read
             *  如果是 NioSocketChannel 实现，那么新连接接入会进入 {@link AbstractNioByteChannel} 类中的 NioByteUnsafe#read
             */
            if ((readyOps & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0 || readyOps == 0) {
                unsafe.read();
            }
        } catch (CancelledKeyException ignored) {
            unsafe.close(unsafe.voidPromise());
        }
    }

    private static void processSelectedKey(SelectionKey k, NioTask<SelectableChannel> task) {
        int state = 0;
        try {
            task.channelReady(k.channel(), k);
            state = 1;
        } catch (Exception e) {
            k.cancel();
            invokeChannelUnregistered(task, k, e);
            state = 2;
        } finally {
            switch (state) {
            case 0:
                k.cancel();
                invokeChannelUnregistered(task, k, null);
                break;
            case 1:
                if (!k.isValid()) { // Cancelled by channelReady()
                    invokeChannelUnregistered(task, k, null);
                }
                break;
            }
        }
    }

    private void closeAll() {
        selectAgain();
        Set<SelectionKey> keys = selector.keys();
        Collection<AbstractNioChannel> channels = new ArrayList<AbstractNioChannel>(keys.size());
        for (SelectionKey k: keys) {
            Object a = k.attachment();
            if (a instanceof AbstractNioChannel) {
                channels.add((AbstractNioChannel) a);
            } else {
                k.cancel();
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                invokeChannelUnregistered(task, k, null);
            }
        }

        for (AbstractNioChannel ch: channels) {
            ch.unsafe().close(ch.unsafe().voidPromise());
        }
    }

    private static void invokeChannelUnregistered(NioTask<SelectableChannel> task, SelectionKey k, Throwable cause) {
        try {
            task.channelUnregistered(k.channel(), cause);
        } catch (Exception e) {
            logger.warn("Unexpected exception while running NioTask.channelUnregistered()", e);
        }
    }

    @Override
    protected void wakeup(boolean inEventLoop) {
        if (!inEventLoop && wakenUp.compareAndSet(false, true)) {
            selector.wakeup();
        }
    }

    Selector unwrappedSelector() {
        return unwrappedSelector;
    }

    int selectNow() throws IOException {
        try {
            return selector.selectNow();
        } finally {
            // restore wakeup state if needed
            if (wakenUp.get()) {
                selector.wakeup();
            }
        }
    }

    /**
     * one-to-zero:
     *  1 计算select唤醒时间
     *  2
     */
    private void select(boolean oldWakenUp) throws IOException {
        Selector selector = this.selector;
        try {
            int selectCnt = 0;
            long currentTimeNanos = System.nanoTime();
            long selectDeadLineNanos = currentTimeNanos + delayNanos(currentTimeNanos);

            for (;;) {
                /**
                 * one-to-zero:
                 * 计算select操作自动唤醒时间 默认就是 1000，也就是最多阻塞自己 1 秒
                 */
                long timeoutMillis = (selectDeadLineNanos - currentTimeNanos + 500000L) / 1000000L;
                if (timeoutMillis <= 0) {
                    if (selectCnt == 0) {
                        selector.selectNow();
                        selectCnt = 1;
                    }
                    break;
                }

                // If a task was submitted when wakenUp value was true, the task didn't get a chance to call
                // Selector#wakeup. So we need to check task queue again before executing select operation.
                // If we don't, the task might be pended until select operation was timed out.
                // It might be pended until idle timeout if IdleStateHandler existed in pipeline.
                if (hasTasks() && wakenUp.compareAndSet(false, true)) {
                    selector.selectNow();
                    selectCnt = 1;
                    break;
                }

                /**
                 * one-to-zero:
                 *  可以看出，netty中没有采用 selector.select()方法，而是采用定时唤醒方法
                 */
                int selectedKeys = selector.select(timeoutMillis);
                selectCnt ++;

                if (selectedKeys != 0 || oldWakenUp || wakenUp.get() || hasTasks() || hasScheduledTasks()) {
                    // - Selected something,
                    // - waken up by user, or
                    // - the task queue has a pending task.
                    // - a scheduled task is ready for processing
                    break;
                }

                /**
                 * one-to-zero:
                 *  线程从 select 唤醒有多中情况，其中一种就是 调用了 Thread.currentThread().interrupt() 方法
                 *  netty 会判断并且建议不要调用这个方法，建议使用 NioEventLoop.shutdownGracefully()
                 */
                if (Thread.interrupted()) {
                    // Thread was interrupted so reset selected keys and break so we not run into a busy loop.
                    // As this is most likely a bug in the handler of the user or it's client library we will
                    // also log it.
                    //
                    // See https://github.com/netty/netty/issues/2426
                    if (logger.isDebugEnabled()) {
                        logger.debug("Selector.select() returned prematurely because " +
                                "Thread.currentThread().interrupt() was called. Use " +
                                "NioEventLoop.shutdownGracefully() to shutdown the NioEventLoop.");
                    }
                    selectCnt = 1;
                    break;
                }

                long time = System.nanoTime();
                /**
                 * one-to-zero:
                 *  判断逻辑大概是：本应该是沉睡 timeoutMillis 时间，但是发现如果没有睡到这么多时间，那说明发生了空轮循，
                 *  因为前面的逻辑判断了如果提前返回只有这么几种情况：
                 *      1 轮循到了IO事件
                 *      2 调用了 wakenUp 方法
                 *      3 线程被中断
                 *      4 空轮循
                 *   前面三种情况都在之前已经判断过了，流程到了这里说明只有一种空轮循情况了
                 */
                if (time - TimeUnit.MILLISECONDS.toNanos(timeoutMillis) >= currentTimeNanos) {
                    // timeoutMillis elapsed without anything selected.
                    selectCnt = 1;
                } else if (SELECTOR_AUTO_REBUILD_THRESHOLD > 0 && selectCnt >= SELECTOR_AUTO_REBUILD_THRESHOLD) {
                    // The code exists in an extra method to ensure the method is not too big to inline as this
                    // branch is not very likely to get hit very frequently.
                    selector = selectRebuildSelector(selectCnt);
                    selectCnt = 1;
                    break;
                }

                currentTimeNanos = time;
            }

            /**
             * one-to-zero: 判断是会否打印日志
             */
            if (selectCnt > MIN_PREMATURE_SELECTOR_RETURNS) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Selector.select() returned prematurely {} times in a row for Selector {}.",
                            selectCnt - 1, selector);
                }
            }
        } catch (CancelledKeyException e) {
            if (logger.isDebugEnabled()) {
                logger.debug(CancelledKeyException.class.getSimpleName() + " raised by a Selector {} - JDK bug?",
                        selector, e);
            }
            // Harmless exception - log anyway
        }
    }

    private Selector selectRebuildSelector(int selectCnt) throws IOException {
        // The selector returned prematurely many times in a row.
        // Rebuild the selector to work around the problem.
        logger.warn(
                "Selector.select() returned prematurely {} times in a row; rebuilding Selector {}.",
                selectCnt, selector);

        rebuildSelector();
        Selector selector = this.selector;

        // Select again to populate selectedKeys.
        selector.selectNow();
        return selector;
    }

    private void selectAgain() {
        needsToSelectAgain = false;
        try {
            selector.selectNow();
        } catch (Throwable t) {
            logger.warn("Failed to update SelectionKeys.", t);
        }
    }
}
