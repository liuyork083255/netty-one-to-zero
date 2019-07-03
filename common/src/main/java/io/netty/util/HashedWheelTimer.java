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
package io.netty.util;

import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;

import static io.netty.util.internal.StringUtil.simpleClassName;

/**
 * A {@link Timer} optimized for approximated I/O timeout scheduling.
 *
 * <h3>Tick Duration</h3>
 *
 * As described with 'approximated', this timer does not execute the scheduled
 * {@link TimerTask} on time.  {@link HashedWheelTimer}, on every tick, will
 * check if there are any {@link TimerTask}s behind the schedule and execute
 * them.
 * <p>
 * You can increase or decrease the accuracy of the execution timing by
 * specifying smaller or larger tick duration in the constructor.  In most
 * network applications, I/O timeout does not need to be accurate.  Therefore,
 * the default tick duration is 100 milliseconds and you will not need to try
 * different configurations in most cases.
 *
 * <h3>Ticks per Wheel (Wheel Size)</h3>
 *
 * {@link HashedWheelTimer} maintains a data structure called 'wheel'.
 * To put simply, a wheel is a hash table of {@link TimerTask}s whose hash
 * function is 'dead line of the task'.  The default number of ticks per wheel
 * (i.e. the size of the wheel) is 512.  You could specify a larger value
 * if you are going to schedule a lot of timeouts.
 *
 * <h3>Do not create many instances.</h3>
 *
 * {@link HashedWheelTimer} creates a new thread whenever it is instantiated and
 * started.  Therefore, you should make sure to create only one instance and
 * share it across your application.  One of the common mistakes, that makes
 * your application unresponsive, is to create a new instance for every connection.
 *
 * <h3>Implementation Details</h3>
 *
 * {@link HashedWheelTimer} is based on
 * <a href="http://cseweb.ucsd.edu/users/varghese/">George Varghese</a> and
 * Tony Lauck's paper,
 * <a href="http://cseweb.ucsd.edu/users/varghese/PAPERS/twheel.ps.Z">'Hashed
 * and Hierarchical Timing Wheels: data structures to efficiently implement a
 * timer facility'</a>.  More comprehensive slides are located
 * <a href="http://www.cse.wustl.edu/~cdgill/courses/cs6874/TimingWheels.ppt">here</a>.
 *
 * one-to-zero:
 *
 *
 *  基本名词
 *      TimerTask 是一个定时任务的实现接口，其中run方法包装了定时任务的逻辑
 *      Timeout 是一个定时任务提交到Timer之后返回的句柄，通过这个句柄外部可以取消这个定时任务，并对定时任务的状态进行一些基本的判断
 *      Timer 是 HashedWheelTimer 实现的父接口，仅定义了如何提交定时任务和如何停止整个定时机制
 *
 *  简单概述：
 *      HashedWheelTimer 就是一个单线程执行的定时器，所有的任务添加都是基于一个时间轮，
 *      轮子上的每一个格子有一个时间间隔概念：也就是线程需要等待的时间，和{@link java.util.Timer}不一样，后者是沉睡的时间是基于最先过期任务的到期时间，
 *          但是 HashedWheelTimer 则只要启动了就会无限循环下去，没有任务也会轮训等待
 *      轮子上的每一个格子都是 {@link HashedWheelBucket}类型，里面有指向具体的任务，是链表结构
 *
 * Note:
 *      由于是单线程，所有任务都是串行，尽量不要执行耗时任务
 *      从构造方法来看，好像不支持重复执行功能
 *
 */
public class HashedWheelTimer implements Timer {

    static final InternalLogger logger = InternalLoggerFactory.getInstance(HashedWheelTimer.class);

    private static final AtomicInteger INSTANCE_COUNTER = new AtomicInteger();
    private static final AtomicBoolean WARNED_TOO_MANY_INSTANCES = new AtomicBoolean();
    private static final int INSTANCE_COUNT_LIMIT = 64;
    private static final long MILLISECOND_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final ResourceLeakDetector<HashedWheelTimer> leakDetector = ResourceLeakDetectorFactory.instance().newResourceLeakDetector(HashedWheelTimer.class, 1);

    /** 修饰的字段必须是 volatile  */
    private static final AtomicIntegerFieldUpdater<HashedWheelTimer> WORKER_STATE_UPDATER = AtomicIntegerFieldUpdater.newUpdater(HashedWheelTimer.class, "workerState");

    private final ResourceLeakTracker<HashedWheelTimer> leak;

    /** 核心线程的启动逻辑 runnable */
    private final Worker worker = new Worker();

    /** 核心线程，处理定时任务是单线程，所以所有任务都是串行执行，如果前面一个任务时间较长，那么会影响后续的任务时间 */
    private final Thread workerThread;

