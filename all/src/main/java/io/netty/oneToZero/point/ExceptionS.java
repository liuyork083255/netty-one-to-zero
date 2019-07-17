package io.netty.oneToZero.point;

import io.netty.channel.ChannelHandlerContext;

/**
 * {@link io.netty.channel.AbstractChannelHandlerContext}
 * netty 异常处理机制：
 *  在用户自定义 handler 中的 inbound 方法如果抛出异常都会被处理，
 *  然后回调用用户的 {@link io.netty.channel.ChannelInboundHandlerAdapter#exceptionCaught(ChannelHandlerContext, Throwable)}
 *
 *  其实核心逻辑就是在 {@link io.netty.channel.AbstractChannelHandlerContext} 中，每次调用 inbound 事件，所有方法都被 try-catch
 *  然后会调用用户自己重写的异常方法
 *  参考 ：{@link io.netty.channel.AbstractChannelHandlerContext#invokeChannelRegistered()}
 *
 */
public class ExceptionS {
}