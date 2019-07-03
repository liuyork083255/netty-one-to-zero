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

package io.netty.util.concurrent;

import io.netty.util.internal.DefaultPriorityQueue;
import io.netty.util.internal.PriorityQueueNode;

import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * one-to-zero:
 *  ScheduledFutureTask 就是 netty 中的定时任务，它是一个非 public 修饰，所以在用户是无法使用该类的
 *
 */
@SuppressWarnings("ComparableImplementedButEqualsNotOverridden")
final class ScheduledFutureTask<V> extends PromiseTask<V> implements ScheduledFuture<V>, PriorityQueueNode {

    /** 下一次任务的执行id */
    private static final AtomicLong nextTaskId = new AtomicLong();

    /* 任务的创建时间，创建了当前任务则代表已经开始，因为如果是延迟任务那么就要从创建开始进行计时。 */
    private static final long START_TIME = System.nanoTime();

    /**
     * 获取程序启动到当前的时间间隔
     */
    static long nanoTime() {
        return System.nanoTime() - START_TIME;
    }

    /**
     * 获取最后一次的执行时间，此方法一般用于循环任务
     * 返回的是：程序启动时间(System.nanoTime() - START_TIME) + 参数(delay)
     */
    static long deadlineNanos(long delay) {
        /*
         *  使用当前时间减去任务开始时间并且加上周期不管怎么算都会是下一次的执行时间的间隔
         *  这里稍微有点绕，此处并不是使用具体的时间进行比较的而是使用时间段进行比较的，
         *  比如开始时间是00:00:00而当前时间是00:00:01他们的时间段就是1s而下一次执行周期计算应该是2s如果这样比较那么此条件不成立则不执行，
         *  直到当前时间00:00:02的时候才进行执行。而此方法就是获取下一次执行周期的计算结果。
         */
        long deadlineNanos = nanoTime() + delay;
        // Guard against overflow
        /* 这里防止计算错误导致程序错误所以做了对应的处理 */
        return deadlineNanos < 0 ? Long.MAX_VALUE : deadlineNanos;
    }

    /** 获取当前的id,并且 +1 */
    private final long id = nextTaskId.getAndIncrement();

    /**
     * one-to-zero:
     *  这只值不再是周期时间，而是下一次执行的时间，比如周期时间10s，那么这个值是：当前时间纳秒 + 10s纳秒值
     *  这个值不是 final 类型，说明可以被修改
     *  比如时间 t1 < t2 < t3，任务应该在 t3执行，但是程序在 t2 就获取这个任务执行，然后逻辑判断时间其实并没有到，
     *  所以重新设置过期时间周期，将 t3-t2，并将这个任务重新添加到 queue 中
     */
    private long deadlineNanos;

    /**
     * 0 - no repeat, >0 - repeat at fixed rate, <0 - repeat with fixed delay
     * 周期时长，这里需要注意这个周期有三个状态
     *  等于0的时候不会循环执行
     *  小于0则使用 scheduleWithFixedDelay 方法的算法，下一次执行时间是上次执行结束的时间加周期
     *  大于0则使用 scheduleAtFixedRate 方法的算法，下一次执行时间是上一次执行时间加周期
     */
    private final long periodNanos;

    /*  PriorityQueueNode 存储当前 node 是在队列中的那个下标，而此变量则是对列存储的下标 */
    private int queueIndex = INDEX_NOT_IN_QUEUE;

    /*
     * 构造器，传入执行器、运行的Runnable，因为是Runnable所以传入了result，执行的时间。
     * 可以看出此方法是延迟执行任务的构造，因为没有传入周期，执行一次即可结束。
     * 此处的执行时间是执行开始时间，而这个时间的算法就是deadlineNanos方法的调用
     */
    ScheduledFutureTask(AbstractScheduledEventExecutor executor, Runnable runnable, V result, long nanoTime) {
        this(executor, toCallable(runnable, result), nanoTime);
    }

    /*
     * 构造器，传入执行器，运行的Callable，执行时间，周期
     * 此处只支持period大于0或者小于0，如果等于0则会抛出异常
     * 而period就是对periodNanos的赋值之前讲述过他的差异
     */
    ScheduledFutureTask(AbstractScheduledEventExecutor executor, Callable<V> callable, long nanoTime, long period) {

        super(executor, callable);
        if (period == 0) {
            throw new IllegalArgumentException("period: 0 (expected: != 0)");
        }
        deadlineNanos = nanoTime;
        periodNanos = period;
    }

    /**
     * @param executor  执行器
     * @param callable  定时任务
     * @param nanoTime  过期时间，需要注意，这个时间已不在是周期时间，而是当前时间+周期时间，也就是下一次执行的时间
     */
    ScheduledFutureTask(AbstractScheduledEventExecutor executor, Callable<V> callable, long nanoTime) {

        super(executor, callable);
        deadlineNanos = nanoTime;
        /* 默认周期为0，不重复执行 */
        periodNanos = 0;
    }

    @Override
    protected EventExecutor executor() {
        return super.executor();
    }

    /* 获取执行时间 */
    public long deadlineNanos() {
        return deadlineNanos;
    }

    /**
     * 获取当前时间还有多久到下一次执行时间
     * 获取时间和下一次执行时间的差，如果当前时间已经超过下一次执行时间则返回0
     */
    public long delayNanos() {
        /*
         * deadlineNanos() - nanoTime()
         *  大于0，说明这个任务的执行时间还没有到
         *  小于等于0，说明这个任务的执行时间已经过了或者正好该执行
         */
        return Math.max(0, deadlineNanos() - nanoTime());
    }

