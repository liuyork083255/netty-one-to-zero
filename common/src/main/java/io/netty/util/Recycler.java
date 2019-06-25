/*
 * Copyright 2013 The Netty Project
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

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.util.internal.MathUtil.safeFindNextPositivePowerOfTwo;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * Light-weight object pool based on a thread-local stack.
 *
 * @param <T> the type of the pooled object
 * @author
 *
 * one-to-zero:
 *  基于 thread-local 实现的轻量级内存对象池
 *  这个类在 ChannelOutboundBuffer、PooledHeapByteBuf 和 PooledDirectByteBuf 中都有使用到。
 *
 *  Recycler 是一个抽象类，向外部提供了两个公共方法 get 和 recycle 分别用于从对象池中获取对象和回收对象；
 *  另外还提供了一个 protected 的抽象方法 newObject，newObject 用于在内存池中没有可用对象的时候创建新的对象，
 *  由用户自己实现，Recycler 以泛型参数的形式让用户传入具体要池化的对象类型
 *
 * Recycler 内部主要包含三个核心组件，各个组件负责对象池实现的具体部分
 *  1 {@link Handle}
 *  2 {@link WeakOrderQueue}
 *  3 {@link Stack}
 *
 *
 * 使用对象池好处：
 *      参考：https://www.cnblogs.com/java-zhao/p/5180492.html
 *      因为在创建对象的时候，JVM 做了很多操作，比如：验证，内存分配，GC
 *      对象进行重用，不会大量创建对象，减少 GC 压力
 *
 * 分析 netty 设计这个对象池的目的：
 *  对象池简单说就是将对象实例缓存起来供后续分配使用来避免瞬时大量小对象反复生成和消亡造成分配和GC压力。
 *  在设计时可以简单的做如下推导：
 *      1 首先考虑单线程的情况，此时简单的构建一个List容器，对象回收时放入list，分配时从list取出即可。这种方式非常容易就构建了一个单线程的对象池实现。
 *      2 接着考虑多线程的情况，分配时与单线程情况相同，从当前线程的List容器中取出一个对象分配即可。
 *        回收时却遇到了麻烦，可能对象回收时的线程和分配时线程一致，那情况与单线程一致。如果不一致，此时存在不同的策略选择。
 *          策略一: 将对象回收至分配线程的List容器中
 *          策略二: 将对象回收至本线程的list容器，当成本线程的对象使用
 *          策略三: 将对象暂存于本线程，在后续合适时机归还至分配线程。
 *
 *      每一种策略均各有特点，
 *          策略一，需要采用MPSC队列（或类似结构）来作为存储容器，因为会有多个线程并发回收对象的情况。如果采用MPSC队列，伴随着不停的分配和回收，MPSC队列内部的节点也是不停的生成和消亡，变相降低了内存池的效果。
 *          策略二，如果线程呈现明显的分配和回收分工则会导致缓存池失去作用。因为分配线程总是无法回收到对象，进而分配时都是新生成对象；而回收线程因为很少分配对象，导致回收的对象超出上限被抛弃也很少得到使用。
 *          策略三，由于对象暂存于本线程，可以避开在回收时的并发竞争。也不会出现策略2导致的失效问题。Netty采取的就是策略三。
 *
 *  Netty 没有采用全局的缓存池，也就是避免线程并发获取和回收降低了性能 !!!
 *
 */
