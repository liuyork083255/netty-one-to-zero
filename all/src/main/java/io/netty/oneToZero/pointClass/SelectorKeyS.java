package io.netty.oneToZero.pointClass;

import java.nio.channels.SelectionKey;

/**
 * {@link SelectionKey}
 *
 * {@link SelectionKey#OP_ACCEPT}
 *      这个状态是服务端启动后，客户端接入新建连接事件
 *
 * {@link SelectionKey#OP_CONNECT}
 *      这个是指客户端的事件，比如启动一个客户端，需要需要连接服务器；服务端是没有这个状态的
 *
 * {@link SelectionKey#OP_READ}
 *      读事件，当客户端数据传来，那么首先会到达channel的缓冲区，只要缓冲区还有数据，就会一直触发这个事件
 *      因为NIO-Epoll是水平触发事件
 *
 * {@link SelectionKey#OP_WRITE}
 *      写事件，说白了，只要channel注册在selector上，那么这个事件就会被触发，因为判断条件的channel写入缓冲区是否还没有满，
 *      channel基本上 99% 都是可写的，因为写数据本来频率就低，加上写满就更低了，所以服务端基本不监听这个事件，就算监听了
 *      立马也要将感兴趣的事件换为可读的 key.interestOps(SelectionKey.OP_READ); 否则下次 select() 肯定还会选择出来的，基本就会出现死循环
 *      还有就是：在读的时候也是可以直接写入数据的
 *      该问题可以参考 https://www.jianshu.com/p/6bdee8cfee90 《selector 为什么无限触发就绪事件》
 *
 *
 */
public class SelectorKeyS {
}