    /**
     * 上一个方法 {@link #delayNanos()} 使用的是当前时间而此方法使用的是传入的指定时间
     */
    public long delayNanos(long currentTimeNanos) {

        return Math.max(0, deadlineNanos() - (currentTimeNanos - START_TIME));
    }

    /**
     * 将获取到的时长转为指定的时间类型，获取到的是纳秒如果传入的unit是秒或者毫秒则会转成对象的时长返回
     */
    @Override
    public long getDelay(TimeUnit unit) {
        return unit.convert(delayNanos(), TimeUnit.NANOSECONDS);
    }

    /**
     * 之前再说 {@link Delayed} 接口的时候，此继承 Comparable 接口，所以实现 Comparable 接口
     * 此方法是比较两个 ScheduledFutureTask 的周期任务下次执行的时长，因为既然是在队列中那么每次弹出的任务都会是头部的，
     * 所以是为了将先执行的任务排到队列头
     */
    @Override
    public int compareTo(Delayed o) {
        /* 如果两个对象比较相等则返回 0 */
        if (this == o) {
            return 0;
        }

        ScheduledFutureTask<?> that = (ScheduledFutureTask<?>) o;

        /* 当前的执行时间减去传入的执行时间，获取的就是他们的差数 */
        long d = deadlineNanos() - that.deadlineNanos();
        /* 如果小于0 则代表当前的时间执行早于传入的时间则返回 -1 */
        if (d < 0) {
            return -1;
        } else if (d > 0) {
            /* 如果大于0则代表当前任务晚于传入的时间则返回1 */
            return 1;
        } else if (id < that.id) {
            /* 如果他俩下一个周期时间相等则代表d是0，则判断他当前的id是否小于传入的id，如果小则代表当前任务优先于传入的任务则返回-1 */
            return -1;
        } else if (id == that.id) {
            /* 如果两个id相等则抛出异常 */
            throw new Error();
        } else {
            /* 否则传入的任务优先于当前的任务 */
            return 1;
        }
    }

    /**
     * 最终的运行run方法
     */
    @Override
    public void run() {
        /* 如果当前线程不是传入的执行器线程则会抛出断言异常，当然如果运行时没有开启断言关键字那么此代码无效 */
        assert executor().inEventLoop();
        try {
            /* 检查是否周期 */
            if (periodNanos == 0) {
                /* 与父级的使用相同设置为状态为正在运运行 */
                if (setUncancellableInternal()) {
                    /* 执行任务 */
                    V result = task.call();
                    /* 设置为成功 */
                    setSuccessInternal(result);
                }
            } else {
                // check if is done as it may was cancelled
                /* 检查当前的任务是否被取消了 */
                if (!isCancelled()) {
                    /* 如果没有则调用call，因为能进入这里都是循环执行的任务所以没有返回值 */
                    task.call();
                    /* 并且判断当前的执行器是否已经关闭 */
                    if (!executor().isShutdown()) {
                        /* 将当前的周期时间赋值给p */
                        long p = periodNanos;

                        /*
                         * 如果当前周期大于0则代表当前时间添加周期时间
                         * 这里需要注意当前时间包括了不包括执行时间
                         * 这样说可能有点绕，这样理解这里的p是本次执行是在开始的准时间，什么是准时间？就是无视任务的执行时间以周期时间和执行开始时间计算。
                         * scheduleAtFixedRate 方法的算法，通过下面的deadlineNanos += p 也是可以看出的。
                         */
                        if (p > 0) {
                            deadlineNanos += p;
                        } else {
                            /* 此处小于0 则就需要将当前程序的运行时间也要算进去所以使用了当前时间加周期，p因为小于0所以负负得正了 */
                            deadlineNanos = nanoTime() - p;
                        }
                        /* 如果还没有取消当前任务 */
                        if (!isCancelled()) {
                            // scheduledTaskQueue can never be null as we lazy init it before submit the task!
                            /* 获取任务队列并且将当前的任务在丢进去，因为已经计算完下一次执行的时间了，所以当前任务已经是一个新的任务，最起码执行时间改变了 */
                            Queue<ScheduledFutureTask<?>> scheduledTaskQueue = ((AbstractScheduledEventExecutor) executor()).scheduledTaskQueue;
                            assert scheduledTaskQueue != null;
                            scheduledTaskQueue.add(this);
                        }
                    }
                }
            }
        } catch (Throwable cause) {
            /* 如果出现异常则设置为失败 */
            setFailureInternal(cause);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @param mayInterruptIfRunning this value has no effect in this implementation.
     *
     * 取消当前任务所以需要从任务队列中移除当前任务
     */
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        boolean canceled = super.cancel(mayInterruptIfRunning);
        if (canceled) {
            ((AbstractScheduledEventExecutor) executor()).removeScheduled(this);
        }
        return canceled;
    }

    /* 取消不删除则直接调用父级方法不做任务的删除 */
    boolean cancelWithoutRemove(boolean mayInterruptIfRunning) {
        return super.cancel(mayInterruptIfRunning);
    }

    /* 和之前已经重写了父类的toString打印的详细信息 */
    @Override
    protected StringBuilder toStringBuilder() {
        StringBuilder buf = super.toStringBuilder();
        buf.setCharAt(buf.length() - 1, ',');

        return buf.append(" id: ")
                  .append(id)
                  .append(", deadline: ")
                  .append(deadlineNanos)
                  .append(", period: ")
                  .append(periodNanos)
                  .append(')');
    }

    /* 获取在队列中的位置 */
    @Override
    public int priorityQueueIndex(DefaultPriorityQueue<?> queue) {
        return queueIndex;
    }

    /* 设置当前任务在队列中的位置 */
    @Override
    public void priorityQueueIndex(DefaultPriorityQueue<?> queue, int i) {
        queueIndex = i;
    }
}
