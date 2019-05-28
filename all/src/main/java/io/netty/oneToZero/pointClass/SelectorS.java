package io.netty.oneToZero.pointClass;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;

/**
 * {@link java.nio.channels.Selector}
 *
 *
 *
 *
 */
public class SelectorS {
    /* 服务端代码启动过程 */
    public static void main(String[] args) throws IOException {
        /*
         * 1. 获取服务端通道并绑定IP和端口号
         * 2. 将服务端通道设置成非阻塞模式
         * 3. 开启一个选择器
         * 4. 向选择器上注册监听事件（接收事件），注册该事件后，当事件到达的时候，selector.select()会返回， 否则会一直阻塞
         * 5. 死循环监听事件
         */
        ServerSocketChannel serverChannel = null;
        serverChannel = ServerSocketChannel.open();
        serverChannel.socket().bind(new InetSocketAddress("localhost", 1234));
        serverChannel.configureBlocking(false);
        Selector selector = Selector.open();
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        /*采用轮训的方式监听selector上是否有需要处理的事件，如果有，进行处理*/
        while (true) {
            /*一直阻塞，知道新连接接入，因为监听的是 accept 事件*/
            selector.select();
        }
    }

}