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

import java.util.concurrent.Callable;
import java.util.concurrent.RunnableFuture;

class PromiseTask<V> extends DefaultPromise<V> implements RunnableFuture<V> {

    /**
     * Future 是任务管理，其中管理中有结果 result，但是 Runnable 接口的 run 方法并没有返回值，
     * 所以这里的做法是将 Runnable 接口转换成 Callable 接口，转换很简单就是创建了一个适配器并且传入结果和 runnable 接口
     */
    static <T> Callable<T> toCallable(Runnable runnable, T result) {
        return new RunnableAdapter<T>(runnable, result);
    }

    /* 定义的内部适配器类，此类继承与 Callable */
    private static final class RunnableAdapter<T> implements Callable<T> {
        /* 定义Runnable接口的属性 */
        final Runnable task;
        /* 结果集属性 */
        final T result;

        RunnableAdapter(Runnable task, T result) {
            this.task = task;
            this.result = result;
        }

        /**
         * 当运行Callable的时候则会调用此方法，此方法的实现也很简单，调用Runnable的run方法并且返回创建适配器时传入的result
         */
        @Override
        public T call() {
            task.run();
            return result;
        }

        @Override
        public String toString() {
            return "Callable(task: " + task + ", result: " + result + ')';
        }
    }

    /**
     * 其实就是用户定义的需要执行的任务
     *  */
    protected final Callable<V> task;

    /**
     * 构造器，传入一个线程执行器和不要执行的Runnable，最后还传入了返回结果。
     * 之前说定义说过个Runnable是没有返回值的所以需要在定义的时候就设置好结果，所以如果使用Runnable就必须要传入结果
     */
    PromiseTask(EventExecutor executor, Runnable runnable, V result) {
        this(executor, toCallable(runnable, result));
    }

    /**
     * 构造器，传入执行器和callable需要执行的有结果任务。因为传入的就是个执行有返回值的任务所以不用再传入结果
     * 而上方的构造器就是讲Runnable封装了下传入此构造器
     */
    PromiseTask(EventExecutor executor, Callable<V> callable) {
        super(executor);
        task = callable;
    }

    /**
     * hashCode 对象标识
     */
    @Override
    public final int hashCode() {
        return System.identityHashCode(this);
    }

    /**
     * 重写了equals，任务直接的比较
     */
    @Override
    public final boolean equals(Object obj) {
        return this == obj;
    }

    /**
     * 此处可能稍微有点抽象，因为并没有看到线程并且传入的执行器也没有执行，
     * 那是因为此方法在调用的时候已经有单独的一个线程在调用了，所以看起来和正常方法没有什么不一样的。
     */
    @Override
    public void run() {
        try {
            /* 首先判断当前任务状态是否为不可取消状态，因为如果设置到这个状态则表示当前的任务正在运行. */
            if (setUncancellableInternal()) {
                /* 如果没有运行那么则调用task.call方法获取执行结果 */
                V result = task.call();
                /* 执行完成则设置结果 */
                setSuccessInternal(result);
            }
        } catch (Throwable e) {
            /* 如果报错则设置异常结果 */
            setFailureInternal(e);
        }
    }

    /**
     * 剩下的方法都是默认的一些处理因为他下面还会有继承所以大多方法并没有特殊含义
     * 下面的方法再前面定义的时候都已经讲过所以这再不会进行讲解，因为并没有特殊的实现，要么是调用父级要么是直接返回值数据。
     */
    @Override
    public final Promise<V> setFailure(Throwable cause) {
        throw new IllegalStateException();
    }

    protected final Promise<V> setFailureInternal(Throwable cause) {
        super.setFailure(cause);
        return this;
    }

    @Override
    public final boolean tryFailure(Throwable cause) {
        return false;
    }

    protected final boolean tryFailureInternal(Throwable cause) {
        return super.tryFailure(cause);
    }

    @Override
    public final Promise<V> setSuccess(V result) {
        throw new IllegalStateException();
    }

    protected final Promise<V> setSuccessInternal(V result) {
        super.setSuccess(result);
        return this;
    }

    @Override
    public final boolean trySuccess(V result) {
        return false;
    }

    protected final boolean trySuccessInternal(V result) {
        return super.trySuccess(result);
    }

    @Override
    public final boolean setUncancellable() {
        throw new IllegalStateException();
    }

    protected final boolean setUncancellableInternal() {
        return super.setUncancellable();
    }

    @Override
    protected StringBuilder toStringBuilder() {
        StringBuilder buf = super.toStringBuilder();
        buf.setCharAt(buf.length() - 1, ',');

        return buf.append(" task: ")
                  .append(task)
                  .append(')');
    }
}