    /** 用于标记时间轮的状态，也就是定时任务器的状态 */
    public static final int WORKER_STATE_INIT = 0;
    public static final int WORKER_STATE_STARTED = 1;
    public static final int WORKER_STATE_SHUTDOWN = 2;

    @SuppressWarnings({ "unused", "FieldMayBeFinal" })
    private volatile int workerState; // 0 - init, 1 - started, 2 - shut down

    /** 表示一个环上的槽的间隔时间 */
    private final long tickDuration;

    /** 一个环上槽，数据结构是数组，长度个数是 2 的 n 次方 {@link #createWheel} */
    private final HashedWheelBucket[] wheel;

    /**
     * 这是一个标示符，用来快速计算任务应该呆的格子。
     * 我们知道，给定一个deadline的定时任务，其应该呆的格子 = deadline % wheel.length
     * 但是 % 操作是个相对耗时的操作，所以使用一种变通的位运算代替：
     *  因为一圈的长度为 2 的 n 次方，mask = 2^n-1 后低位将全部是1，所以 deadline & mast 等价于 deadline % wheel.length，但是前者的性能很好
     *  java 中的 HashMap 也是使用这种处理方法
     * 其实就是用来辅助计算任务应该在哪个格子里面的一个中间值，其在构造方法中被初始化 值就等于 2^n - 1
     */
    private final int mask;

    private final CountDownLatch startTimeInitialized = new CountDownLatch(1);

    /**
     * 定时任务的队列，采用 JCTool 工具类
     * 所有新增的任务都先放在这个 queue 中，而不是直接放入时间轮格子的链表中
     */
    private final Queue<HashedWheelTimeout> timeouts = PlatformDependent.newMpscQueue();

    private final Queue<HashedWheelTimeout> cancelledTimeouts = PlatformDependent.newMpscQueue();

    /**
     * 记录任务的个数，新增一个任务 +1
     *  移除一个任务 -1
     */
    private final AtomicLong pendingTimeouts = new AtomicLong(0);

    /**
     * 最大任务数
     */
    private final long maxPendingTimeouts;

    /**
     * worker 线程启动的时候就会设置个时间，表示当前定时器启动的时间，是一个纳秒值
     */
    private volatile long startTime;

    /**
     * Creates a new timer with the default thread factory
     * ({@link Executors#defaultThreadFactory()}), default tick duration, and
     * default number of ticks per wheel.
     */
    public HashedWheelTimer() {
        this(Executors.defaultThreadFactory());
    }

    /**
     * Creates a new timer with the default thread factory
     * ({@link Executors#defaultThreadFactory()}) and default number of ticks
     * per wheel.
     *
     * @param tickDuration   the duration between tick
     * @param unit           the time unit of the {@code tickDuration}
     * @throws NullPointerException     if {@code unit} is {@code null}
     * @throws IllegalArgumentException if {@code tickDuration} is &lt;= 0
     */
    public HashedWheelTimer(long tickDuration, TimeUnit unit) {
        this(Executors.defaultThreadFactory(), tickDuration, unit);
    }

    /**
     * Creates a new timer with the default thread factory
     * ({@link Executors#defaultThreadFactory()}).
     *
     * @param tickDuration   the duration between tick
     * @param unit           the time unit of the {@code tickDuration}
     * @param ticksPerWheel  the size of the wheel
     * @throws NullPointerException     if {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(long tickDuration, TimeUnit unit, int ticksPerWheel) {
        this(Executors.defaultThreadFactory(), tickDuration, unit, ticksPerWheel);
    }

    /**
     * Creates a new timer with the default tick duration and default number of
     * ticks per wheel.
     *
     * @param threadFactory  a {@link ThreadFactory} that creates a
     *                       background {@link Thread} which is dedicated to
     *                       {@link TimerTask} execution.
     * @throws NullPointerException if {@code threadFactory} is {@code null}
     */
    public HashedWheelTimer(ThreadFactory threadFactory) {
        this(threadFactory, 100, TimeUnit.MILLISECONDS);
    }

    /**
     * Creates a new timer with the default number of ticks per wheel.
     *
     * @param threadFactory  a {@link ThreadFactory} that creates a
     *                       background {@link Thread} which is dedicated to
     *                       {@link TimerTask} execution.
     * @param tickDuration   the duration between tick
     * @param unit           the time unit of the {@code tickDuration}
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if {@code tickDuration} is &lt;= 0
     */
    public HashedWheelTimer(
            ThreadFactory threadFactory, long tickDuration, TimeUnit unit) {
        this(threadFactory, tickDuration, unit, 512);
    }

