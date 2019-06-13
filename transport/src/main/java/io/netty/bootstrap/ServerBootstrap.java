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
package io.netty.bootstrap;

import io.netty.channel.*;
import io.netty.util.AttributeKey;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

/**
 * {@link Bootstrap} sub-class which allows easy bootstrap of {@link ServerChannel}
 *
 */
public class ServerBootstrap extends AbstractBootstrap<ServerBootstrap, ServerChannel> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ServerBootstrap.class);

    private final Map<ChannelOption<?>, Object> childOptions = new LinkedHashMap<ChannelOption<?>, Object>();
    private final Map<AttributeKey<?>, Object> childAttrs = new LinkedHashMap<AttributeKey<?>, Object>();
    private final ServerBootstrapConfig config = new ServerBootstrapConfig(this);
    /**
     * one-to-zero;
     *  boss group 在父类 {@link AbstractBootstrap#group} 中
     */
    private volatile EventLoopGroup childGroup;
    private volatile ChannelHandler childHandler;

    public ServerBootstrap() { }

    private ServerBootstrap(ServerBootstrap bootstrap) {
        super(bootstrap);
        childGroup = bootstrap.childGroup;
        childHandler = bootstrap.childHandler;
        synchronized (bootstrap.childOptions) {
            childOptions.putAll(bootstrap.childOptions);
        }
        synchronized (bootstrap.childAttrs) {
            childAttrs.putAll(bootstrap.childAttrs);
        }
    }

    /**
     * Specify the {@link EventLoopGroup} which is used for the parent (acceptor) and the child (client).
     */
    @Override
    public ServerBootstrap group(EventLoopGroup group) {
        return group(group, group);
    }

    /**
     * Set the {@link EventLoopGroup} for the parent (acceptor) and the child (client). These
     * {@link EventLoopGroup}'s are used to handle all the events and IO for {@link ServerChannel} and
     * {@link Channel}'s.
     */
    public ServerBootstrap group(EventLoopGroup parentGroup, EventLoopGroup childGroup) {
        super.group(parentGroup);
        ObjectUtil.checkNotNull(childGroup, "childGroup");
        if (this.childGroup != null) {
            throw new IllegalStateException("childGroup set already");
        }
        this.childGroup = childGroup;
        return this;
    }

    /**
     * Allow to specify a {@link ChannelOption} which is used for the {@link Channel} instances once they get created
     * (after the acceptor accepted the {@link Channel}). Use a value of {@code null} to remove a previous set
     * {@link ChannelOption}.
     *
     * one-to-zero:
     *  option 主要是设置一些网络底层参数 具体可以参考 {@link ChannelOption}
     *  和 attributeMap 不同，后者主要用于存放业务数据，然后在多个 handler 可以共享
     *
     *  option 设置可以根据 boss 和 worker 两个 selector 指定不同的参数
     *  如果是设置 boss 的 option ，则调用其父类 {@link this#option(ChannelOption, Object)}
     *
     */
    public <T> ServerBootstrap childOption(ChannelOption<T> childOption, T value) {
        ObjectUtil.checkNotNull(childOption, "childOption");

        /* 如果设置了参数，后期需要删除，那么可以直接重新设置一次，value 指定为 null 即可 */
        if (value == null) {
            synchronized (childOptions) {
                childOptions.remove(childOption);
            }
        } else {
            synchronized (childOptions) {
                childOptions.put(childOption, value);
            }
        }
        return this;
    }

    /**
     * Set the specific {@link AttributeKey} with the given value on every child {@link Channel}. If the value is
     * {@code null} the {@link AttributeKey} is removed
     *
     * one-to-zero:
     *  在每个子通道上使用给定的值设置特定的 AttributeKey
     *
     *  和 option 一样，attribute 也分为 boss 和 worker，boss 对应的方法是父类 {@link this#attr(AttributeKey, Object)}
     *
     */
    public <T> ServerBootstrap childAttr(AttributeKey<T> childKey, T value) {
        ObjectUtil.checkNotNull(childKey, "childKey");
        if (value == null) {
            childAttrs.remove(childKey);
        } else {
            childAttrs.put(childKey, value);
        }
        return this;
    }

    /**
     * Set the {@link ChannelHandler} which is used to serve the request for the {@link Channel}'s.
     *
     * one-to-zero:
     *  需要注意了，这里是给 worker 添加 handler，有两种添加方式
     *  1 添加类型是继承 ChannelHandlerAdapter 的自定义类 e.g. MyHandler
     *  2 添加的类型继承 ChannelHandlerAdapter 的 ChannelInitializer 类
     *  这里区别很大：
     *      serverBootstrap.childHandler(...)，添加方式是 new 也好，直接放入实例也好，netty 都会将至保存起来，
     *      放在 {@link ServerBootstrap.ServerBootstrapAcceptor#childHandler} 中，每当有新连接接入，就会调用 child.pipeline().addLast(childHandler);
     *      也就是说，这个 handler 的实例只有一个，而且必须是被 @Sharable 注释修饰，否则会抛出 ChannelPipelineException 异常，
     *      因为每次 pipeline.addLast 添加 handler，都会校验这个 handler 是否可共享，且是否被添加过，详见 {@link DefaultChannelPipeline#checkMultiplicity(ChannelHandler)} 方法
     *      所以：
     *          如果 serverBootstrap.childHandler(...) 是一个非 ChannelInitializer 类，就必须实现 @Sharable 注解，
     *          否则第一次 client 连接接入后，正常没问题，因为每一个 handler 有个 added 属性标识是否被加载过 正常，但是第二次 client 新连接立马就会抛出异常
     *          而 ChannelInitializer 就没有关系，因为其实现了 @Sharable 注解
     *
     */
    public ServerBootstrap childHandler(ChannelHandler childHandler) {
        this.childHandler = ObjectUtil.checkNotNull(childHandler, "childHandler");
        return this;
    }

    @Override
    void init(Channel channel) throws Exception {
        /**
         * one-to-zero:
         * 为 boss channel 管道初始化 option 参数
         */
        final Map<ChannelOption<?>, Object> options = options0();
        synchronized (options) {
            setChannelOptions(channel, options, logger);
        }

        /**
         * one-to-zero:
         * 为 boss channel 管道初始化 attr 参数
         * Note：
         *  在 >= 4.1 版本中，Channel.attr == ChannelHandlerContext.attr
         *  早期的 4.0 之前，每一个 ChannelHandlerContext 都会维护一个 attrMap
         *
         */
        final Map<AttributeKey<?>, Object> attrs = attrs0();
        synchronized (attrs) {
            for (Entry<AttributeKey<?>, Object> e: attrs.entrySet()) {
                @SuppressWarnings("unchecked")
                AttributeKey<Object> key = (AttributeKey<Object>) e.getKey();
                channel.attr(key).set(e.getValue());
            }
        }

        /**
         * one-to-zero:
         * pipeline 是 channel 的成员变量，channel 被创建的时候就会默认创建 pipeline 的实现
         * 这个 pipeline 是 boss 的
         */
        ChannelPipeline p = channel.pipeline();

        final EventLoopGroup currentChildGroup = childGroup;
        final ChannelHandler currentChildHandler = childHandler;
        final Entry<ChannelOption<?>, Object>[] currentChildOptions;
        final Entry<AttributeKey<?>, Object>[] currentChildAttrs;

        /**
         * one-to-zero:
         *  保存 worker option 参数
         */
        synchronized (childOptions) {
            currentChildOptions = childOptions.entrySet().toArray(newOptionArray(0));
        }

        /**
         * one-to-zero:
         *  保存 worker attr 参数
         */
        synchronized (childAttrs) {
            currentChildAttrs = childAttrs.entrySet().toArray(newAttrArray(0));
        }

        /**
         * one-to-zero:
         *  为 boss 的 pipeline 设置 handler
         *  1 用户一般在 boss 很少设置 handler
         *  2 netty 会为 boss 添加一个 ServerBootstrapAcceptor handler， ServerBootstrapAcceptor 是一个内部类，就是防止暴露出去
         *
         *  这里的 p.addLast 添加了一个 ChannelInitializer 对象，并且是重写了 initChannel 方法，这个方式会在
         *      {@link io.netty.channel.AbstractChannel.AbstractUnsafe#register0(ChannelPromise)} 方法中调用 pipeline.invokeHandlerAddedIfNeeded();
         *
         *
         * Note：
         *  这里需要注意：这里连续为 boss 的 pipeline 添加了两个 handler
         *  一个是 ChannelInitializer：
         *      它继承 ChannelInboundHandlerAdapter，这个handler里面就会添加到 pipeline 中
         *  一个是 ServerBootstrapAcceptor：
         *      这个是真正处理客户端新连接的 handler，但是这个添加不会里面执行，而是被回调，就是机制是
         *      p.addLast 方法添加 handler 后，会判断当前 pipeline 是否被注册到 channel，如果没有就添加一个任务 {@link DefaultChannelPipeline#pendingHandlerCallbackHead}持有
         *      这个回调会在 channel 注册之后被触发，因为 {@link AbstractChannel.AbstractUnsafe#register0(ChannelPromise)} 完成注册之后都会方法里面调用 pipeline.invokeHandlerAddedIfNeeded();
         *      这个方法就是会调用 pipeline 里面的所有 handler 的 handlerAdded 方法，而 {@link ChannelInitializer#handlerAdded(ChannelHandlerContext)} 就实现了这个方法，
         *      并且在这个方法里面回调 pipeline 里面所有 handler 的 initChannel 方法，也就是这里的 initChannel 方法
         *
         *      目前不好理解为什么 initChannel 回调方法要放在 handlerAdded 这回调方法里面，源码注释说是 保证多个ChannelInitializer初始化器顺序的一致性，
         *      原话是：
         *          This should always be true with our current DefaultChannelPipeline implementation.
         *          The good thing about calling initChannel(...) in handlerAdded(...) is that there will be no ordering
         *          surprises if a ChannelInitializer will add another ChannelInitializer. This is as all handlers
         *          will be added in the expected order.
         *
         *
         * 那么问题来了：
         *  因为我们知道，boss 的 pipeline 中的 handler 默认只有三个，headHandler ，ServerBootstrapAcceptor ，tailHandler
         *  那这里的 ChannelInitializer handler 跑哪里去了，是在什么时候被删除的？？？
         *  其实是 ChannelInitializer 自己删除自己的 {@link ChannelInitializer#removeState(ChannelHandlerContext)}，这个方法也会在其内部被调用，也就是 channel 完成注册和初始化
         *  这个 ChannelInitializer 其实就是一个辅助类，本就不应该存在 pipeline 中
         *
         */
        p.addLast(new ChannelInitializer<Channel>() {
            /**
             * one-to-zero:
             *  这里的代码逻辑都是在 channel 完成注册之后被回调，回调机制是 {@link DefaultChannelPipeline#pendingHandlerCallbackHead}
             */
            @Override
            public void initChannel(final Channel ch) throws Exception {
                final ChannelPipeline pipeline = ch.pipeline();
                /**
                 * one-to-zero:
                 *  由于启动的时候没有设置 ServerBootstrap.handler(MyHandler)，所以这里为空
                 */
                ChannelHandler handler = config.handler();
                if (handler != null) {
                    pipeline.addLast(handler);
                }

                /**
                 * one-to-zero:
                 *
                 */
                ch.eventLoop().execute(new Runnable() {
                    @Override
                    public void run() {
                        pipeline.addLast(new ServerBootstrapAcceptor(
                                ch, currentChildGroup, currentChildHandler, currentChildOptions, currentChildAttrs));
                    }
                });
            }
        });
    }

    @Override
    public ServerBootstrap validate() {
        super.validate();
        if (childHandler == null) {
            throw new IllegalStateException("childHandler not set");
        }
        if (childGroup == null) {
            logger.warn("childGroup is not set. Using parentGroup instead.");
            childGroup = config.group();
        }
        return this;
    }

    @SuppressWarnings("unchecked")
    private static Entry<AttributeKey<?>, Object>[] newAttrArray(int size) {
        return new Entry[size];
    }

    @SuppressWarnings("unchecked")
    private static Map.Entry<ChannelOption<?>, Object>[] newOptionArray(int size) {
        return new Map.Entry[size];
    }

    /**
     * one-to-zero:
     *  ServerBootstrapAcceptor 其实就是 netty 默认添加的 handler，也就是放在 boss 的 pipeline 中
     *  如果用户没有自定义给 boss 添加 handler，那么 boss 的 pipeline 中只会有 是三个 handler，
     *  分别是 HeadHandler ServerBootstrapAcceptor TailHandler
     *
     *  而 ServerBootstrapAcceptor 作用就是处理新进来的客户端连接，因为 boss 的 channel 注册感性兴趣的事件就是 accept
     *  {@link io.netty.channel.socket.nio.NioServerSocketChannel(java.nio.channels.ServerSocketChannel)} 构造方法中指定的 SelectionKey.OP_ACCEPT
     *
     *
     *  ServerBootstrapAcceptor 就是 Reactor 模式中的 Acceptor
     *
     */
    private static class ServerBootstrapAcceptor extends ChannelInboundHandlerAdapter {

        /* worker 的 child group */
        private final EventLoopGroup childGroup;
        /* worker 的 child handler */
        private final ChannelHandler childHandler;
        /* worker 的 child option */
        private final Entry<ChannelOption<?>, Object>[] childOptions;
        /* worker 的 child attr */
        private final Entry<AttributeKey<?>, Object>[] childAttrs;
        private final Runnable enableAutoReadTask;

        ServerBootstrapAcceptor(
                final Channel channel, EventLoopGroup childGroup, ChannelHandler childHandler,
                Entry<ChannelOption<?>, Object>[] childOptions, Entry<AttributeKey<?>, Object>[] childAttrs) {
            this.childGroup = childGroup;
            this.childHandler = childHandler;
            this.childOptions = childOptions;
            this.childAttrs = childAttrs;

            // Task which is scheduled to re-enable auto-read.
            // It's important to create this Runnable before we try to submit it as otherwise the URLClassLoader may
            // not be able to load the class because of the file limit it already reached.
            //
            // See https://github.com/netty/netty/issues/1328
            enableAutoReadTask = new Runnable() {
                @Override
                public void run() {
                    channel.config().setAutoRead(true);
                }
            };
        }

        @Override
        @SuppressWarnings("unchecked")
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            /**
             * one-to-zero:
             *  因为这是 boss 独有的 handler，并且注册感兴趣事件是 SelectionKey.OP_ACCEPT， 所以这里必然可以将 msg 强转为 Channel
             *  如果是 worker 中的 handler，msg 基本都是 byteBuf
             */
            final Channel child = (Channel) msg;

            child.pipeline().addLast(childHandler);

            setChannelOptions(child, childOptions, logger);

            for (Entry<AttributeKey<?>, Object> e: childAttrs) {
                child.attr((AttributeKey<Object>) e.getKey()).set(e.getValue());
            }

            try {
                /**
                 * one-to-zero:
                 *  1 进入 {@link io.netty.channel.MultithreadEventLoopGroup#register(Channel)}
                 *
                 *
                 *
                 *
                 */
                childGroup.register(child).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        if (!future.isSuccess()) {
                            forceClose(child, future.cause());
                        }
                    }
                });
            } catch (Throwable t) {
                forceClose(child, t);
            }
        }

        private static void forceClose(Channel child, Throwable t) {
            child.unsafe().closeForcibly();
            logger.warn("Failed to register an accepted channel: {}", child, t);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            final ChannelConfig config = ctx.channel().config();
            if (config.isAutoRead()) {
                // stop accept new connections for 1 second to allow the channel to recover
                // See https://github.com/netty/netty/issues/1328
                config.setAutoRead(false);
                ctx.channel().eventLoop().schedule(enableAutoReadTask, 1, TimeUnit.SECONDS);
            }
            // still let the exceptionCaught event flow through the pipeline to give the user
            // a chance to do something with it
            ctx.fireExceptionCaught(cause);
        }
    }

    @Override
    @SuppressWarnings("CloneDoesntCallSuperClone")
    public ServerBootstrap clone() {
        return new ServerBootstrap(this);
    }

    /**
     * Return the configured {@link EventLoopGroup} which will be used for the child channels or {@code null}
     * if non is configured yet.
     *
     * @deprecated Use {@link #config()} instead.
     */
    @Deprecated
    public EventLoopGroup childGroup() {
        return childGroup;
    }

    final ChannelHandler childHandler() {
        return childHandler;
    }

    final Map<ChannelOption<?>, Object> childOptions() {
        return copiedMap(childOptions);
    }

    final Map<AttributeKey<?>, Object> childAttrs() {
        return copiedMap(childAttrs);
    }

    @Override
    public final ServerBootstrapConfig config() {
        return config;
    }
}
