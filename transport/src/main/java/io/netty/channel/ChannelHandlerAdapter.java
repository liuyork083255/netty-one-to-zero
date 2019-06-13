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

package io.netty.channel;

import io.netty.channel.ChannelHandlerMask.Skip;
import io.netty.util.internal.InternalThreadLocalMap;

import java.util.Map;

/**
 * Skeleton implementation of a {@link ChannelHandler}.
 */
public abstract class ChannelHandlerAdapter implements ChannelHandler {

    // Not using volatile because it's used only for a sanity check.
    boolean added;

    /**
     * Throws {@link IllegalStateException} if {@link ChannelHandlerAdapter#isSharable()} returns {@code true}
     */
    protected void ensureNotSharable() {
        if (isSharable()) {
            throw new IllegalStateException("ChannelHandler " + getClass().getName() + " is not allowed to be shared");
        }
    }

    /**
     * Return {@code true} if the implementation is {@link Sharable} and so can be added
     * to different {@link ChannelPipeline}s.
     *
     * one-to-zero:
     *  判断当前的 handler 是否有 Sharable 注解，如果没有则将至添加到 thread local 中，默认是 {@link io.netty.util.concurrent.FastThreadLocal}，
     *  因为创建的线程是 {@link io.netty.util.concurrent.FastThreadLocalThread} 类型
     *  Note：
     *      由于 key 是线程，所以每一个线程都会存储一份在本地中
     *
     */
    public boolean isSharable() {
        /**
         * Cache the result of {@link Sharable} annotation detection to workaround a condition. We use a
         * {@link ThreadLocal} and {@link WeakHashMap} to eliminate the volatile write/reads. Using different
         * {@link WeakHashMap} instances per {@link Thread} is good enough for us and the number of
         * {@link Thread}s are quite limited anyway.
         *
         * See <a href="https://github.com/netty/netty/issues/2289">#2289</a>.
         */
        Class<?> clazz = getClass();
        Map<Class<?>, Boolean> cache = InternalThreadLocalMap.get().handlerSharableCache();
        Boolean sharable = cache.get(clazz);
        if (sharable == null) {
            /* 估计是这一步骤比较耗时，所以做了线程本地存储 */
            sharable = clazz.isAnnotationPresent(Sharable.class);
            cache.put(clazz, sharable);
        }
        return sharable;
    }

    /**
     * Do nothing by default, sub-classes may override this method.
     */
    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        // NOOP
    }

    /**
     * Do nothing by default, sub-classes may override this method.
     */
    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        // NOOP
    }

    /**
     * Calls {@link ChannelHandlerContext#fireExceptionCaught(Throwable)} to forward
     * to the next {@link ChannelHandler} in the {@link ChannelPipeline}.
     *
     * Sub-classes may override this method to change behavior.
     *
     * @deprecated is part of {@link ChannelInboundHandler}
     */
    @Skip
    @Override
    @Deprecated
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        ctx.fireExceptionCaught(cause);
    }
}