    /**
     * Creates a new timer.
     *
     * @param threadFactory  a {@link ThreadFactory} that creates a
     *                       background {@link Thread} which is dedicated to
     *                       {@link TimerTask} execution.
     * @param tickDuration   the duration between tick
     * @param unit           the time unit of the {@code tickDuration}
     * @param ticksPerWheel  the size of the wheel
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(
            ThreadFactory threadFactory,
            long tickDuration, TimeUnit unit, int ticksPerWheel) {
        this(threadFactory, tickDuration, unit, ticksPerWheel, true);
    }

    /**
     * Creates a new timer.
     *
     * @param threadFactory        a {@link ThreadFactory} that creates a
     *                             background {@link Thread} which is dedicated to
     *                             {@link TimerTask} execution.
     * @param tickDuration         the duration between tick
     * @param unit                 the time unit of the {@code tickDuration}
     * @param ticksPerWheel        the size of the wheel
     * @param leakDetection        {@code true} if leak detection should be enabled always,
     *                             if false it will only be enabled if the worker thread is not
     *                             a daemon thread.
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(
        ThreadFactory threadFactory,
        long tickDuration, TimeUnit unit, int ticksPerWheel, boolean leakDetection) {
        this(threadFactory, tickDuration, unit, ticksPerWheel, leakDetection, -1);
    }

    /**
     * Creates a new timer.
     *
     * @param threadFactory        a {@link ThreadFactory} that creates a
     *                             background {@link Thread} which is dedicated to
     *                             {@link TimerTask} execution.
     * @param tickDuration         the duration between tick
     * @param unit                 the time unit of the {@code tickDuration}
     * @param ticksPerWheel        the size of the wheel
     * @param leakDetection        {@code true} if leak detection should be enabled always,
     *                             if false it will only be enabled if the worker thread is not
     *                             a daemon thread.
     * @param  maxPendingTimeouts  The maximum number of pending timeouts after which call to
     *                             {@code newTimeout} will result in
     *                             {@link java.util.concurrent.RejectedExecutionException}
     *                             being thrown. No maximum pending timeouts limit is assumed if
     *                             this value is 0 or negative.
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(ThreadFactory threadFactory, long tickDuration, TimeUnit unit, int ticksPerWheel, boolean leakDetection, long maxPendingTimeouts) {

        if (threadFactory == null) {
            throw new NullPointerException("threadFactory");
        }
        /* 一个 tick 的时间单位 */
        if (unit == null) {
            throw new NullPointerException("unit");
        }
        /* 一个 tick 的时间间隔 */
        if (tickDuration <= 0) {
            throw new IllegalArgumentException("tickDuration must be greater than 0: " + tickDuration);
        }
        /* 时间轮上一轮有多少个 tick/bucket */
        if (ticksPerWheel <= 0) {
            throw new IllegalArgumentException("ticksPerWheel must be greater than 0: " + ticksPerWheel);
        }

        // Normalize ticksPerWheel to power of two and initialize the wheel.
        /* 将时间轮的大小规范化到2的n次方，这样可以用位运算来处理取模操作，提高效率 */
        wheel = createWheel(ticksPerWheel);
        /*
         * 这是一个标示符，用来快速计算任务应该呆的格子。
         * 我们知道，给定一个deadline的定时任务，其应该呆的格子 = deadline % wheel.length
         * 但是 % 操作是个相对耗时的操作，所以使用一种变通的位运算代替：
         *  因为一圈的长度为 2 的 n 次方，mask = 2^n-1后低位将全部是1，然后 deadline & mast == deadline % wheel.length
         *  java 中的 HashMap 也是使用这种处理方法
         */
        mask = wheel.length - 1;

        // Convert tickDuration to nanos.
        /* 转换时间间隔到纳秒 */
        long duration = unit.toNanos(tickDuration);

        // Prevent overflow.
        /* 防止溢出 */
        if (duration >= Long.MAX_VALUE / wheel.length) {
            throw new IllegalArgumentException(String.format("tickDuration: %d (expected: 0 < tickDuration in nanos < %d", tickDuration, Long.MAX_VALUE / wheel.length));
        }

        /* 时间间隔至少要1ms */
        if (duration < MILLISECOND_NANOS) {
            if (logger.isWarnEnabled()) {
                logger.warn("Configured tickDuration %d smaller then %d, using 1ms.", tickDuration, MILLISECOND_NANOS);
            }
            this.tickDuration = MILLISECOND_NANOS;
        } else {
            this.tickDuration = duration;
        }

        /* 创建 worker 线程，尤其可见，netty 的定时任务采用一个线程执行，模式是 MPSC */
        workerThread = threadFactory.newThread(worker);

        /* 这里默认是启动内存泄露检测：当 HashedWheelTimer 实例超过当前 cpu 可用核数 *4 的时候，将发出警告 */
        leak = leakDetection || !workerThread.isDaemon() ? leakDetector.track(this) : null;

        /* 设置最大等待任务数 */
        this.maxPendingTimeouts = maxPendingTimeouts;

