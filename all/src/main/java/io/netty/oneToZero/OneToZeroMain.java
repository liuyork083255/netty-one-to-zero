package io.netty.oneToZero;

import io.netty.channel.nio.NioEventLoopGroup;

public class OneToZeroMain {

    public static void main(String[] args) {

        NioEventLoopGroup boss = new NioEventLoopGroup(1);
        NioEventLoopGroup worker = new NioEventLoopGroup(1);




    }


}