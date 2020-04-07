package io.netty.oneToZero;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

/**
 * VERSION: 4.1.36.Final
 *
 */
public class OneToZeroMain {

    public static void main(String[] args) {

        NioEventLoopGroup boss = new NioEventLoopGroup(1);
        NioEventLoopGroup worker = new NioEventLoopGroup(1);

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(boss, boss).channel(NioServerSocketChannel.class);

            /**
             * one-to-zero:
             *  点击查看添加的 handler 是 自定义handler 还是 ChannelInitializer 区别
             *  详见 {@link ServerBootstrap#childHandler(ChannelHandler)} 的注解
             */
            bootstrap.handler(null);

            /**
             * one-to-zero:
             *  点击查看添加的 handler 是 自定义handler 还是 ChannelInitializer 区别
             */
            bootstrap.childHandler(null);

            ChannelFuture cf = bootstrap.bind(12345).sync();
            cf.channel().closeFuture().sync();
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        finally {
            boss.shutdownGracefully();
            worker.shutdownGracefully();
        }



    }


}