        /* 限制 timer 的实例数，避免过多的timer线程反而影响性能 */
        if (INSTANCE_COUNTER.incrementAndGet() > INSTANCE_COUNT_LIMIT && WARNED_TOO_MANY_INSTANCES.compareAndSet(false, true)) {
            reportTooManyInstances();
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            // This object is going to be GCed and it is assumed the ship has sailed to do a proper shutdown. If
            // we have not yet shutdown then we want to make sure we decrement the active instance count.
            if (WORKER_STATE_UPDATER.getAndSet(this, WORKER_STATE_SHUTDOWN) != WORKER_STATE_SHUTDOWN) {
                INSTANCE_COUNTER.decrementAndGet();
            }
        }
    }

    private static HashedWheelBucket[] createWheel(int ticksPerWheel) {
        /* 处理时间轮太小或者太大造成的异常 */
        if (ticksPerWheel <= 0) {
            throw new IllegalArgumentException("ticksPerWheel must be greater than 0: " + ticksPerWheel);
        }

        if (ticksPerWheel > 1073741824) {
            throw new IllegalArgumentException("ticksPerWheel may not be greater than 2^30: " + ticksPerWheel);
        }

        /* 规范化到2的n次方 */
        ticksPerWheel = normalizeTicksPerWheel(ticksPerWheel);
        /* 创建每个bucket */
        HashedWheelBucket[] wheel = new HashedWheelBucket[ticksPerWheel];
        for (int i = 0; i < wheel.length; i ++) {
            wheel[i] = new HashedWheelBucket();
        }
        return wheel;
    }

    private static int normalizeTicksPerWheel(int ticksPerWheel) {
        int normalizedTicksPerWheel = 1;
        /* 不断地左移位直到找到大于等于时间轮大小的2的n次方出现 */
        while (normalizedTicksPerWheel < ticksPerWheel) {
            normalizedTicksPerWheel <<= 1;
        }
        return normalizedTicksPerWheel;
    }

    /**
     * Starts the background thread explicitly.  The background thread will
     * start automatically on demand even if you did not call this method.
     *
     * @throws IllegalStateException if this timer has been
     *                               {@linkplain #stop() stopped} already
     *
     * one-to-zero:
     *  每添加一个任务，就会调用这个方法
     *  启动时间轮。这个方法其实不需要显示的主动调用，因为在添加定时任务（newTimeout()方法）的时候会自动调用此方法。
     *  这个是合理的设计，因为如果时间轮里根本没有定时任务，启动时间轮也是空耗资源
     *
     */
    public void start() {
        /** 针对 worker 的状态 {@link workerState} 进行switch */
        switch (WORKER_STATE_UPDATER.get(this)) {
            /* 如果是初始化状态就标记为开始状态，并且启动 worker 线程 */
            case WORKER_STATE_INIT:
                if (WORKER_STATE_UPDATER.compareAndSet(this, WORKER_STATE_INIT, WORKER_STATE_STARTED)) {
                    /* 启动 worker 线程 */
                    workerThread.start();
                }
                break;
            case WORKER_STATE_STARTED:
                break;
            case WORKER_STATE_SHUTDOWN:
                throw new IllegalStateException("cannot be started once stopped");
            default:
                throw new Error("Invalid WorkerState");
        }

        // Wait until the startTime is initialized by the worker.
        /* 这里需要同步等待 worker 线程启动 并 完成 startTime 初始化的工作 */
        while (startTime == 0) {
            try {
                startTimeInitialized.await();
            } catch (InterruptedException ignore) {
                // Ignore - it will be ready very soon.
            }
        }
    }

    @Override
    public Set<Timeout> stop() {
        /* 判断当前线程是否是 worker 线程，stop 方法不能由TimerTask触发，否则后面的同步等待 join 操作就无法完成 */
        if (Thread.currentThread() == workerThread) {
            throw new IllegalStateException(HashedWheelTimer.class.getSimpleName() + ".stop() cannot be called from " + TimerTask.class.getSimpleName());
        }

        /* 更新 worker 的状态从 start -> shutdown */
        if (!WORKER_STATE_UPDATER.compareAndSet(this, WORKER_STATE_STARTED, WORKER_STATE_SHUTDOWN)) {
            // workerState can be 0 or 2 at this moment - let it always be 2.
            if (WORKER_STATE_UPDATER.getAndSet(this, WORKER_STATE_SHUTDOWN) != WORKER_STATE_SHUTDOWN) {
                INSTANCE_COUNTER.decrementAndGet();
                if (leak != null) {
                    boolean closed = leak.close(this);
                    assert closed;
                }
            }

            return Collections.emptySet();
        }

        /* 如果来到了这里，说明之前cas更新一切顺利 */
        try {
            boolean interrupted = false;
            /* while循环持续中断worker线程直到它醒悟它该结束了(有可能被一些耗时的操作耽误了)  通过isAlive判断worker线程是否已经结束 */
            while (workerThread.isAlive()) {
                workerThread.interrupt();
                try {
                    workerThread.join(100);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }

            /* 如果当前线程被interrupt，就设置标志位，常规操作 */
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        } finally {
            /* 减少实例数 */
            INSTANCE_COUNTER.decrementAndGet();
            if (leak != null) {
                boolean closed = leak.close(this);
                assert closed;
            }
        }
        /* 返回还没执行的定时任务 */
        return worker.unprocessedTimeouts();
    }

    @Override
    public Timeout newTimeout(TimerTask task, long delay, TimeUnit unit) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        if (unit == null) {
            throw new NullPointerException("unit");
        }

        /* 增加等待执行的定时任务数 */
        long pendingTimeoutsCount = pendingTimeouts.incrementAndGet();

        /* 如果当前队列中的任务超出了最大任务数量，则抛出拒绝异常 */
        if (maxPendingTimeouts > 0 && pendingTimeoutsCount > maxPendingTimeouts) {
            pendingTimeouts.decrementAndGet();
            throw new RejectedExecutionException("Number of pending timeouts (" + pendingTimeoutsCount + ") is greater than or equal to maximum allowed pending " + "timeouts (" + maxPendingTimeouts + ")");
        }

        /* 工作线程没有启动则启动线程 */
        start();

        // Add the timeout to the timeout queue which will be processed on the next tick.
        // During processing all the queued HashedWheelTimeouts will be added to the correct HashedWheelBucket.
        /* 计算任务的 deadline */
        long deadline = System.nanoTime() + unit.toNanos(delay) - startTime;

        // Guard against overflow.
        if (delay > 0 && deadline < 0) {
            deadline = Long.MAX_VALUE;
        }
        /* 这里定时任务不是直接加到对应的格子中，而是先加入到一个队列里，然后等到下一个tick的时候，会从队列里取出最多100000个任务加入到指定的格子中 */
        HashedWheelTimeout timeout = new HashedWheelTimeout(this, task, deadline);
        timeouts.add(timeout);
        return timeout;
    }

    /**
     * Returns the number of pending timeouts of this {@link Timer}.
     */
    public long pendingTimeouts() {
        return pendingTimeouts.get();
    }

    private static void reportTooManyInstances() {
        if (logger.isErrorEnabled()) {
            String resourceType = simpleClassName(HashedWheelTimer.class);
            logger.error("You are creating too many " + resourceType + " instances. " +
                    resourceType + " is a shared resource that must be reused across the JVM," +
                    "so that only a few instances are created.");
        }
    }

    /**
     * 该类就是定时器线程执行的 runnable
     * Worker 是时间轮的核心线程类。tick 的转动，过期任务的处理都是在这个线程中处理的。
     */
    private final class Worker implements Runnable {
        private final Set<Timeout> unprocessedTimeouts = new HashSet<Timeout>();

        /** 目前看源码发现这个值应该是 记录时间轮经过的格子数，一直递增 */
        private long tick;

        @Override
        public void run() {
            // Initialize the startTime.
            /* 设置全局定时器启动的当前时间，一旦设置后，后续就不会更改，除非重启进程 */
            startTime = System.nanoTime();
            /*
             * 由于 System.nanoTime() 可能返回0，甚至负数。并且0是一个标示符，用来判断startTime是否被初始化，
             * 所以当startTime=0的时候，重新赋值为1
             */
            if (startTime == 0) {
                // We use 0 as an indicator for the uninitialized value here, so make sure it's not 0 when initialized.
                startTime = 1;
            }

            // Notify the other threads waiting for the initialization at start().
            /*  唤醒阻塞在 start() 的线程 */
            startTimeInitialized.countDown();

            /* 只要时间轮的状态为 WORKER_STATE_STARTED，就循环的“转动”tick，循环判断响应格子中的到期任务 */
            do {
                /*
                 * waitForNextTick 方法主要是计算下次 tick 的时间, 然后 sleep 到下次 tick
                 * 返回值就是 System.nanoTime() - startTime, 也就是定时器启动以来的时间
                 */
                final long deadline = waitForNextTick();
                /* 可能溢出或者被中断的时候会返回负数, 所以小于等于0不管 */
                if (deadline > 0) {
                    /* 获取 tick 对应的格子索引，tick & mask 其实就是取模操作，只不过性能比 % 更高 */
                    int idx = (int) (tick & mask);

                    /* 移除被取消的任务 */
                    processCancelledTasks();

                    HashedWheelBucket bucket = wheel[idx];

                    /* 从任务队列中取出任务加入到对应的格子中 */
                    transferTimeoutsToBuckets();

                    /* 过期执行格子中的任务 */
                    bucket.expireTimeouts(deadline);
                    tick++;
                }
            } while (WORKER_STATE_UPDATER.get(HashedWheelTimer.this) == WORKER_STATE_STARTED);

            /* 如果上面的 while 循环退出，则说明定时器结束了 */

            // Fill the unprocessedTimeouts so we can return them from stop() method.
            /* 这里应该是时间轮停止了，清除所有格子中的任务，并加入到未处理任务列表，以供stop()方法返回 */
            for (HashedWheelBucket bucket: wheel) {
                bucket.clearTimeouts(unprocessedTimeouts);
            }
            /* 将还没有加入到格子中的待处理定时任务队列中的任务取出，如果是未取消的任务，则加入到未处理任务队列中，以供stop()方法返回 */
            for (;;) {
                HashedWheelTimeout timeout = timeouts.poll();
                if (timeout == null) {
                    break;
                }
                if (!timeout.isCancelled()) {
                    unprocessedTimeouts.add(timeout);
                }
            }
            /* 处理取消的任务 */
            processCancelledTasks();
        }

        /**
         * 新能的任务其实不是直接添加到时间环格子上的链表中，而是先发放在队列中 {@link #timeouts}
         * 在时间轮每次遍历格子的时候，会将一部分任务放在格子中，默认是一次获取 10W 个
         */
        private void transferTimeoutsToBuckets() {
            // transfer only max. 100000 timeouts per tick to prevent a thread to stale the workerThread when it just
            // adds new timeouts in a loop.
            /* 每次 tick 只处理 10w 个任务，以免阻塞 worker 线程 */
            for (int i = 0; i < 100000; i++) {
                HashedWheelTimeout timeout = timeouts.poll();
                if (timeout == null) {
                    // all processed
                    /*  如果没有任务了，直接跳出循环 */
                    break;
                }
                /* 还没有放入到格子中就取消了，直接略过 */
                if (timeout.state() == HashedWheelTimeout.ST_CANCELLED) {
                    // Was cancelled in the meantime.
                    continue;
                }
                /* 计算任务需要经过多少个tick */
                long calculated = timeout.deadline / tickDuration;
                /* 计算任务的轮数 */
                timeout.remainingRounds = (calculated - tick) / wheel.length;

                /* 如果任务在timeouts队列里面放久了, 以至于已经过了执行时间, 这个时候就使用当前tick, 也就是放到当前bucket, 此方法调用完后就会被执行. */
                final long ticks = Math.max(calculated, tick); // Ensure we don't schedule for past.
                int stopIndex = (int) (ticks & mask);

                /* 将任务加入到相应的格子中 */
                HashedWheelBucket bucket = wheel[stopIndex];
                bucket.addTimeout(timeout);
            }
        }

        /** 将取消的任务取出，并从格子中移除 */
        private void processCancelledTasks() {
            for (;;) {
                HashedWheelTimeout timeout = cancelledTimeouts.poll();
                if (timeout == null) {
                    // all processed
                    break;
                }
                try {
                    timeout.remove();
                } catch (Throwable t) {
                    if (logger.isWarnEnabled()) {
                        logger.warn("An exception was thrown while process a cancellation task", t);
                    }
                }
            }
        }

        /**
         * calculate goal nanoTime from startTime and current tick number,
         * then wait until that goal has been reached.
         * @return Long.MIN_VALUE if received a shutdown request,
         * current time otherwise (with Long.MIN_VALUE changed by +1)
         *
         * one-to-zero:
         *  sleep, 直到下次tick到来,
         *  然后值是这个定时器启动以来到现在的时长
         */
        private long waitForNextTick() {
            /* 下次 tick 的时间点, 用于计算需要 sleep 的时间 */
            long deadline = tickDuration * (tick + 1);

            for (;;) {
                /*
                 * 计算需要sleep的时间, 之所以加999999后再除10000000, 是为了保证足够的sleep时间
                 * 例如：当 deadline - currentTime = 2000002 的时候，如果不加 999999，则只睡了 2ms，
                 * 而 2ms 其实是未到达 deadline 这个时间点的，所以为了使上述情况能 sleep 足够的时间，加上999999后，会多睡1ms
                 */
                final long currentTime = System.nanoTime() - startTime;
                /* 计算-转换需要沉睡的毫秒值 */
                long sleepTimeMs = (deadline - currentTime + 999999) / 1000000;

                if (sleepTimeMs <= 0) {
                    /* 这里的意思应该是从时间轮启动到现在经过太长的时间(跨度大于292年...)，以至于让long装不下，都溢出了...对于netty的严谨，我服！ */
                    if (currentTime == Long.MIN_VALUE) {
                        return -Long.MAX_VALUE;
                    } else {
                        return currentTime;
                    }
                }

                // Check if we run on windows, as if thats the case we will need
                // to round the sleepTime as workaround for a bug that only affect
                // the JVM if it runs on windows.
                //
                // See https://github.com/netty/netty/issues/356
                /* 这里是因为windows平台的定时调度最小单位为10ms，如果不是10ms的倍数，可能会引起sleep时间不准确 */
                if (PlatformDependent.isWindows()) {
                    sleepTimeMs = sleepTimeMs / 10 * 10;
                }

                try {
                    /* 这里沉睡采用的方法参数只能支持毫秒 */
                    Thread.sleep(sleepTimeMs);
                } catch (InterruptedException ignored) {
                    /* 调用HashedWheelTimer.stop()时优雅退出 */
                    if (WORKER_STATE_UPDATER.get(HashedWheelTimer.this) == WORKER_STATE_SHUTDOWN) {
                        return Long.MIN_VALUE;
                    }
                }
            }
        }

        public Set<Timeout> unprocessedTimeouts() {
            return Collections.unmodifiableSet(unprocessedTimeouts);
        }
    }

    private static final class HashedWheelTimeout implements Timeout {

        /** 定义定时任务的 3 个状态：初始化、取消、过期 */
        private static final int ST_INIT = 0;
        private static final int ST_CANCELLED = 1;
        private static final int ST_EXPIRED = 2;

        /** 修饰的字段必须是 volatile  */
        private static final AtomicIntegerFieldUpdater<HashedWheelTimeout> STATE_UPDATER = AtomicIntegerFieldUpdater.newUpdater(HashedWheelTimeout.class, "state");

        /** 时间轮引用 */
        private final HashedWheelTimer timer;

        /** 具体到期需要执行的任务 */
        private final TimerTask task;

        private final long deadline;

        @SuppressWarnings({"unused", "FieldMayBeFinal", "RedundantFieldInitialization" })
        private volatile int state = ST_INIT;

        // remainingRounds will be calculated and set by Worker.transferTimeoutsToBuckets() before the
        // HashedWheelTimeout will be added to the correct HashedWheelBucket.
        /** 离任务执行的轮数，记录这当前任务的轮数，指针扫描一轮该值减一 */
        long remainingRounds;

        // This will be used to chain timeouts in HashedWheelTimerBucket via a double-linked-list.
        // As only the workerThread will act on it there is no need for synchronization / volatile.
        /** 双向链表结构，由于只有worker线程会访问，这里不需要synchronization / volatile */
        HashedWheelTimeout next;
        HashedWheelTimeout prev;

        // The bucket to which the timeout was added
        /** 定时任务所在的格子 */
        HashedWheelBucket bucket;

        HashedWheelTimeout(HashedWheelTimer timer, TimerTask task, long deadline) {
            this.timer = timer;
            this.task = task;
            this.deadline = deadline;
        }

        @Override
        public Timer timer() {
            return timer;
        }

        @Override
        public TimerTask task() {
            return task;
        }

        @Override
        public boolean cancel() {
            // only update the state it will be removed from HashedWheelBucket on next tick.
            /* 这里只是修改状态为ST_CANCELLED，会在下次tick时，在格子中移除 */
            if (!compareAndSetState(ST_INIT, ST_CANCELLED)) {
                return false;
            }
            // If a task should be canceled we put this to another queue which will be processed on each tick.
            // So this means that we will have a GC latency of max. 1 tick duration which is good enough. This way
            // we can make again use of our MpscLinkedQueue and so minimize the locking / overhead as much as possible.
            /* 加入到时间轮的待取消队列，并在每次tick的时候，从相应格子中移除。 */
            timer.cancelledTimeouts.add(this);
            return true;
        }

        /** 从格子中移除自身 */
        void remove() {
            HashedWheelBucket bucket = this.bucket;
            if (bucket != null) {
                bucket.remove(this);
            } else {
                timer.pendingTimeouts.decrementAndGet();
            }
        }

        public boolean compareAndSetState(int expected, int state) {
            return STATE_UPDATER.compareAndSet(this, expected, state);
        }

        public int state() {
            return state;
        }

        @Override
        public boolean isCancelled() {
            return state() == ST_CANCELLED;
        }

        @Override
        public boolean isExpired() {
            return state() == ST_EXPIRED;
        }

        /** 过期并执行任务 */
        public void expire() {
            if (!compareAndSetState(ST_INIT, ST_EXPIRED)) {
                return;
            }

            try {
                task.run(this);
            } catch (Throwable t) {
                if (logger.isWarnEnabled()) {
                    logger.warn("An exception was thrown by " + TimerTask.class.getSimpleName() + '.', t);
                }
            }
        }

        @Override
        public String toString() {
            final long currentTime = System.nanoTime();
            long remaining = deadline - currentTime + timer.startTime;

            StringBuilder buf = new StringBuilder(192)
               .append(simpleClassName(this))
               .append('(')
               .append("deadline: ");
            if (remaining > 0) {
                buf.append(remaining)
                   .append(" ns later");
            } else if (remaining < 0) {
                buf.append(-remaining)
                   .append(" ns ago");
            } else {
                buf.append("now");
            }

            if (isCancelled()) {
                buf.append(", cancelled");
            }

            return buf.append(", task: ")
                      .append(task())
                      .append(')')
                      .toString();
        }
    }

    /**
     * Bucket that stores HashedWheelTimeouts. These are stored in a linked-list like datastructure to allow easy
     * removal of HashedWheelTimeouts in the middle. Also the HashedWheelTimeout act as nodes themself and so no
     * extra object creation is needed.
     *
     * one-to-zero:
     *  HashedWheelTimeout 就是时间轮上的每一个格子类型
     *  HashedWheelBucket 用来存放 {@link HashedWheelTimeout}，结构类似于 LinkedList。
     *  提供了expireTimeouts(long deadline)方法来过期并执行格子中的定时任务
     */
    private static final class HashedWheelBucket {
        // Used for the linked-list datastructure
        /**  指向格子中任务的首尾 */
        private HashedWheelTimeout head;
        private HashedWheelTimeout tail;

        /**
         * Add {@link HashedWheelTimeout} to this bucket.
         * one-to-zero：
         *  基础的链表添加操作
         */
        public void addTimeout(HashedWheelTimeout timeout) {
            assert timeout.bucket == null;
            timeout.bucket = this;
            if (head == null) {
                head = tail = timeout;
            } else {
                tail.next = timeout;
                timeout.prev = tail;
                tail = timeout;
            }
        }

        /**
         * Expire all {@link HashedWheelTimeout}s for the given {@code deadline}.
         * one-to-zero:
         *  过期并执行格子中的到期任务，tick 到该格子的时候，worker 线程会调用这个方法，根据 deadline 和 remainingRounds 判断任务是否过期
         */
        public void expireTimeouts(long deadline) {
            HashedWheelTimeout timeout = head;

            // process all timeouts
            /* 遍历格子中的所有定时任务 */
            while (timeout != null) {
                HashedWheelTimeout next = timeout.next;
                /* 轮数 <= 0，表示定时任务到期，需要被执行 */
                if (timeout.remainingRounds <= 0) {
                    /* 移除 timeout 节点，并且返回 timeout 下一个节点 */
                    next = remove(timeout);
                    if (timeout.deadline <= deadline) {
                        /* 设置任务状态未过期，并且执行任务 */
                        timeout.expire();
                    } else {
                        // The timeout was placed into a wrong slot. This should never happen.
                        /* 如果 round 数已经为 0，deadline 却>当前格子的 deadline，说明放错格子了，这种情况应该不会出现 */
                        throw new IllegalStateException(String.format("timeout.deadline (%d) > deadline (%d)", timeout.deadline, deadline));
                    }
                } else if (timeout.isCancelled()) {
                    /* 如果任务被取消了需要移除任务 */
                    next = remove(timeout);
                } else {
                    /* 没有到期，轮数-1 */
                    timeout.remainingRounds --;
                }
                timeout = next;
            }
        }

        /**
         * 基础的链表移除 node 操作
         * 就是将当前节点 timeout 从链表中移除，然后收尾相连
         * 返回的是 timeout 下一个节点，如果为空，其实返回的就是 null
         */
        public HashedWheelTimeout remove(HashedWheelTimeout timeout) {
            HashedWheelTimeout next = timeout.next;
            // remove timeout that was either processed or cancelled by updating the linked-list
            /* 下面的两个操作则是将当前节点移除，然后拼接收尾 */
            if (timeout.prev != null) {
                timeout.prev.next = next;
            }
            if (timeout.next != null) {
                timeout.next.prev = timeout.prev;
            }

            if (timeout == head) {
                // if timeout is also the tail we need to adjust the entry too
                if (timeout == tail) {
                    tail = null;
                    head = null;
                } else {
                    head = next;
                }
            } else if (timeout == tail) {
                // if the timeout is the tail modify the tail to be the prev node.
                tail = timeout.prev;
            }
            // null out prev, next and bucket to allow for GC.
            /* 主动置空，提前 GC */
            timeout.prev = null;
            timeout.next = null;
            timeout.bucket = null;
            timeout.timer.pendingTimeouts.decrementAndGet();
            return next;
        }

        /**
         * Clear this bucket and return all not expired / cancelled {@link Timeout}s.
         */
        public void clearTimeouts(Set<Timeout> set) {
            for (;;) {
                HashedWheelTimeout timeout = pollTimeout();
                if (timeout == null) {
                    return;
                }
                if (timeout.isExpired() || timeout.isCancelled()) {
                    continue;
                }
                set.add(timeout);
            }
        }

        private HashedWheelTimeout pollTimeout() {
            HashedWheelTimeout head = this.head;
            if (head == null) {
                return null;
            }
            HashedWheelTimeout next = head.next;
            if (next == null) {
                tail = this.head =  null;
            } else {
                this.head = next;
                next.prev = null;
            }

            // null out prev and next to allow for GC.
            head.next = null;
            head.prev = null;
            head.bucket = null;
            return head;
        }
    }
}