public abstract class Recycler<T> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(Recycler.class);

    /**
     * one-to-zero:
     *  表示一个不需要回收的包装对象，用于在禁止使用Recycler功能时进行占位的功能
     *  仅当 io.netty.recycler.maxCapacityPerThread <=0 时用到
     */
    @SuppressWarnings("rawtypes")
    private static final Handle NOOP_HANDLE = new Handle() {
        @Override
        public void recycle(Object object) {
            // NOOP
        }
    };
    /**
     * one-to-zero:
     *  唯一ID生成器
     *  用在两处：
     *      1、当前线程ID，即哪一个线程第一次创建 Recycler 对象，就会为其该线程指定一个 ID，采用 {@link AtomicInteger#getAndIncrement()} 方式
     *      2、WeakOrderQueue 的 id，我们知道别的线程X 释放线程A 的对象，那么第一次 WeakOrderQueue 是不存在的，需要新建，那么新建就会初始化这个ID {@link Recycler.WeakOrderQueue#id}
     */
    private static final AtomicInteger ID_GENERATOR = new AtomicInteger(Integer.MIN_VALUE);

    /**
     * one-to-zero:
     *  static变量, 生成并获取一个唯一id.
     *  用于{@link Recycler.Stack#pushNow} 中的 item.recycleId 和 item.lastRecycleId 的设定
     */
    private static final int OWN_THREAD_ID = ID_GENERATOR.getAndIncrement();

    /**
     * one-to-zero:
     *  每个 Stack 默认的最大容量
     *  注意：
     *      1、当 io.netty.recycler.maxCapacityPerThread<=0 时，禁用回收功能
     *      2、Recycler 中有且只有两个地方存储 DefaultHandle 对象（Stack和Link），
     *      所以：最多可存储 MAX_CAPACITY_PER_THREAD + LINK_CAPACITY
     *
     *  实际上，在 netty 中，Recycler 提供了两种设置属性的方式
     *      第一种：-Dio.netty.recycler.ratio 等 jvm 启动参数方式
     *      第二种：Recycler(int maxCapacityPerThread) 构造器传入方式
     */
    private static final int DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD = 4 * 1024; // Use 4k instances as default.
    private static final int DEFAULT_MAX_CAPACITY_PER_THREAD;
    private static final int INITIAL_CAPACITY;
    private static final int MAX_SHARED_CAPACITY_FACTOR;
    private static final int MAX_DELAYED_QUEUES_PER_THREAD;
    private static final int LINK_CAPACITY;
    private static final int RATIO;

    static {
        // In the future, we might have different maxCapacity for different object types.
        // e.g. io.netty.recycler.maxCapacity.writeTask
        //      io.netty.recycler.maxCapacity.outboundBuffer
        int maxCapacityPerThread = SystemPropertyUtil.getInt("io.netty.recycler.maxCapacityPerThread",
                SystemPropertyUtil.getInt("io.netty.recycler.maxCapacity", DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD));
        if (maxCapacityPerThread < 0) {
            maxCapacityPerThread = DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD;
        }

        DEFAULT_MAX_CAPACITY_PER_THREAD = maxCapacityPerThread;

        /*
         * 最大可共享的容量因子。
         * 最大可共享的容量 = maxCapacity / maxSharedCapacityFactor，maxSharedCapacityFactor 默认为2
         */
        MAX_SHARED_CAPACITY_FACTOR = max(2,
                SystemPropertyUtil.getInt("io.netty.recycler.maxSharedCapacityFactor",
                        2));

        /*
         * 每个线程可拥有多少个 WeakOrderQueue，默认为 2 * cpu 核数
         * 实际上就是当前线程的 Map<Stack<?>, WeakOrderQueue> 的 size 最大值
         */
        MAX_DELAYED_QUEUES_PER_THREAD = max(0,
                SystemPropertyUtil.getInt("io.netty.recycler.maxDelayedQueuesPerThread",
                        // We use the same value as default EventLoop number
                        NettyRuntime.availableProcessors() * 2));

        /*
         * WeakOrderQueue 中的 Link 中的数组 DefaultHandle<?>[] elements 容量，默认为16，
         * 当一个 Link 中的 DefaultHandle 元素达到16个时，会新创建一个 Link 进行存储，这些 Link 组成链表，当然
         * 所有的 Link 加起来的容量要 <= 最大可共享容量。
         */
        LINK_CAPACITY = safeFindNextPositivePowerOfTwo(
                max(SystemPropertyUtil.getInt("io.netty.recycler.linkCapacity", 16), 16));

        // By default we allow one push to a Recycler for each 8th try on handles that were never recycled before.
        // This should help to slowly increase the capacity of the recycler while not be too sensitive to allocation
        // bursts.
        /*
         * 回收因子，默认为8。
         * 即默认每8个对象，允许回收一次，直接扔掉7个，可以让 recycler 的容量缓慢的增大，避免爆发式的请求
         */
        RATIO = safeFindNextPositivePowerOfTwo(SystemPropertyUtil.getInt("io.netty.recycler.ratio", 8));

        if (logger.isDebugEnabled()) {
            if (DEFAULT_MAX_CAPACITY_PER_THREAD == 0) {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: disabled");
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: disabled");
                logger.debug("-Dio.netty.recycler.linkCapacity: disabled");
                logger.debug("-Dio.netty.recycler.ratio: disabled");
            } else {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: {}", DEFAULT_MAX_CAPACITY_PER_THREAD);
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: {}", MAX_SHARED_CAPACITY_FACTOR);
                logger.debug("-Dio.netty.recycler.linkCapacity: {}", LINK_CAPACITY);
                logger.debug("-Dio.netty.recycler.ratio: {}", RATIO);
            }
        }
        /*
         * 每个 Stack 默认的初始容量，默认为256
         * 后续根据需要进行扩容，直到 <= DEFAULT_MAX_CAPACITY_PER_THREAD
         */
        INITIAL_CAPACITY = min(DEFAULT_MAX_CAPACITY_PER_THREAD, 256);
    }

    private final int maxCapacityPerThread;
    private final int maxSharedCapacityFactor;
    private final int ratioMask;
    private final int maxDelayedQueuesPerThread;

    /**
     * one-to-zero:
     *  1、每个 Recycler 对象都有一个 threadLocal
     *      原因：因为一个 Stack 要指明存储的对象泛型T，而不同的 Recycler<T> 对象的 T 可能不同，所以此处的 FastThreadLocal 是对象级别
     *  2、每条线程都有一个Stack<T>对象
     *  由于 threadLocal 是对象级别的，所以一个线程可能拥有多个 Stack<T>，只要 T 是不同的类型
     *  当首次调用 threadLocal.get() 的时候，就会触发 initialValue 方法
     */
    private final FastThreadLocal<Stack<T>> threadLocal = new FastThreadLocal<Stack<T>>() {
        @Override
        protected Stack<T> initialValue() {
            return new Stack<T>(Recycler.this, Thread.currentThread(), maxCapacityPerThread, maxSharedCapacityFactor,
                    ratioMask, maxDelayedQueuesPerThread);
        }

        @Override
        protected void onRemoval(Stack<T> value) {
            // Let us remove the WeakOrderQueue from the WeakHashMap directly if its safe to remove some overhead
            if (value.threadRef.get() == Thread.currentThread()) {
               if (DELAYED_RECYCLED.isSet()) {
                   DELAYED_RECYCLED.get().remove(value);
               }
            }
        }
    };

    protected Recycler() {
        this(DEFAULT_MAX_CAPACITY_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread) {
        this(maxCapacityPerThread, MAX_SHARED_CAPACITY_FACTOR);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor) {
        this(maxCapacityPerThread, maxSharedCapacityFactor, RATIO, MAX_DELAYED_QUEUES_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor,
                       int ratio, int maxDelayedQueuesPerThread) {
        ratioMask = safeFindNextPositivePowerOfTwo(ratio) - 1;
        if (maxCapacityPerThread <= 0) {
            this.maxCapacityPerThread = 0;
            this.maxSharedCapacityFactor = 1;
            this.maxDelayedQueuesPerThread = 0;
        } else {
            this.maxCapacityPerThread = maxCapacityPerThread;
            this.maxSharedCapacityFactor = max(1, maxSharedCapacityFactor);
            this.maxDelayedQueuesPerThread = max(0, maxDelayedQueuesPerThread);
        }
    }

    /**
     * one-to-zero:
     *  向外部提供可以复用的对象，是从对象池获取的
     */
    @SuppressWarnings("unchecked")
    public final T get() {
        /*
         * 如果maxCapacityPerThread == 0，禁止回收功能
         * 创建一个对象，其Recycler.Handle<User> handle属性为 NOOP_HANDLE，该对象的 recycle(Object object) 不做任何事情，即不做回收
         */
        if (maxCapacityPerThread == 0) {
            return newObject((Handle<T>) NOOP_HANDLE);
        }
        /*
         * 从当前线程中获取 stack
         * 如果是第一次调用 stack，那么会初始化创建 Stack
         */
        Stack<T> stack = threadLocal.get();

        /*
         * 1 先看看 stack 数组中是否有可用对象，如果有则直接返回
         * 2 如果没有则遍历 stack 在别的线程中的 WeakOrderQueue，有则将对象放入 stack 中并返回，没有找到则返回 null
         */
        DefaultHandle<T> handle = stack.pop();
        if (handle == null) {
            /* 创建 DefaultHandle 对象 */
            handle = stack.newHandle();
            /**
             *  调用用户自己实现的 {@link #newObject(Handle)} 创建 T 对象，然后将这个 T 对象赋给 handler 的 value 属性
             */
            handle.value = newObject(handle);
        }
        return (T) handle.value;
    }

    /**
     * @deprecated use {@link Handle#recycle(Object)}.
     */
    @Deprecated
    public final boolean recycle(T o, Handle<T> handle) {
        if (handle == NOOP_HANDLE) {
            return false;
        }

        DefaultHandle<T> h = (DefaultHandle<T>) handle;
        if (h.stack.parent != this) {
            return false;
        }

        h.recycle(o);
        return true;
    }

    final int threadLocalCapacity() {
        return threadLocal.get().elements.length;
    }

    final int threadLocalSize() {
        return threadLocal.get().size;
    }

    /**
     * one-to-zero:
     *  用户唯一需要实现的抽象方法
     *  创建自己需要的缓存对象类型
     */
    protected abstract T newObject(Handle<T> handle);

    /**
     * one-to-zero:
     *  Handle 主要提供一个 recycle 接口，用于提供对象回收的具体实现
     *  默认实现 {@link DefaultHandle}
     */
    public interface Handle<T> {
        /**
         * one-to-zero:
         *  回收对象
         *  Note:
         *      由于这是内存池的实现，所以回收不是交给 GC 处理，而是缓存起来。
         */
        void recycle(T object);
    }

    /**
     * one-to-zero:
     *  每个 Handle 关联一个 value 字段，用于存放具体的池化对象
     *  Note:
     *      在对象池中，所有的池化对象都被这个 Handle 包装，Handle 是对象池管理的基本单位。
     *      另外 Handle 指向着对应的 Stack，对象存储也就是 Handle 存储的具体地方由 Stack 维护和管理。
     *
     */
    static final class DefaultHandle<T> implements Handle<T> {

        /**
         * pushNow() = OWN_THREAD_ID
         * 在 pushLater 中的 add(DefaultHandle handle) 操作中 == id（当前的WeakOrderQueue的唯一ID）
         * 在 poll()中置位0
         */
        private int lastRecycledId;

        /**
         * 只有在 pushNow() 中会设置值 OWN_THREAD_ID
         * 在 poll() 中置位0
         */
        private int recycleId;

        /**
         * 标记是否已经被回收：
         * 该值仅仅用于控制是否执行 (++handleRecycleCount & ratioMask) != 0 这段逻辑，而不会用于阻止重复回收的操作，
         * 重复回收的操作由 item.recycleId | item.lastRecycledId 来阻止
         */
        boolean hasBeenRecycled;

        /* handler 指向的 stack */
        private Stack<?> stack;
        /* 每个Handle关联一个value字段，用于存放具体的池化对象 */
        private Object value;

        DefaultHandle(Stack<?> stack) {
            this.stack = stack;
        }

        @Override
        public void recycle(Object object) {
            if (object != value) {
                throw new IllegalArgumentException("object does not belong to handle");
            }

            Stack<?> stack = this.stack;
            if (lastRecycledId != recycleId || stack == null) {
                throw new IllegalStateException("recycled already");
            }

            /* 此时的 this 是被回收的 DefaultHandler */
            stack.push(this);
        }
    }

    /**
     * one-to-zero:
     *  Recycler 类（而不是每一个 Recycler 对象）都有一个 DELAYED_RECYCLED
     *  FastThreadLocal<Map<Stack<?>, WeakOrderQueue>> 是类成员变量，所有线程共享该对象获取自己对象的 Map<Stack<?>, WeakOrderQueue>，即每一个线程
     *  拥有一个 Map<Stack<?>, WeakOrderQueue> Map 对象
     *
     *  它是一个 stack -> WeakOrderQueue 映射，每个 stack 会映射到一个 WeakOrderQueue，
     *  这个 WeakOrderQueue 是该 stack 关联的其它线程 WeakOrderQueue 链表的 head WeakOrderQueue。
     *  当其它线程回收对象到该 stack 时会创建1个 WeakOrderQueue 并加到 stack 的 WeakOrderQueue 链表中。
     *
     * 每一个线程会对应一个 stack，每一个 stack 都是有自己的存储数组，不过这个存储都是自己线程释放对象的时候，才会存储在这里
     * 如果是自己的对象，这个对象放在别的线程中使用，那么别的线程就应该负责释放，这个时候释放后就不是存储在 stack 数组中，而是在 WeakOrderQueue
     *
     */
    private static final FastThreadLocal<Map<Stack<?>, WeakOrderQueue>> DELAYED_RECYCLED =
            new FastThreadLocal<Map<Stack<?>, WeakOrderQueue>>() {
        @Override
        protected Map<Stack<?>, WeakOrderQueue> initialValue() {
            return new WeakHashMap<Stack<?>, WeakOrderQueue>();
        }
    };

    // a queue that makes only moderate guarantees about visibility: items are seen in the correct order,
    // but we aren't absolutely guaranteed to ever see anything at all, thereby keeping the queue cheap to maintain

    /**
     * one-to-zero:
     *  WeakOrderQueue 的功能可以由两个接口体现，add 和 transfer;
     *  add:        add 用于将 handler（对象池管理的基本单位）放入队列
     *  transfer:   transfer 用于向 stack 输入可以被重复使用的对象。
     *
     *  目前可以理解：
     *      WeakOrderQueue 就是保存别的线程释放的对象，因为当前线程释放会直接放在 stack 中
     */
    private static final class WeakOrderQueue {

        /**
         * 如果 DELAYED_RECYCLED 中的 key-value 对已经达到了 maxDelayedQueues，
         * 对于后续的Stack，其对应的 WeakOrderQueue 设置为 DUMMY，
         * 后续如果检测到 DELAYED_RECYCLED 中对应的 Stack 的 value 是 WeakOrderQueue.DUMMY 时，直接返回，不做存储操作
         */
        static final WeakOrderQueue DUMMY = new WeakOrderQueue();

        // Let Link extend AtomicInteger for intrinsics. The Link itself will be used as writerIndex.
        @SuppressWarnings("serial")
        static final class Link extends AtomicInteger {
            private final DefaultHandle<?>[] elements = new DefaultHandle[LINK_CAPACITY];

            private int readIndex;
            Link next;
        }

        // This act as a place holder for the head Link but also will reclaim space once finalized.
        // Its important this does not hold any reference to either Stack or WeakOrderQueue.

        /**
         * Head 仅仅作为 head-Link 的占位符，仅用于 ObjectCleaner 回收操作
         */
        static final class Head {
            private final AtomicInteger availableSharedCapacity;

            /**
             * 指定读操作的 Link 节点，
             *  eg. Head -> Link1 -> Link2
             *  假设此时的读操作在 Link2 上进行时，则此处的 link == Link2，见 transfer(Stack dst),
             *  实际上此时 Link1 已经被读完了，Link1 变成了垃圾
             *  （一旦一个Link的读指针指向了最后，则该 Link 不会被重复利用，而是被 GC 掉，之后回收空间，新建 Link 再进行操作）
             */
            Link link;

            Head(AtomicInteger availableSharedCapacity) {
                this.availableSharedCapacity = availableSharedCapacity;
            }

            /**
             * 在该对象被真正的回收前，执行该方法
             * 循环释放当前的 WeakOrderQueue 中的 Link 链表所占用的所有共享空间 availableSharedCapacity，
             * 如果不释放，否则该值将不会增加，影响后续的 Link 的创建
             */
            /// TODO: In the future when we move to Java9+ we should use java.lang.ref.Cleaner.
            @Override
            protected void finalize() throws Throwable {
                try {
                    super.finalize();
                } finally {
                    Link head = link;
                    link = null;
                    while (head != null) {
                        reclaimSpace(LINK_CAPACITY);
                        Link next = head.next;
                        // Unlink to help GC and guard against GC nepotism.
                        head.next = null;
                        head = next;
                    }
                }
            }

            /**
             * one-to-zero:
             *  回收空间
             */
            void reclaimSpace(int space) {
                assert space >= 0;
                availableSharedCapacity.addAndGet(space);
            }

            /**
             * 这里可以理解为一次内存的批量分配，每次从 availableSharedCapacity 中分配 space 个大小的内存。
             * 如果一个 Link 中不是放置一个 DefaultHandle[]，而是只放置一个 DefaultHandle，那么此处的 space==1，这样的话就需要频繁的进行内存分配
             */
            boolean reserveSpace(int space) {
                return reserveSpace(availableSharedCapacity, space);
            }

            /**
             * one-to-zero:
             *  在剩余的 availableSharedCapacity 大小中分配 space 个
             */
            static boolean reserveSpace(AtomicInteger availableSharedCapacity, int space) {
                assert space >= 0;
                for (;;) {
                    /* cas + 无限重试进行 分配 */
                    int available = availableSharedCapacity.get();
                    /* 如果剩余 10 个空间，结果 space 要求分配 20，自然分配失败 */
                    if (available < space) {
                        return false;
                    }
                    /*
                     * 注意：这里使用的是 AtomicInteger，当这里的 availableSharedCapacity 发生变化时，
                     * 实际上就是改变的 stack.availableSharedCapacity 的 int value 属性的值
                     * 分配成功之后，availableSharedCapacity 会减小
                     */
                    if (availableSharedCapacity.compareAndSet(available, available - space)) {
                        return true;
                    }
                }
            }
        }

        // chain of data items
        /**
         * 在初始化 WeakOrderQueue 的时候，head 和 tail 其实都是指向同一个 Link
         */
        private final Head head;
        /**
         * WeakOrderQueue 被创建的时候被初始化 tail
         * tail 被赋值为一个新建的 Link {@link Recycler.WeakOrderQueue#WeakOrderQueue(Stack, Thread)}
         *
         * 在初始化 WeakOrderQueue 的时候，head 和 tail 其实都是指向同一个 Link
         */
        private Link tail;
        // pointer to another queue of delayed items for the same stack
        private WeakOrderQueue next;

        /**
         * 1、why WeakReference ? 与 Stack 相同。
         * 2、作用是在 poll 的时候，如果 owner 不存在了，则需要将该线程所包含的 WeakOrderQueue 的元素释放，然后从链表中删除该 Queue。
         */
        private final WeakReference<Thread> owner;

        /**
         * WeakOrderQueue 的唯一标记
         */
        private final int id = ID_GENERATOR.getAndIncrement();

        /**
         * 这个构造方法其实就是为了 {@link Recycler.WeakOrderQueue#DUMMY} 用的
         */
        private WeakOrderQueue() {
            owner = null;
            head = new Head(null);
        }

        /**
         * 在初始化 WeakOrderQueue 的时候，head 和 tail 其实都是指向同一个 Link
         */
        private WeakOrderQueue(Stack<?> stack, Thread thread) {
            /* 创建有效 Link 节点，恰好是尾节点 */
            tail = new Link();

            // Its important that we not store the Stack itself in the WeakOrderQueue as the Stack also is used in
            // the WeakHashMap as key. So just store the enclosed AtomicInteger which should allow to have the
            // Stack itself GCed.
            /* 创建 Link 链表头节点，只是占位符 */
            head = new Head(stack.availableSharedCapacity);
            head.link = tail;
            owner = new WeakReference<Thread>(thread);
        }

        /**
         * 创建 WeakOrderQueue
         */
        static WeakOrderQueue newQueue(Stack<?> stack, Thread thread) {
            /* 创建 WeakOrderQueue */
            final WeakOrderQueue queue = new WeakOrderQueue(stack, thread);
            // Done outside of the constructor to ensure WeakOrderQueue.this does not escape the constructor and so
            // may be accessed while its still constructed.

            /* 将该 queue 赋值给 stack 的 head 属性 */
            stack.setHead(queue);

            return queue;
        }

        private void setNext(WeakOrderQueue next) {
            assert next != this;
            this.next = next;
        }

        /**
         * Allocate a new {@link WeakOrderQueue} or return {@code null} if not possible.
         * one-to-zero:
         *  判断是否还可以创建 WeakOrderQueue，不可以则直接返回 null
         */
        static WeakOrderQueue allocate(Stack<?> stack, Thread thread) {
            // We allocated a Link so reserve the space
            return Head.reserveSpace(stack.availableSharedCapacity, LINK_CAPACITY) ? newQueue(stack, thread) : null;
        }

        /**
         * one-to-zero:
         *  add 用于将 handler（对象池管理的基本单位）放入队列
         */
        void add(DefaultHandle<?> handle) {
            /* 记录回收这个 handle 的 WeakOrderQueue 唯一标识 */
            handle.lastRecycledId = id;

            Link tail = this.tail;
            int writeIndex;
            /*
             * 判断一个 Link 对象是否已经满了：
             * 如果没满，直接添加；
             * 如果已经满了，创建一个新的 Link 对象，之后重组 Link 链表，然后添加元素的末尾的 Link（除了这个 Link，前边的 Link 全部已经满了）
             */
            if ((writeIndex = tail.get()) == LINK_CAPACITY) {
                if (!head.reserveSpace(LINK_CAPACITY)) {
                    // Drop it.
                    return;
                }
                // We allocate a Link so reserve the space
                /*
                 * 此处创建一个Link，会将该 Link 作为新的 tail-Link，之前的 tail-Link 已经满了，成为正常的Link了。重组 Link 链表
                 * 之前是 HEAD -> tail-Link，重组后 HEAD -> 之前的tail-Link -> 新的tail-Link
                 */
                this.tail = tail = tail.next = new Link();

                writeIndex = tail.get();
            }
            tail.elements[writeIndex] = handle;
            /*
             * 如果使用者在将 DefaultHandle 对象压入队列后，将 Stack 设置为 null，
             * 但是此处的 DefaultHandle 是持有 stack 的强引用的，则 Stack 对象无法回收；
             * 而且由于此处 DefaultHandle 是持有 stack 的强引用，WeakHashMap 中对应 stack 的 WeakOrderQueue 也无法被回收掉了，导致内存泄漏。
             */
            handle.stack = null;
            // we lazy set to ensure that setting stack to null appears before we unnull it in the owning thread;
            // this also means we guarantee visibility of an element in the queue if we see the index updated
            /* tail本身继承于AtomicInteger，所以此处直接对tail进行+1操作 */
            tail.lazySet(writeIndex + 1);
        }

        boolean hasFinalData() {
            return tail.readIndex != tail.get();
        }

        /**
         * one-to-zero:
         *  transfer 用于向 stack 输入可以被重复使用的对象
         *  也就是将别的线程中的 WeakOrderQueue 对象中可重用的 handle 转移到所属的 stack 中
         *
         *  true:   表示有转移成功的
         *  false:  表示没有从 queue 转移数据到 stack
         */
        // transfer as many items as we can from this queue to the stack, returning true if any were transferred
        @SuppressWarnings("rawtypes")
        boolean transfer(Stack<?> dst) {
            /* 寻找第一个Link（Head不是Link） */
            Link head = this.head.link;
            /* head == null，表示只有Head一个节点，没有存储数据的节点，直接返回 */
            if (head == null) {
                return false;
            }
            /*
             * 如果第一个 Link 节点的 readIndex 索引已经到达 该 Link 对象的 DefaultHandle[] 的尾部，
             * 则判断当前的 Link 节点的下一个节点是否为 null，如果为 null，说明已经达到了 Link 链表尾部，直接返回，
             * 否则，将当前的 Link 节点的下一个 Link 节点赋值给 head 和 this.head.link，进而对下一个 Link 节点进行操作
             */
            if (head.readIndex == LINK_CAPACITY) {
                if (head.next == null) {
                    return false;
                }
                this.head.link = head = head.next;
            }

            /* 获取 Link 节点的 readIndex, 即当前的 Link 节点的第一个有效元素的位置 */
            final int srcStart = head.readIndex;
            /* 获取 Link 节点的 writeIndex，即当前的 Link 节点的最后一个有效元素的位置 */
            int srcEnd = head.get();
            /* 计算 Link 节点中可以被转移的元素个数 */
            final int srcSize = srcEnd - srcStart;
            if (srcSize == 0) {
                return false;
            }

            /* 获取转移元素的目的地 Stack 中当前的元素个数 */
            final int dstSize = dst.size;
            /* 计算期盼的容量 */
            final int expectedCapacity = dstSize + srcSize;

            /*
             * 如果 expectedCapacity 大于目的地 Stack 的长度
             * 1、对目的地 Stack 进行扩容
             * 2、计算 Link 中最终的可转移的最后一个元素的下标
             */
            if (expectedCapacity > dst.elements.length) {
                final int actualCapacity = dst.increaseCapacity(expectedCapacity);
                srcEnd = min(srcStart + actualCapacity - dstSize, srcEnd);
            }

            if (srcStart != srcEnd) {
                /* 获取 Link 节点的 DefaultHandle[] */
                final DefaultHandle[] srcElems = head.elements;
                /* 获取目的地 Stack 的 DefaultHandle[] */
                final DefaultHandle[] dstElems = dst.elements;
                /* dst 数组的大小，会随着元素的迁入而增加，如果最后发现没有增加，那么表示没有迁移成功任何一个元素 */
                int newDstSize = dstSize;
                for (int i = srcStart; i < srcEnd; i++) {
                    DefaultHandle element = srcElems[i];
                    /* 设置 element.recycleId 或者 进行防护性判断 */
                    if (element.recycleId == 0) {
                        element.recycleId = element.lastRecycledId;
                    } else if (element.recycleId != element.lastRecycledId) {
                        throw new IllegalStateException("recycled already");
                    }
                    /* 置空 Link 节点的 DefaultHandle[i] */
                    srcElems[i] = null;
                    /* 扔掉放弃 7/8 的元素 */
                    if (dst.dropHandle(element)) {
                        // Drop the object.
                        continue;
                    }
                    /* 将可转移成功的 DefaultHandle 元素的 stack 属性设置为目的地 Stack */
                    element.stack = dst;
                    /* 将 DefaultHandle 元素转移到目的地 Stack 的 DefaultHandle[newDstSize ++]中 */
                    dstElems[newDstSize ++] = element;
                }

                if (srcEnd == LINK_CAPACITY && head.next != null) {
                    // Add capacity back as the Link is GCed.
                    this.head.reclaimSpace(LINK_CAPACITY);
                    /*
                     * 将 Head 指向下一个 Link，也就是将当前的 Link 给回收掉了
                     * 假设之前为 Head -> Link1 -> Link2，回收之后为 Head -> Link2
                     */
                    this.head.link = head.next;
                }
                /* 重置 readIndex */
                head.readIndex = srcEnd;
                /* 表示没有被回收任何一个对象，直接返回 */
                if (dst.size == newDstSize) {
                    return false;
                }
                /* 将新的 newDstSize 赋值给目的地 Stack 的 size */
                dst.size = newDstSize;
                return true;
            } else {
                // The destination stack is full already.
                return false;
            }
        }
    }

    /**
     * one-to-zero:
     *  Stack 具体维护着对象池数据，向 Recycler 提供 push 和 pop 两个主要访问接口;
     *      pop 用于从内部弹出一个可被重复使用的对象;
     *      push 用于回收以后可以重复使用的对象。
     */
    static final class Stack<T> {

        // we keep a queue of per-thread queues, which is appended to once only, each time a new thread other
        // than the stack owner recycles: when we run out of items in our stack we iterate this collection
        // to scavenge those that can be reused. this permits us to incur minimal thread synchronisation whilst
        // still recycling all items.
        final Recycler<T> parent;

        // We store the Thread in a WeakReference as otherwise we may be the only ones that still hold a strong
        // Reference to the Thread itself after it died because DefaultHandle will hold a reference to the Stack.
        //
        // The biggest issue is if we do not use a WeakReference the Thread may not be able to be collected at all if
        // the user will store a reference to the DefaultHandle somewhere and never clear this reference (or not clear
        // it in a timely manner).
        /**
         * 该 Stack 所属的线程
         *      why WeakReference?
         * 假设该线程对象在外界已经没有强引用了，那么实际上该线程对象就可以被回收了。但是如果此处用的是强引用，那么虽然外界不再对该线程有强引用，
         * 但是该 stack 对象还持有强引用（假设用户存储了 DefaultHandle 对象，然后一直不释放，而 DefaultHandle 对象又持有 stack 引用），导致该线程对象无法释放。
         */
        final WeakReference<Thread> threadRef;

        /**
         * 可用的共享内存大小，默认为 maxCapacity/maxSharedCapacityFactor = 4k/2 = 2k = 2048
         * 假设当前的 Stack 是线程A 的，则其他线程B~X 等去回收线程A 创建的对象时，可回收最多A创建的多少个对象
         * 注意：那么实际上线程A 创建的对象最终最多可以被回收 maxCapacity + availableSharedCapacity 个，默认为 6k 个
         *
         * why AtomicInteger?
         *  当线程B 和线程C 同时创建线程A 的 WeakOrderQueue 的时候，会同时分配内存，需要同时操作 availableSharedCapacity
         * 具体见：WeakOrderQueue.allocate
         */
        final AtomicInteger availableSharedCapacity;

        /**
         * DELAYED_RECYCLED 中最多可存储的 {Stack，WeakOrderQueue} 键值对个数
         */
        final int maxDelayedQueues;

        /* 线程栈缓存对象的最大个数 默认最大为4k，4096 */
        private final int maxCapacity;

        /**
         * 默认为8-1=7，即2^3-1，控制每8个元素只有一个可以被 recycle，其余7个被扔掉
         */
        private final int ratioMask;

        /* 保存着 stack 中缓存的对象 */
        private DefaultHandle<?>[] elements;

        /**
         * elements 中的元素个数，同时也可作为操作数组的下标
         * 数组只有 elements.length 来计算数组容量的函数，没有计算当前数组中的元素个数的函数，所以需要我们去记录，不然需要每次都去计算
         */
        private int size;

        /**
         * 每有一个元素将要被回收, 则该值+1，例如第一个被回收的元素的 handleRecycleCount = handleRecycleCount + 1 =0
         * 与 ratioMask 配合，用来决定当前的元素是被回收还是被 drop。
         * 例如 ++handleRecycleCount & ratioMask（7），其实相当于 ++handleRecycleCount % 8，
         * 则当 ++handleRecycleCount = 0/8/16/...时，元素被回收，其余的元素直接被 drop
         */
        private int handleRecycleCount = -1; // Start with -1 so the first one will be recycled.

        /**
         * cursor：当前操作的 WeakOrderQueue
         * prev：cursor的前一个 WeakOrderQueue
         */
        private WeakOrderQueue cursor, prev;

        /**
         * 该值是当线程B 回收线程A 创建的对象时，线程B 会为线程A 的 Stack 对象创建一个 WeakOrderQueue 对象，
         * 该 WeakOrderQueue 指向这里的 head，用于后续线程A 对象的查找操作
         *  Q: why volatile?
         *  A: 假设线程A 正要读取对象X，此时需要从其他线程的 WeakOrderQueue 中读取，假设此时线程B 正好创建 Queue，
         *     并向 Queue 中放入一个对象X；假设恰好次 Queue 就是线程A 的 Stack 的 head
         *  使用 volatile 可以立即读取到该 queue。
         *
         * 对于 head 的设置，具有同步问题。具体见此处的 volatile 和 synchronized void setHead(WeakOrderQueue queue)，防止重排序
         * 见 {@link Stack#setHead(WeakOrderQueue)}
         */
        private volatile WeakOrderQueue head;

        Stack(Recycler<T> parent, Thread thread, int maxCapacity, int maxSharedCapacityFactor,
              int ratioMask, int maxDelayedQueues) {
            this.parent = parent;
            threadRef = new WeakReference<Thread>(thread);
            this.maxCapacity = maxCapacity;
            availableSharedCapacity = new AtomicInteger(max(maxCapacity / maxSharedCapacityFactor, LINK_CAPACITY));
            elements = new DefaultHandle[min(INITIAL_CAPACITY, maxCapacity)];
            this.ratioMask = ratioMask;
            this.maxDelayedQueues = maxDelayedQueues;
        }

        // Marked as synchronized to ensure this is serialized.
        /**
         * 假设线程B 和线程C 同时回收线程A 的对象时，有可能会同时 newQueue，就可能同时 setHead，所以这里需要加锁
         * 以 head==null 的时候为例，
         * 加锁：
         *      线程B 先执行，则 head = 线程B 的 queue；
         *      之后线程C 执行，此时将当前的 head 也就是线程B的 queue 作为线程C 的 queue 的 next，组成链表，之后设置 head 为线程C 的 queue
         * 不加锁：
         *      线程B 先执行 queue.setNext(head);
         *      此时线程B 的 queue.next = null-> 线程C 执行 queue.setNext(head);
         *      线程C 的 queue.next=null -> 线程B 执行 head = queue;
         *      设置head为线程B的queue -> 线程C 执行 head = queue;
         *      设置 head 为线程C 的 queue
         *
         * 注意：此时线程B 和线程C 的 queue 没有连起来，则之后的 poll()就不会从B进行查询。（B就是资源泄露）
         */
        synchronized void setHead(WeakOrderQueue queue) {
            /*
             * 这一步非常重要：
             *  假设线程A创建对象，此处是线程C回收对象，则线程C先获取其Map<Stack<?>, WeakOrderQueue>对象中 key=线程A的 stack 对象的 WeakOrderQueue，
             *  然后将该 Queue 赋值给线程A 的stack.head，后续的 pop 操作打基础
             *
             *  这个流程保证了
             *      1 新建的 queue 的 next 永远会指向原来 stack.head 指向的 queue。
             *      2 stack.head 永远会指向新建的这个 queue
             *  这样就形成了一个链表结果
             *
             * 如果 stack 也是新建的，那么 head 现在就是 null，新建的 queue.next = null
             */
            queue.setNext(head);
            head = queue;
        }

        /**
         * 对 stack 中的数组 {@link Stack#elements} 进行扩容
         */
        int increaseCapacity(int expectedCapacity) {
            /* 获取旧数组长度 */
            int newCapacity = elements.length;
            /* 获取最大长度 */
            int maxCapacity = this.maxCapacity;
            /* 不断扩容（每次扩容2倍），直到达到 expectedCapacity 或者新容量已经大于等于 maxCapacity */
            do {
                /* 扩容2倍 */
                newCapacity <<= 1;
            } while (newCapacity < expectedCapacity && newCapacity < maxCapacity);

            /* 上述的扩容有可能使新容量 newCapacity > maxCapacity，这里取最小值 */
            newCapacity = min(newCapacity, maxCapacity);
            /* 如果新旧容量不相等，进行实际扩容 */
            if (newCapacity != elements.length) {
                /* 创建新数组，复制旧数组元素到新数组，并将新数组赋值给 Stack.elements */
                elements = Arrays.copyOf(elements, newCapacity);
            }

            return newCapacity;
        }

        /**
         * one-to-zero:
         *  1 先看看 stack 数组中是否有可用对象，如果有则直接返回
         *  2 如果没有则遍历 stack 在别的线程中的 WeakOrderQueue，有则将对象放入 stack 中并返回，没有找到则返回 null
         */
        @SuppressWarnings({ "unchecked", "rawtypes" })
        DefaultHandle<T> pop() {
            int size = this.size;

            /* 如果 size=0；说明 stack 的数组中没有可重用的对象 */
            if (size == 0) {
                /* 遍历 stack 在别的线程中的 WeakOrderQueue */
                if (!scavenge()) {
                    return null;
                }
                /**
                 * 由于在transfer(Stack<?> dst)的过程中，可能会将其他线程的 WeakOrderQueue 中的 DefaultHandle 对象传递到当前的 Stack,
                 * 所以 size 发生了变化，需要重新赋值
                 */
                size = this.size;
            }
            /**
             * 注意：因为一个 Recycler<T> 只能回收一种类型 T 的对象，所以 element 可以直接使用操作 size 来作为下标来进行获取
             */
            size --;
            DefaultHandle ret = elements[size];
            elements[size] = null;
            if (ret.lastRecycledId != ret.recycleId) {
                throw new IllegalStateException("recycled multiple times");
            }
            ret.recycleId = 0;
            ret.lastRecycledId = 0;
            this.size = size;
            return ret;
        }

        /**
         * one-to-zero:
         *  遍历 stack 在别的线程中的 WeakOrderQueue 是否有可重用的对象
         *  true：有
         *  false：没有
         */
        boolean scavenge() {
            // continue an existing scavenge, if any
            if (scavengeSome()) {
                return true;
            }

            // reset our scavenge cursor
            prev = null;
            cursor = head;
            return false;
        }

        /**
         * 遍历其余线程所属 stack 的 queue 中是否有可重用对象，
         * 如果有：则转移到 stack 中，并且返回 true
         * 如果没有：则返回 false
         */
        boolean scavengeSome() {
            WeakOrderQueue prev;
            /* 程序第一次获取是没有 WeakOrderQueue，所以 cursor 是 null */
            WeakOrderQueue cursor = this.cursor;
            if (cursor == null) {
                prev = null;
                cursor = head;
                /* 如果 head == null，表示当前的 Stack 对象没有 WeakOrderQueue，直接返回 */
                if (cursor == null) {
                    return false;
                }
            } else {
                prev = this.prev;
            }

            boolean success = false;
            do {
                /*
                 * 如果线程A 获取对象，那么 stack 中没有，则会在别的线程中的 queue 查找
                 * 这个 transfer 方法的参数 this 就是线程A 的 stack
                 * 如果转移成功，则直接返回 true
                 * 否则继续寻找下一个 queue
                 */
                if (cursor.transfer(this)) {
                    success = true;
                    break;
                }
                /* 遍历下一个 WeakOrderQueue，这个 queue 则是另外一个线程的 */
                WeakOrderQueue next = cursor.next;
                if (cursor.owner.get() == null) {
                    // If the thread associated with the queue is gone, unlink it, after
                    // performing a volatile read to confirm there is no data left to collect.
                    // We never unlink the first queue, as we don't want to synchronize on updating the head.
                    /*
                     * 如果当前的 WeakOrderQueue 的线程已经不可达了，则
                     * 1、如果该 WeakOrderQueue 中有数据，则将其中的数据全部转移到当前 Stack 中
                     * 2、将当前的 WeakOrderQueue 的前一个节点 prev 指向当前的 WeakOrderQueue 的下一个节点，即将当前的 WeakOrderQueue 从 Queue 链表中移除。方便后续GC
                     */
                    if (cursor.hasFinalData()) {
                        for (;;) {
                            if (cursor.transfer(this)) {
                                success = true;
                            } else {
                                break;
                            }
                        }
                    }

                    if (prev != null) {
                        prev.setNext(next);
                    }
                } else {
                    prev = cursor;
                }

                cursor = next;

            } while (cursor != null && !success);

            this.prev = prev;
            this.cursor = cursor;
            return success;
        }

        void push(DefaultHandle<?> item) {
            Thread currentThread = Thread.currentThread();
            if (threadRef.get() == currentThread) {
                // The current Thread is the thread that belongs to the Stack, we can try to push the object now.
                /* 如果该 stack 就是本线程的 stack，那么直接把 DefaultHandle 放到该 stack 的数组里 */
                pushNow(item);
            } else {
                // The current Thread is not the one that belongs to the Stack
                // (or the Thread that belonged to the Stack was collected already), we need to signal that the push
                // happens later.
                pushLater(item, currentThread);
            }
        }

        /**
         * one-to-zero:
         *  1 判断回收对象是否已被回收
         *  2 判断是否达到 stack 的最大容量
         *  3 如果达到了数组的大小，则扩容双倍
         *  4 将回收对象直接放入数组
         */
        private void pushNow(DefaultHandle<?> item) {
            /*
             * (item.recycleId | item.lastRecycleId) != 0 等价于 item.recycleId != 0 && item.lastRecycleId != 0
             * 当 item 开始创建时 item.recycleId == 0 && item.lastRecycleId == 0
             * 当 item 被 recycle 时，item.recycleId == x，item.lastRecycleId == y 进行赋值
             * 当 item 被 poll 之后， item.recycleId = item.lastRecycleId = 0
             * 所以当 item.recycleId 和 item.lastRecycleId 任何一个不为0，则表示回收过
             */
            if ((item.recycleId | item.lastRecycledId) != 0) {
                throw new IllegalStateException("recycled already");
            }
            /* 初始化 recycleId 和 lastRecycledId 值 */
            item.recycleId = item.lastRecycledId = OWN_THREAD_ID;

            int size = this.size;
            /* 如果已经超过了容量最大值或者应该被 drop 掉，则直接返回 */
            if (size >= maxCapacity || dropHandle(item)) {
                // Hit the maximum capacity or should drop - drop the possibly youngest object.
                return;
            }
            /* 扩容为原来的两倍 */
            if (size == elements.length) {
                elements = Arrays.copyOf(elements, min(size << 1, maxCapacity));
            }
            /* 直接将回收对象放在 数组中 */
            elements[size] = item;
            this.size = size + 1;
        }

        /**
         * 先将 item 元素加入 WeakOrderQueue，后续再从 WeakOrderQueue 中将元素压入 Stack 中
         */
        private void pushLater(DefaultHandle<?> item, Thread thread) {
            // we don't want to have a ref to the queue as the value in our weak map
            // so we null it out; to ensure there are no races with restoring it later
            // we impose a memory ordering here (no-op on x86)
            /*
             * Recycler 有1个 stack -> WeakOrderQueue 映射，每个 stack 会映射到1个 WeakOrderQueue，
             * 这个 WeakOrderQueue 是该 stack 关联的其它线程 WeakOrderQueue 链表的 head WeakOrderQueue。
             * 当其它线程回收对象到该 stack 时会创建1个 WeakOrderQueue 并加到 stack 的 WeakOrderQueue 链表中。
             */
            Map<Stack<?>, WeakOrderQueue> delayedRecycled = DELAYED_RECYCLED.get();
            /*
             * 如果线程A 创建了 handle 对象，在线程B 中被释放，那么下面这段代码运行的是在线程B 中，
             * 1 首先获取线程B 中的 Map<Stack<?>, WeakOrderQueue>
             * 2 因为 Map<Stack<?>, WeakOrderQueue> 中的 key 是其他线程的 stack，所以 .get(this) 中的 this 就是线程A 的 stack
             *   也就是参数 item 对应的 stack
             *
             */
            WeakOrderQueue queue = delayedRecycled.get(this);
            if (queue == null) {
                /* 如果 DELAYED_RECYCLED 中的 key-value 对已经达到了 maxDelayedQueues，则后续的无法回收 - 内存保护 */
                if (delayedRecycled.size() >= maxDelayedQueues) {
                    // Add a dummy queue so we know we should drop the object
                    /* 其实后续操作都会判断 map 中获取出来的 WeakOrderQueue 如果是 DUMMY 类型，那么说明容量已经满了需要被删除 */
                    delayedRecycled.put(this, WeakOrderQueue.DUMMY);
                    return;
                }
                // Check if we already reached the maximum number of delayed queues and if we can allocate at all.
                if ((queue = WeakOrderQueue.allocate(this, thread)) == null) {
                    // drop object
                    return;
                }
                delayedRecycled.put(this, queue);
            } else if (queue == WeakOrderQueue.DUMMY) {
                // drop object
                return;
            }

            queue.add(item);
        }

        /**
         * one-to-zero:
         *  netty 为了防止 Stack的 DefaultHandle[] 数组发生爆炸性的增长，所以默认采取每 8 个元素回收一个，扔掉 7 个的策略
         *  缓存池默认最大个数是 4k，但是如果某一个线程突然申请了很多对象，同时释放了很多对象，
         *  那么 netty 是不会直接将这些对象回收的，而是有一个策略，满足这个策略就会回收，不满足就直接丢弃
         *
         *  两个 drop 的时机
         *      1、pushNow：当前线程将数据 push 到 Stack 中
         *      2、transfer：将其他线程的 WeakOrderQueue 中的数据转移到当前的 Stack 中
         */
        boolean dropHandle(DefaultHandle<?> handle) {
            if (!handle.hasBeenRecycled) {
                /*
                 * 每8个对象：扔掉7个，回收一个
                 * 回收的索引：handleRecycleCount - 0/8/16/24/32/...
                 * handleRecycleCount 无限递增也没有关系，超过了 Integer.MAX_VALUE 就会变成 负数
                 */
                if ((++handleRecycleCount & ratioMask) != 0) {
                    // Drop the object.
                    return true;
                }
                /*
                 * 设置已经被回收了的标志，实际上此处还没有被回收，在 pushNow(DefaultHandle<T> item) 接下来的逻辑就会进行回收
                 * 对于 pushNow(DefaultHandle<T> item)：
                 *  该值仅仅用于控制是否执行 (++handleRecycleCount & ratioMask) != 0 这段逻辑，而不会用于阻止重复回收的操作，
                 *  重复回收的操作由 item.recycleId | item.lastRecycledId 来阻止
                 */
                handle.hasBeenRecycled = true;
            }
            return false;
        }

        DefaultHandle<T> newHandle() {
            return new DefaultHandle<T>(this);
        }
    }
}
