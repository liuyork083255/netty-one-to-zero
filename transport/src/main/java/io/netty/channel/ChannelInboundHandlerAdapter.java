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
package io.netty.channel;

import io.netty.channel.ChannelHandlerMask.Skip;
import io.netty.channel.nio.AbstractNioByteChannel;
import io.netty.channel.nio.AbstractNioMessageChannel;

/**
 * Abstract base class for {@link ChannelInboundHandler} implementations which provide
 * implementations of all of their methods.
 *
 * <p>
 * This implementation just forward the operation to the next {@link ChannelHandler} in the
 * {@link ChannelPipeline}. Sub-classes may override a method implementation to change this.
 * </p>
 * <p>
 * Be aware that messages are not released after the {@link #channelRead(ChannelHandlerContext, Object)}
 * method returns automatically. If you are looking for a {@link ChannelInboundHandler} implementation that
 * releases the received messages automatically, please see {@link SimpleChannelInboundHandler}.
 * </p>
 *
 * one-to-zero：
 *  此类主要是提供一个捷便方式，实现了 ChannelHandlerAdapter ChannelInboundHandler 的所有抽象方法，便于用户继承
 *  此类的作用什么都做，仅仅只是将事件传播到下一个类的同名方法
 *  需要注意的是：
 *      {@link #channelRead(ChannelHandlerContext, Object)} 方法执行完成后不会释放消息体，也就是 byteBuf，
 *      如果用户需要寻找一种可以自动释放的消息体，那么可以参考 {@link SimpleChannelInboundHandler}
 *
 *  此类所有方法都注释了 @Skip 注解
 *  含义是：被注解的方法不会被 pipeline 调用，而是直接跳过，这个注解不会被继承
 *
 *  可以观察到：
 *      在 inbound 链中，调用传递方法都是用的 fireXxx 方法，但是 outbound 链中，则不是这样命名的
 *
 *
 *
 */
public class ChannelInboundHandlerAdapter extends ChannelHandlerAdapter implements ChannelInboundHandler {

    /**
     * Calls {@link ChannelHandlerContext#fireChannelRegistered()} to forward
     * to the next {@link ChannelInboundHandler} in the {@link ChannelPipeline}.
     *
     * Sub-classes may override this method to change behavior.
     *
     * one-to-zero:
     *  如果一个新连接接入，会执行这个方法，位置 -> {@link AbstractChannel.AbstractUnsafe#register0(ChannelPromise)} pipeline.fireChannelRegistered();
     *
     *  其实看源码可以发现，在 AbstractNioByteChannel 和 AbstractNioMessageChannel 中，最先执行的是 fireChannelRead 方法
     *  而注册都是发生在 boss-selector 上，boss-selector 的默认 handler 就是 ServerBootstrapAcceptor，这个 handler 只实现了 channelRead 和 exceptionCaught 方法
     *
     */
    @Skip
    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        ctx.fireChannelRegistered();
    }

    /**
     * Calls {@link ChannelHandlerContext#fireChannelUnregistered()} to forward
     * to the next {@link ChannelInboundHandler} in the {@link ChannelPipeline}.
     *
     * Sub-classes may override this method to change behavior.
     *
     * one-to-zero:
     *  断开连接后，会调用这个方法，debug 下来发现不是在IO事件中调用，而是在 task queue 中调用，
     *  在IO事件发生后，会判断出这个事件是关闭事件，所以会将这个事件以异步的方式提交到任务队列中，
     *  然后在任务队列中执行
     *
     */
    @Skip
    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        ctx.fireChannelUnregistered();
    }

    /**
     * Calls {@link ChannelHandlerContext#fireChannelActive()} to forward
     * to the next {@link ChannelInboundHandler} in the {@link ChannelPipeline}.
     *
     * Sub-classes may override this method to change behavior.
     *
     * one-to-zero:
     *  新连接注册后，会调用这个方法，位置 -> {@link AbstractChannel.AbstractUnsafe#register0(ChannelPromise)} pipeline.fireChannelRegistered();
     *
     */
    @Skip
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        ctx.fireChannelActive();
    }

    /**
     * Calls {@link ChannelHandlerContext#fireChannelInactive()} to forward
     * to the next {@link ChannelInboundHandler} in the {@link ChannelPipeline}.
     *
     * Sub-classes may override this method to change behavior.
     *
     * one-to-zero:
     *  断开连接后，会调用这个方法，debug 下来发现不是在IO事件中调用，而是在 task queue 中调用，
     *  在IO事件发生后，会判断出这个事件是关闭事件，所以会将这个事件以异步的方式提交到任务队列中，
     *  然后在任务队列中执行
     *
     */
    @Skip
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        ctx.fireChannelInactive();
    }

    /**
     * Calls {@link ChannelHandlerContext#fireChannelRead(Object)} to forward
     * to the next {@link ChannelInboundHandler} in the {@link ChannelPipeline}.
     *
     * Sub-classes may override this method to change behavior.
     *
     * one-to-zero:
     *  这个方法永远都是最先执行的，如果是在 boss 中，则是读取 channel 的连接事件，
     *  如果是在 worker 中，则是读取 channel 的数据 msg
     *  分别在 {@link AbstractNioByteChannel#read()} 和 {@link AbstractNioMessageChannel#read()} 执行
     *
     */
    @Skip
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ctx.fireChannelRead(msg);
    }

    /**
     * Calls {@link ChannelHandlerContext#fireChannelReadComplete()} to forward
     * to the next {@link ChannelInboundHandler} in the {@link ChannelPipeline}.
     *
     * Sub-classes may override this method to change behavior.
     *
     * one-to-zero:
     *  在 {@link this#channelRead(ChannelHandlerContext, Object)} 之后会紧接着这个方法的调用
     *  也是在 {@link AbstractNioByteChannel#read()} 和 {@link AbstractNioMessageChannel#read()} 执行
     *
     *  Note：
     *      在断开连接后，也会触发这个方法
     */
    @Skip
    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        ctx.fireChannelReadComplete();
    }

    /**
     * Calls {@link ChannelHandlerContext#fireUserEventTriggered(Object)} to forward
     * to the next {@link ChannelInboundHandler} in the {@link ChannelPipeline}.
     *
     * Sub-classes may override this method to change behavior.
     */
    @Skip
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        ctx.fireUserEventTriggered(evt);
    }

    /**
     * Calls {@link ChannelHandlerContext#fireChannelWritabilityChanged()} to forward
     * to the next {@link ChannelInboundHandler} in the {@link ChannelPipeline}.
     *
     * Sub-classes may override this method to change behavior.
     */
    @Skip
    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        ctx.fireChannelWritabilityChanged();
    }

    /**
     * Calls {@link ChannelHandlerContext#fireExceptionCaught(Throwable)} to forward
     * to the next {@link ChannelHandler} in the {@link ChannelPipeline}.
     *
     * Sub-classes may override this method to change behavior.
     */
    @Skip
    @Override
    @SuppressWarnings("deprecation")
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
            throws Exception {
        ctx.fireExceptionCaught(cause);
    }
}
