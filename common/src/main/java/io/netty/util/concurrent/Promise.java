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

/**
 * Special {@link Future} which is writable.
 *
 * one-to-zero:
 *  Promise 是继承 Future，所有它也是一个 Future，我们都知道，Future 是不能写的，没有相关接口，只能通过读
 *  netty 通过 Promise 对 Future 进行扩展，用于写入 IO 操作的结果
 *
 *  这个写操作到底有什么用？？？
 *      Future 本身不能由实现者直接标记成功或失败，而是由调用的线程来标记。而Promise则在Future的基础上增加了让用户自己标记成功或失败
 *
 *
 * 核心功能逻辑是实现类 {@link DefaultPromise}
 * netty 中使用个的是 {@link DefaultPromise}的子类 DefaultChannelPromise
 *      用户操作 IO 事件，因为都是异步的，所以都会返回一个 Future，而这个 Future 其实就是一个 Promise  ->  new DefaultChannelPromise(channel(), executor());
 *
 *
 */
public interface Promise<V> extends Future<V> {

    /**
     * Marks this future as a success and notifies all
     * listeners.
     *
     * If it is success or failed already it will throw an {@link IllegalStateException}.
     *
     * one-to-zero:
     *  将此 Future 标记为成功，并通知所有侦听器。完成和操作，说明这个 future 的状态就变成的 completed
     *  如果它已经成功或失败，它将抛出一个 IllegalStateException
     *
     */
    Promise<V> setSuccess(V result);

    /**
     * Marks this future as a success and notifies all
     * listeners.
     *
     * @return {@code true} if and only if successfully marked this future as
     *         a success. Otherwise {@code false} because this future is
     *         already marked as either a success or a failure.
     *
     * one-to-zero:
     *  通过设置结果的方式标记Future成功并通知所有listener, 如果已被标记过，只是返回false
     *  完成和操作，说明这个 future 的状态就变成的 completed
     *
     */
    boolean trySuccess(V result);

    /**
     * Marks this future as a failure and notifies all
     * listeners.
     *
     * If it is success or failed already it will throw an {@link IllegalStateException}.
     *
     * one-to-zero：
     *  通过设置异常的方式标记Future失败并通知所有listener, 如果已被标记过，则抛出异常
     *  完成和操作，说明这个 future 的状态就变成的 completed
     *
     */
    Promise<V> setFailure(Throwable cause);

    /**
     * Marks this future as a failure and notifies all
     * listeners.
     *
     * @return {@code true} if and only if successfully marked this future as
     *         a failure. Otherwise {@code false} because this future is
     *         already marked as either a success or a failure.
     *
     * one-to-zero：
     *  通过设置异常的方式标记Future失败并通知所有listener, 如果已被标记过，只是返回false
     *  完成和操作，说明这个 future 的状态就变成的 completed
     */
    boolean tryFailure(Throwable cause);

    /**
     * Make this future impossible to cancel.
     *
     * @return {@code true} if and only if successfully marked this future as uncancellable or it is already done
     *         without being cancelled.  {@code false} if this future has been cancelled already.
     */
    boolean setUncancellable();

    @Override
    Promise<V> addListener(GenericFutureListener<? extends Future<? super V>> listener);

    @Override
    Promise<V> addListeners(GenericFutureListener<? extends Future<? super V>>... listeners);

    @Override
    Promise<V> removeListener(GenericFutureListener<? extends Future<? super V>> listener);

    @Override
    Promise<V> removeListeners(GenericFutureListener<? extends Future<? super V>>... listeners);

    @Override
    Promise<V> await() throws InterruptedException;

    @Override
    Promise<V> awaitUninterruptibly();

    @Override
    Promise<V> sync() throws InterruptedException;

    @Override
    Promise<V> syncUninterruptibly();
}
