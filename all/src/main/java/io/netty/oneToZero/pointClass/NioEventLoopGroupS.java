package io.netty.oneToZero.pointClass;

import io.netty.channel.MultithreadEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;

/**
 * {@link NioEventLoopGroup} 解析
 *  NioEventLoopGroup 其实就是做一个简单的封装，便于使用，内部主要还是 调用父类 {@link MultithreadEventLoopGroup} 类实现
 *  NioEventLoopGroup 主要提供了 {@link java.nio.channels.spi.SelectorProvider} 的引入
 *
 *  NioEventLoopGroup 可以理解成一个线程 nio-loop 线程池，但是和 jdk 的线程池有区别
 *      jdk的线程池是将任务放在一个 task queue 中，然后多个线程去领取任务，因为任务有或者没有，都会牵涉到线程之间的通信，有额外的开销
 *      但是 NioEventLoopGroup 则是为每一个 nio-loop 线程维护一个 task queue，减少了线程通信
 *
 *
 *
 */
public class NioEventLoopGroupS {
}