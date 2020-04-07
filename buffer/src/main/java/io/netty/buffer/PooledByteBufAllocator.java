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

package io.netty.buffer;

import static io.netty.util.internal.ObjectUtil.checkPositiveOrZero;

import io.netty.util.NettyRuntime;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.concurrent.FastThreadLocalThread;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.ThreadExecutorMap;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * one-to-zero:
 *  主要处理内存分配初始化参数和对外暴露访问 buf 接口，也就是一般通过 {@link PooledByteBufAllocator#DEFAULT} 来申请池化的内存空间会直接进入这个方法
 *  这是在应用层对底层的一个封装，最终的内存分配工作被委托给 {@link PoolArena}
 *
 * 名词定义：
 *      Chunk: 内存块，一个连续内存空间的管理者。具体有堆内 Chunk 和堆外 Chunk。默认大小为16MB。它是由一个或者多个 page 构成，默认 2048 个 page
 *      Page: 内存页，一个单位的内存大小。Chunk 将自身申请的连续内存空间分割成相等大小的一堆 Page。通过对 Page 的分配来完成内存分配功能。 默认 8K
 *      PooledChunkList: 将有相同使用率区间的 Chunk 集中在一起形成的一个 Chunk 列表。目的在于高效的分配和管理。
 *      Arena: 作为内存分配的总体入口，所有的内存分配动作都在这个类中对外提供。这个 Arena 的个数默认和 IO 线程个数相同，即一个线程对应一个 Arena
 *
 * 该类一被加载就会完成初始化 {@link PooledByteBufAllocator#DEFAULT}
 *
 */
public class PooledByteBufAllocator extends AbstractByteBufAllocator implements ByteBufAllocatorMetricProvider {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(PooledByteBufAllocator.class);

    /** 默认堆内存类型 PoolArena 个数 */
    private static final int DEFAULT_NUM_HEAP_ARENA;

    /** 默认直接内存类型 PoolArena 个数 */
    private static final int DEFAULT_NUM_DIRECT_ARENA;

    /** 每一页 page 默认大小 1024 * 8 = 8192 = 8k */
    private static final int DEFAULT_PAGE_SIZE;

    /**
     * 默认值 11 ，其实就是 page 大小的指数，默认 page 是 8k，则 8192 << 11 = 16 MiB per chunk 就是一个 chunk 的大小
     * 该值是控制完全二叉树的深度
     */
    private static final int DEFAULT_MAX_ORDER; // 8192 << 11 = 16 MiB per chunk

    /** tiny cache的大小 => 默认512 */
    private static final int DEFAULT_TINY_CACHE_SIZE;

    /** small cache的大小 => 默认256 */
    private static final int DEFAULT_SMALL_CACHE_SIZE;

    /** normal cache的大小 => 默认64 */
    private static final int DEFAULT_NORMAL_CACHE_SIZE;

    private static final int DEFAULT_MAX_CACHED_BUFFER_CAPACITY;

    private static final int DEFAULT_CACHE_TRIM_INTERVAL;

    private static final long DEFAULT_CACHE_TRIM_INTERVAL_MILLIS;

    private static final boolean DEFAULT_USE_CACHE_FOR_ALL_THREADS;

    private static final int DEFAULT_DIRECT_MEMORY_CACHE_ALIGNMENT;

    static final int DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK;

    /** 最小 PAGE_SIZE => 4K ，默认 8k */
    private static final int MIN_PAGE_SIZE = 4096;

    /** 最大 Chunk 的大小 => 默认等于2的30次方 即1G */
    private static final int MAX_CHUNK_SIZE = (int) (((long) Integer.MAX_VALUE + 1) / 2);

    private final Runnable trimTask = new Runnable() {
        @Override
        public void run() {
            PooledByteBufAllocator.this.trimCurrentThreadCache();
        }
    };

    static {
        /* 设置 page 大小 */
        int defaultPageSize = SystemPropertyUtil.getInt("io.netty.allocator.pageSize", 8192);
        Throwable pageSizeFallbackCause = null;
        try {
            validateAndCalculatePageShifts(defaultPageSize);
        } catch (Throwable t) {
            pageSizeFallbackCause = t;
            defaultPageSize = 8192;
        }
        DEFAULT_PAGE_SIZE = defaultPageSize;

        int defaultMaxOrder = SystemPropertyUtil.getInt("io.netty.allocator.maxOrder", 11);
        Throwable maxOrderFallbackCause = null;
        try {
            validateAndCalculateChunkSize(DEFAULT_PAGE_SIZE, defaultMaxOrder);
        } catch (Throwable t) {
            maxOrderFallbackCause = t;
            defaultMaxOrder = 11;
        }
        DEFAULT_MAX_ORDER = defaultMaxOrder;

        // Determine reasonable default for nHeapArena and nDirectArena.
        // Assuming each arena has 3 chunks, the pool should not consume more than 50% of max memory.
        /* 假设每个 arena 有3块内存，那么池占用的内存不应该超过最大内存的50%。 */
        final Runtime runtime = Runtime.getRuntime();

        /*
         * We use 2 * available processors by default to reduce contention as we use 2 * available processors for the
         * number of EventLoops in NIO and EPOLL as well. If we choose a smaller number we will run into hot spots as
         * allocation and de-allocation needs to be synchronized on the PoolArena.
         *
         * See https://github.com/netty/netty/issues/3888.
         */
        /*
         * 在缺省情况下，我们使用 2*可用的处理器 来减少争用，正如我们在 NIO 和 EPOLL 中使用 2 *可用的处理器 来处理 eventloop 的数量一样。
         * 如果我们选择一个更小的数字，我们将会因为分配和反分配需要在 PoolArena 上同步。
         */
        final int defaultMinNumArena = NettyRuntime.availableProcessors() * 2;
        final int defaultChunkSize = DEFAULT_PAGE_SIZE << DEFAULT_MAX_ORDER;

        /* 设置堆内存的 arena 的个数 */
        DEFAULT_NUM_HEAP_ARENA = Math.max(0, SystemPropertyUtil.getInt("io.netty.allocator.numHeapArenas",
                (int) Math.min(defaultMinNumArena, runtime.maxMemory() / defaultChunkSize / 2 / 3)));

        /* 设置非堆内存的 arena 的个数 */
        DEFAULT_NUM_DIRECT_ARENA = Math.max(0, SystemPropertyUtil.getInt("io.netty.allocator.numDirectArenas",
                (int) Math.min(defaultMinNumArena, PlatformDependent.maxDirectMemory() / defaultChunkSize / 2 / 3)));

        // cache sizes
        DEFAULT_TINY_CACHE_SIZE = SystemPropertyUtil.getInt("io.netty.allocator.tinyCacheSize", 512);
        DEFAULT_SMALL_CACHE_SIZE = SystemPropertyUtil.getInt("io.netty.allocator.smallCacheSize", 256);
        DEFAULT_NORMAL_CACHE_SIZE = SystemPropertyUtil.getInt("io.netty.allocator.normalCacheSize", 64);

        // 32 kb is the default maximum capacity of the cached buffer. Similar to what is explained in
        // 'Scalable memory allocation using jemalloc'
        DEFAULT_MAX_CACHED_BUFFER_CAPACITY = SystemPropertyUtil.getInt("io.netty.allocator.maxCachedBufferCapacity", 32 * 1024);

        // the number of threshold of allocations when cached entries will be freed up if not frequently used
        DEFAULT_CACHE_TRIM_INTERVAL = SystemPropertyUtil.getInt("io.netty.allocator.cacheTrimInterval", 8192);

        DEFAULT_CACHE_TRIM_INTERVAL_MILLIS = SystemPropertyUtil.getLong("io.netty.allocation.cacheTrimIntervalMillis", 0);

        DEFAULT_USE_CACHE_FOR_ALL_THREADS = SystemPropertyUtil.getBoolean("io.netty.allocator.useCacheForAllThreads", true);

        DEFAULT_DIRECT_MEMORY_CACHE_ALIGNMENT = SystemPropertyUtil.getInt("io.netty.allocator.directMemoryCacheAlignment", 0);

        // Use 1023 by default as we use an ArrayDeque as backing storage which will then allocate an internal array
        // of 1024 elements. Otherwise we would allocate 2048 and only use 1024 which is wasteful.
        DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK = SystemPropertyUtil.getInt("io.netty.allocator.maxCachedByteBuffersPerChunk", 1023);

        /* 日志输出 */
        if (logger.isDebugEnabled()) {
            logger.debug("-Dio.netty.allocator.numHeapArenas: {}", DEFAULT_NUM_HEAP_ARENA);
            logger.debug("-Dio.netty.allocator.numDirectArenas: {}", DEFAULT_NUM_DIRECT_ARENA);
            if (pageSizeFallbackCause == null) {
                logger.debug("-Dio.netty.allocator.pageSize: {}", DEFAULT_PAGE_SIZE);
            } else {
                logger.debug("-Dio.netty.allocator.pageSize: {}", DEFAULT_PAGE_SIZE, pageSizeFallbackCause);
            }
            if (maxOrderFallbackCause == null) {
                logger.debug("-Dio.netty.allocator.maxOrder: {}", DEFAULT_MAX_ORDER);
            } else {
                logger.debug("-Dio.netty.allocator.maxOrder: {}", DEFAULT_MAX_ORDER, maxOrderFallbackCause);
            }
            logger.debug("-Dio.netty.allocator.chunkSize: {}", DEFAULT_PAGE_SIZE << DEFAULT_MAX_ORDER);
            logger.debug("-Dio.netty.allocator.tinyCacheSize: {}", DEFAULT_TINY_CACHE_SIZE);
            logger.debug("-Dio.netty.allocator.smallCacheSize: {}", DEFAULT_SMALL_CACHE_SIZE);
            logger.debug("-Dio.netty.allocator.normalCacheSize: {}", DEFAULT_NORMAL_CACHE_SIZE);
            logger.debug("-Dio.netty.allocator.maxCachedBufferCapacity: {}", DEFAULT_MAX_CACHED_BUFFER_CAPACITY);
            logger.debug("-Dio.netty.allocator.cacheTrimInterval: {}", DEFAULT_CACHE_TRIM_INTERVAL);
            logger.debug("-Dio.netty.allocator.cacheTrimIntervalMillis: {}", DEFAULT_CACHE_TRIM_INTERVAL_MILLIS);
            logger.debug("-Dio.netty.allocator.useCacheForAllThreads: {}", DEFAULT_USE_CACHE_FOR_ALL_THREADS);
            logger.debug("-Dio.netty.allocator.maxCachedByteBuffersPerChunk: {}",
                    DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK);
        }
    }

    /**
     * 用户一般都是创建buf 都是堆内存，采用 {@link Unpooled}
     * 而 netty 不建议使用池化buf（原因是netty内部访问池化同时通过IO线程，由于Arena个数默认和线程个数相同，所以如果此时多了用户线程，那么就无法一一对应了），
     * 如果需要使用，则实例化就是通过下面默认创建的实例
     * PlatformDependent.directBufferPreferred() 在windows 默认返回 false，也就是默认创建堆内存，而不是直接内存
     *
     * PooledByteBufAllocator 的实例化，详见 {@link PooledByteBufAllocator} 以及构造方法
     *
     */
    public static final PooledByteBufAllocator DEFAULT = new PooledByteBufAllocator(PlatformDependent.directBufferPreferred());

    /** 堆内存对应的 arena 数组，默认长度为 CPU 核心数 */
    private final PoolArena<byte[]>[] heapArenas;

    /** 堆外内存 arena 数组，默认长度为 CPU 核心数 */
    private final PoolArena<ByteBuffer>[] directArenas;

    private final int tinyCacheSize;
    private final int smallCacheSize;
    private final int normalCacheSize;

    /** 堆内存 Arena 度测统计 list */
    private final List<PoolArenaMetric> heapArenaMetrics;

    /** 直接内存 Arena 度测统计 list */
    private final List<PoolArenaMetric> directArenaMetrics;
    private final PoolThreadLocalCache threadCache;

    /** 默认是 8192 << 11 = 16 MiB */
    private final int chunkSize;

    /**
     * PooledByteBufAllocatorMetric 其实也是一个 度测统计类
     * 比如统计
     *  堆内存所分配的内存总大小 {@link ByteBufAllocatorMetric#usedHeapMemory()}
     *  直接内存所分配的内存总大小 {@link ByteBufAllocatorMetric#usedDirectMemory()}
     */
    private final PooledByteBufAllocatorMetric metric;

    public PooledByteBufAllocator() {
        this(false);
    }

    @SuppressWarnings("deprecation")
    public PooledByteBufAllocator(boolean preferDirect) {
        this(preferDirect, DEFAULT_NUM_HEAP_ARENA, DEFAULT_NUM_DIRECT_ARENA, DEFAULT_PAGE_SIZE, DEFAULT_MAX_ORDER);
    }

    @SuppressWarnings("deprecation")
    public PooledByteBufAllocator(int nHeapArena, int nDirectArena, int pageSize, int maxOrder) {
        this(false, nHeapArena, nDirectArena, pageSize, maxOrder);
    }

    /**
     * @deprecated use
     * {@link PooledByteBufAllocator#PooledByteBufAllocator(boolean, int, int, int, int, int, int, int, boolean)}
     */
    @Deprecated
    public PooledByteBufAllocator(boolean preferDirect, int nHeapArena, int nDirectArena, int pageSize, int maxOrder) {
        this(preferDirect, nHeapArena, nDirectArena, pageSize, maxOrder,
                DEFAULT_TINY_CACHE_SIZE, DEFAULT_SMALL_CACHE_SIZE, DEFAULT_NORMAL_CACHE_SIZE);
    }

    /**
     * @deprecated use
     * {@link PooledByteBufAllocator#PooledByteBufAllocator(boolean, int, int, int, int, int, int, int, boolean)}
     */
    @Deprecated
    public PooledByteBufAllocator(boolean preferDirect, int nHeapArena, int nDirectArena, int pageSize, int maxOrder,
                                  int tinyCacheSize, int smallCacheSize, int normalCacheSize) {
        this(preferDirect, nHeapArena, nDirectArena, pageSize, maxOrder, tinyCacheSize, smallCacheSize,
                normalCacheSize, DEFAULT_USE_CACHE_FOR_ALL_THREADS, DEFAULT_DIRECT_MEMORY_CACHE_ALIGNMENT);
    }

    public PooledByteBufAllocator(boolean preferDirect, int nHeapArena,
                                  int nDirectArena, int pageSize, int maxOrder, int tinyCacheSize,
                                  int smallCacheSize, int normalCacheSize,
                                  boolean useCacheForAllThreads) {
        this(preferDirect, nHeapArena, nDirectArena, pageSize, maxOrder,
                tinyCacheSize, smallCacheSize, normalCacheSize,
                useCacheForAllThreads, DEFAULT_DIRECT_MEMORY_CACHE_ALIGNMENT);
    }

    /**
     * 初始化池化 ByteBuf 的分配器类
     * 主要核心逻辑还是创建 堆内存和直接内存的 Arena
     */
    public PooledByteBufAllocator(boolean preferDirect, int nHeapArena, int nDirectArena, int pageSize, int maxOrder,
                                  int tinyCacheSize, int smallCacheSize, int normalCacheSize,
                                  boolean useCacheForAllThreads, int directMemoryCacheAlignment) {
        /* 是否默认采用直接内存 */
        super(preferDirect);
        /* 实例化 threadCache 字段，这里仅仅是实例化，并没有创建线程的缓存对象 PoolThreadCache */
        threadCache = new PoolThreadLocalCache(useCacheForAllThreads);
        this.tinyCacheSize = tinyCacheSize;
        this.smallCacheSize = smallCacheSize;
        this.normalCacheSize = normalCacheSize;
        /* chunkSize初始化 其值为 pageSize*2^maxOrder */
        chunkSize = validateAndCalculateChunkSize(pageSize, maxOrder);

        checkPositiveOrZero(nHeapArena, "nHeapArena");
        checkPositiveOrZero(nDirectArena, "nDirectArena");

        checkPositiveOrZero(directMemoryCacheAlignment, "directMemoryCacheAlignment");
        if (directMemoryCacheAlignment > 0 && !isDirectMemoryCacheAlignmentSupported()) {
            throw new IllegalArgumentException("directMemoryCacheAlignment is not supported");
        }

        if ((directMemoryCacheAlignment & -directMemoryCacheAlignment) != directMemoryCacheAlignment) {
            throw new IllegalArgumentException("directMemoryCacheAlignment: " + directMemoryCacheAlignment + " (expected: power of two)");
        }

        /* pageShifts 就是 pageSize 二进制表示时尾部0的个数 pageSize是8192时，它的二进制表示是10000000000000，那么这个pageShifts就是13 */
        int pageShifts = validateAndCalculatePageShifts(pageSize);

        /** 实例化 heapArenas 数组， {@link DEFAULT_NUM_HEAP_ARENA} */
        if (nHeapArena > 0) {
            /* 创建 nHeapArena 长度的 PoolArena 数组 */
            heapArenas = newArenaArray(nHeapArena);

            List<PoolArenaMetric> metrics = new ArrayList<PoolArenaMetric>(heapArenas.length);
            for (int i = 0; i < heapArenas.length; i ++) {
                /**
                 * 这里其实真正创建的是 {@link PoolArena} 对象，HeapArena 只不过重载了几个方法
                 */
                PoolArena.HeapArena arena = new PoolArena.HeapArena(this, pageSize, maxOrder, pageShifts, chunkSize, directMemoryCacheAlignment);
                /* 可以发现，heapArenas 里面真正存储的对象是 PoolArena.HeapArena */
                heapArenas[i] = arena;
                /**
                 * {@link PoolArena.HeapArena} 实现了 {@link PoolArenaMetric} 接口，所以这里可以直接赋值
                 */
                metrics.add(arena);
            }
            heapArenaMetrics = Collections.unmodifiableList(metrics);
        } else {
            /** 可以发现，如果 {@link DEFAULT_NUM_HEAP_ARENA} 为非正数，其实就禁用了池化功能 */
            heapArenas = null;
            heapArenaMetrics = Collections.emptyList();
        }

        /** 实例化 heapArenas 数组， {@link DEFAULT_NUM_DIRECT_ARENA} */
        if (nDirectArena > 0) {
            directArenas = newArenaArray(nDirectArena);
            List<PoolArenaMetric> metrics = new ArrayList<PoolArenaMetric>(directArenas.length);
            for (int i = 0; i < directArenas.length; i ++) {
                /**
                 * 这里其实真正创建的是 {@link PoolArena} 对象，DirectArena 只不过重载了几个方法
                 */
                PoolArena.DirectArena arena = new PoolArena.DirectArena(this, pageSize, maxOrder, pageShifts, chunkSize, directMemoryCacheAlignment);
                /* 可以发现，directArenas 里面真正存储的对象是 PoolArena.DirectArena */
                directArenas[i] = arena;
                metrics.add(arena);
            }
            directArenaMetrics = Collections.unmodifiableList(metrics);
        } else {
            /** 可以发现，如果 {@link DEFAULT_NUM_DIRECT_ARENA} 为非正数，其实就禁用了池化功能 */
            directArenas = null;
            directArenaMetrics = Collections.emptyList();
        }
        metric = new PooledByteBufAllocatorMetric(this);
    }

    @SuppressWarnings("unchecked")
    private static <T> PoolArena<T>[] newArenaArray(int size) {
        return new PoolArena[size];
    }

    /**
     * 根据参数 pageSize 计算它的二进制后面有几个0，
     * 参数 pageSize 必须是 2 的 n 次方，
     * 由于 page-size 大小最小是 4K，默认 8K=10000000000000，所以返回值 13 (13个0)
     */
    private static int validateAndCalculatePageShifts(int pageSize) {
        if (pageSize < MIN_PAGE_SIZE) {
            throw new IllegalArgumentException("pageSize: " + pageSize + " (expected: " + MIN_PAGE_SIZE + ")");
        }

        if ((pageSize & pageSize - 1) != 0) {
            throw new IllegalArgumentException("pageSize: " + pageSize + " (expected: power of 2)");
        }

        // Logarithm base 2. At this point we know that pageSize is a power of two.
        return Integer.SIZE - 1 - Integer.numberOfLeadingZeros(pageSize);
    }

    /**
     * 该方法是设置 chunk 大小，其大小是根据 page 大小的 maxOrder 次方
     * @return
     */
    private static int validateAndCalculateChunkSize(int pageSize, int maxOrder) {
        if (maxOrder > 14) {
            throw new IllegalArgumentException("maxOrder: " + maxOrder + " (expected: 0-14)");
        }

        // Ensure the resulting chunkSize does not overflow.
        int chunkSize = pageSize;
        for (int i = maxOrder; i > 0; i --) {
            if (chunkSize > MAX_CHUNK_SIZE / 2) {
                throw new IllegalArgumentException(String.format("pageSize (%d) << maxOrder (%d) must not exceed %d", pageSize, maxOrder, MAX_CHUNK_SIZE));
            }
            chunkSize <<= 1;
        }
        return chunkSize;
    }

    @Override
    protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
        /* 获取内存空间优先从线程缓存中获取，如果此时 PoolThreadCache 为空，则会为当前线程新建 cache */
        PoolThreadCache cache = threadCache.get();
        PoolArena<byte[]> heapArena = cache.heapArena;

        final ByteBuf buf;
        if (heapArena != null) {
            buf = heapArena.allocate(cache, initialCapacity, maxCapacity);
        } else {
            buf = PlatformDependent.hasUnsafe() ?
                    new UnpooledUnsafeHeapByteBuf(this, initialCapacity, maxCapacity) :
                    new UnpooledHeapByteBuf(this, initialCapacity, maxCapacity);
        }

        return toLeakAwareBuffer(buf);
    }

    @Override
    protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
        /* 获取内存空间优先从线程缓存中获取 */
        PoolThreadCache cache = threadCache.get();
        PoolArena<ByteBuffer> directArena = cache.directArena;

        final ByteBuf buf;
        if (directArena != null) {
            buf = directArena.allocate(cache, initialCapacity, maxCapacity);
        } else {
            buf = PlatformDependent.hasUnsafe() ?
                    UnsafeByteBufUtil.newUnsafeDirectByteBuf(this, initialCapacity, maxCapacity) :
                    new UnpooledDirectByteBuf(this, initialCapacity, maxCapacity);
        }

        return toLeakAwareBuffer(buf);
    }

    /**
     * Default number of heap arenas - System Property: io.netty.allocator.numHeapArenas - default 2 * cores
     */
    public static int defaultNumHeapArena() {
        return DEFAULT_NUM_HEAP_ARENA;
    }

    /**
     * Default number of direct arenas - System Property: io.netty.allocator.numDirectArenas - default 2 * cores
     */
    public static int defaultNumDirectArena() {
        return DEFAULT_NUM_DIRECT_ARENA;
    }

    /**
     * Default buffer page size - System Property: io.netty.allocator.pageSize - default 8192
     */
    public static int defaultPageSize() {
        return DEFAULT_PAGE_SIZE;
    }

    /**
     * Default maximum order - System Property: io.netty.allocator.maxOrder - default 11
     */
    public static int defaultMaxOrder() {
        return DEFAULT_MAX_ORDER;
    }

    /**
     * Default thread caching behavior - System Property: io.netty.allocator.useCacheForAllThreads - default true
     */
    public static boolean defaultUseCacheForAllThreads() {
        return DEFAULT_USE_CACHE_FOR_ALL_THREADS;
    }

    /**
     * Default prefer direct - System Property: io.netty.noPreferDirect - default false
     */
    public static boolean defaultPreferDirect() {
        return PlatformDependent.directBufferPreferred();
    }

    /**
     * Default tiny cache size - System Property: io.netty.allocator.tinyCacheSize - default 512
     */
    public static int defaultTinyCacheSize() {
        return DEFAULT_TINY_CACHE_SIZE;
    }

    /**
     * Default small cache size - System Property: io.netty.allocator.smallCacheSize - default 256
     */
    public static int defaultSmallCacheSize() {
        return DEFAULT_SMALL_CACHE_SIZE;
    }

    /**
     * Default normal cache size - System Property: io.netty.allocator.normalCacheSize - default 64
     */
    public static int defaultNormalCacheSize() {
        return DEFAULT_NORMAL_CACHE_SIZE;
    }

    /**
     * Return {@code true} if direct memory cache alignment is supported, {@code false} otherwise.
     */
    public static boolean isDirectMemoryCacheAlignmentSupported() {
        return PlatformDependent.hasUnsafe();
    }

    @Override
    public boolean isDirectBufferPooled() {
        return directArenas != null;
    }

    /**
     * Returns {@code true} if the calling {@link Thread} has a {@link ThreadLocal} cache for the allocated
     * buffers.
     */
    @Deprecated
    public boolean hasThreadLocalCache() {
        return threadCache.isSet();
    }

    /**
     * Free all cached buffers for the calling {@link Thread}.
     */
    @Deprecated
    public void freeThreadLocalCache() {
        threadCache.remove();
    }

    final class PoolThreadLocalCache extends FastThreadLocal<PoolThreadCache> {
        private final boolean useCacheForAllThreads;

        PoolThreadLocalCache(boolean useCacheForAllThreads) {
            this.useCacheForAllThreads = useCacheForAllThreads;
        }

        /**
         * 在调用 {@link FastThreadLocal#get()} 方法的时候，如果获取的对应为空，那么父类中默认会调用 {@link FastThreadLocal#initialValue()}
         * 来完成对象的初始化
         */
        @Override
        protected synchronized PoolThreadCache initialValue() {
            final PoolArena<byte[]> heapArena = leastUsedArena(heapArenas);
            final PoolArena<ByteBuffer> directArena = leastUsedArena(directArenas);

            final Thread current = Thread.currentThread();
            if (useCacheForAllThreads || current instanceof FastThreadLocalThread) {
                final PoolThreadCache cache = new PoolThreadCache(
                        heapArena, directArena, tinyCacheSize, smallCacheSize, normalCacheSize,
                        DEFAULT_MAX_CACHED_BUFFER_CAPACITY, DEFAULT_CACHE_TRIM_INTERVAL);

                if (DEFAULT_CACHE_TRIM_INTERVAL_MILLIS > 0) {
                    final EventExecutor executor = ThreadExecutorMap.currentExecutor();
                    if (executor != null) {
                        executor.scheduleAtFixedRate(trimTask, DEFAULT_CACHE_TRIM_INTERVAL_MILLIS,
                                DEFAULT_CACHE_TRIM_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
                    }
                }
                return cache;
            }
            // No caching so just use 0 as sizes.
            return new PoolThreadCache(heapArena, directArena, 0, 0, 0, 0, 0);
        }

        @Override
        protected void onRemoval(PoolThreadCache threadCache) {
            threadCache.free(false);
        }

        /**
         * 通过一定的规则从所有的 Arena 中获取一个
         */
        private <T> PoolArena<T> leastUsedArena(PoolArena<T>[] arenas) {
            if (arenas == null || arenas.length == 0) {
                return null;
            }

            PoolArena<T> minArena = arenas[0];
            for (int i = 1; i < arenas.length; i++) {
                PoolArena<T> arena = arenas[i];
                /*
                 * numThreadCaches 默认都是0，如果一个线程获取 arena 之后，numThreadCaches 会 +1
                 * 也就保证了尽可能不同的线程获取不同的 arena
                 * arenas 默认个数是 cpu*2，当然：如果worker启动指定了m线程，比如m=2，那么其实 arenas 中只会用到2个，剩余的都是空闲的，仅仅是创建了类，但是底层内存并没有分配
                 */
                if (arena.numThreadCaches.get() < minArena.numThreadCaches.get()) {
                    minArena = arena;
                }
            }

            return minArena;
        }
    }

    @Override
    public PooledByteBufAllocatorMetric metric() {
        return metric;
    }

    /**
     * Return the number of heap arenas.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#numHeapArenas()}.
     */
    @Deprecated
    public int numHeapArenas() {
        return heapArenaMetrics.size();
    }

    /**
     * Return the number of direct arenas.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#numDirectArenas()}.
     */
    @Deprecated
    public int numDirectArenas() {
        return directArenaMetrics.size();
    }

    /**
     * Return a {@link List} of all heap {@link PoolArenaMetric}s that are provided by this pool.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#heapArenas()}.
     */
    @Deprecated
    public List<PoolArenaMetric> heapArenas() {
        return heapArenaMetrics;
    }

    /**
     * Return a {@link List} of all direct {@link PoolArenaMetric}s that are provided by this pool.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#directArenas()}.
     */
    @Deprecated
    public List<PoolArenaMetric> directArenas() {
        return directArenaMetrics;
    }

    /**
     * Return the number of thread local caches used by this {@link PooledByteBufAllocator}.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#numThreadLocalCaches()}.
     */
    @Deprecated
    public int numThreadLocalCaches() {
        PoolArena<?>[] arenas = heapArenas != null ? heapArenas : directArenas;
        if (arenas == null) {
            return 0;
        }

        int total = 0;
        for (PoolArena<?> arena : arenas) {
            total += arena.numThreadCaches.get();
        }

        return total;
    }

    /**
     * Return the size of the tiny cache.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#tinyCacheSize()}.
     */
    @Deprecated
    public int tinyCacheSize() {
        return tinyCacheSize;
    }

    /**
     * Return the size of the small cache.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#smallCacheSize()}.
     */
    @Deprecated
    public int smallCacheSize() {
        return smallCacheSize;
    }

    /**
     * Return the size of the normal cache.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#normalCacheSize()}.
     */
    @Deprecated
    public int normalCacheSize() {
        return normalCacheSize;
    }

    /**
     * Return the chunk size for an arena.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#chunkSize()}.
     */
    @Deprecated
    public final int chunkSize() {
        return chunkSize;
    }

    final long usedHeapMemory() {
        return usedMemory(heapArenas);
    }

    final long usedDirectMemory() {
        return usedMemory(directArenas);
    }

    private static long usedMemory(PoolArena<?>[] arenas) {
        if (arenas == null) {
            return -1;
        }
        long used = 0;
        for (PoolArena<?> arena : arenas) {
            used += arena.numActiveBytes();
            if (used < 0) {
                return Long.MAX_VALUE;
            }
        }
        return used;
    }

    final PoolThreadCache threadCache() {
        PoolThreadCache cache =  threadCache.get();
        assert cache != null;
        return cache;
    }

    /**
     * Trim thread local cache for the current {@link Thread}, which will give back any cached memory that was not
     * allocated frequently since the last trim operation.
     *
     * Returns {@code true} if a cache for the current {@link Thread} exists and so was trimmed, false otherwise.
     */
    public boolean trimCurrentThreadCache() {
        PoolThreadCache cache = threadCache.getIfExists();
        if (cache != null) {
            cache.trim();
            return true;
        }
        return false;
    }

    /**
     * Returns the status of the allocator (which contains all metrics) as string. Be aware this may be expensive
     * and so should not called too frequently.
     */
    public String dumpStats() {
        int heapArenasLen = heapArenas == null ? 0 : heapArenas.length;
        StringBuilder buf = new StringBuilder(512)
                .append(heapArenasLen)
                .append(" heap arena(s):")
                .append(StringUtil.NEWLINE);
        if (heapArenasLen > 0) {
            for (PoolArena<byte[]> a: heapArenas) {
                buf.append(a);
            }
        }

        int directArenasLen = directArenas == null ? 0 : directArenas.length;

        buf.append(directArenasLen)
           .append(" direct arena(s):")
           .append(StringUtil.NEWLINE);
        if (directArenasLen > 0) {
            for (PoolArena<ByteBuffer> a: directArenas) {
                buf.append(a);
            }
        }

        return buf.toString();
    }
}
