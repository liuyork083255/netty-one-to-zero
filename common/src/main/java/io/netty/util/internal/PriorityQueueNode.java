/*
 * Copyright 2015 The Netty Project
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
package io.netty.util.internal;

/**
 * Provides methods for {@link DefaultPriorityQueue} to maintain internal state. These methods should generally not be
 * used outside the scope of {@link DefaultPriorityQueue}.
 *
 * one-to-zero:
 *  此接口是为了维护队列内部数据使用的，只有在DefaultPriorityQueue中使用到了，其他地方不应该调用此接口定义的方法
 *  这里的维护仅仅是记录ScheduledFutureTask具体任务在队列中的下标地址，而下面的两个方法都是对下标做的操作。
 *  这里比较抽象，读者可以这样想，任务并不是一个是多个但是他们都需要执行，只能一个一个去执行，
 *  这样就需要一个队列去进行排序排到第几个则为了后面能快速获取到当前任务的下标所以这里实现了这个接口这算是个优化点，
 *  因为如果不进行记录那么可能会导致，如果获取当前下标则需要进行遍历比较，这样是非常消耗性能的，所以这个做法挺不错，可以拿来借鉴。
 *
 */
public interface PriorityQueueNode {
    /**
     * This should be used to initialize the storage returned by {@link #priorityQueueIndex(DefaultPriorityQueue)}.
     *
     * one-to-zero:
     *  定义了一个常量值，不存在队列中的index
     */
    int INDEX_NOT_IN_QUEUE = -1;

    /**
     * Get the last value set by {@link #priorityQueueIndex(DefaultPriorityQueue, int)} for the value corresponding to
     * {@code queue}.
     * <p>
     * Throwing exceptions from this method will result in undefined behavior.
     *
     * one-to-zero:
     *  获取在传入队列中的下标地址，具体看实现
     */
    int priorityQueueIndex(DefaultPriorityQueue<?> queue);

    /**
     * Used by {@link DefaultPriorityQueue} to maintain state for an element in the queue.
     * <p>
     * Throwing exceptions from this method will result in undefined behavior.
     * @param queue The queue for which the index is being set.
     * @param i The index as used by {@link DefaultPriorityQueue}.
     *
     *  one-to-zero:
     *  设置当前的任务在队列中的下标位置，int i则是对于的index
     */
    void priorityQueueIndex(DefaultPriorityQueue<?> queue, int i);
}
