package io.netty.oneToZero.pointClass;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Iterator;
import java.util.Set;

/**
 * {@link Selector}
 *
 *
 *
 * api:
 *  {@link Selector#open()}
 *      创建一个selector对象; 通过 SelectorProvider.provider().openSelector() 系统默认spi创建
 *
 *  {@link Selector#isOpen()}
 *      当前selector 是否已开启
 *
 *  {@link Selector#provider()}
 *      返回创建此通道的provider {@link SelectorProvider}
 *
 *  {@link Selector#keys()}
 *      返回此选择器的key集合
 *      key集合不能直接修改。只有在取消key并注销其通道之后，才会删除key。任何修改key集合的尝试都会引发一个UnsupportedOperationException异常。
 *      key集合不是线程安全的。
 *      这个方法是返回注册在这个selector上的所有key
 *
 *  {@link Selector#selectedKeys()}
 *      这个方法是返回注册在当前selector上感兴趣的key
 *      这个集合的key是不能添加的，只能通过调用 .select() 方法才能往里面添加key
 *      还有就是这个key集合删除方式是能通过 set.remove 和 iterator.remove 两种方式删除，不可能有第三种方式删除
 *      选择密钥集不是线程安全的。
 *
 *  {@link Selector#selectNow()}
 *      选择一组键，其对应的通道已准备好进行I/O操作。
 *      此方法执行非阻塞选择操作。如果自上一个选择操作以来没有通道可选，则此方法立即返回零。
 *      调用此方法将清除以前对wakeup方法的任何调用的效果
 *
 *  {@link Selector#select(long)}
 *
 *  {@link Selector#select()}
 *      这个方式现在理解的还不是很透彻
 *      案例1：ServerSocketChannel 一般都是注册在 selector 上，并且感兴趣的事件是 accept
 *             如果一个连接上来之后，那么 key.isAcceptable() 肯定是true，就应该调用 ServerSocketChannel#accept 方法把
 *             事件消费掉，否则下一次 select() 的时候，这事件还是会被选择出来
 *      案例2: 客户端的 SocketChannel 一般都是注册在 selector 上，并且感兴趣的事件是 读\写 ，如果有读事件发生，
 *             那么应该调用 SocketChannel#read 将channel 中的数据全部消费掉，如果不消费，或者没有完全消费，那么下一次
 *             select() 的时候，这个事件还会被选择出来
 *      这里需要了解NIO的网络编程中的知识点：
 *          Epoll 分为水平触发(也叫条件触发)和边缘触发
 *          详细看 https://blog.csdn.net/lihao21/article/details/67631516?ref=myread
 *
 *
 *  {@link Selector#wakeup()}
 *
 *  {@link Selector#close()}
 *
 *
 * 一个selector会维护三种 SelectionKey
 *  1 调用 keys 方法返回所有注册的key
 *  2 调用 selectedKeys 方法返回当前只感兴趣的key
 *  3 取消过后的key， 就是以前是对读感兴趣的channel，现在取消这个注册事件了，但是当前channel还是注册在上面的，需要等到下一次select方法调用才会取消
 *
 *  2 和 3 中的key集合都是 1 中的key集合的子集
 *  新建的selector，这三个集合都是空的
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

            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            Iterator<SelectionKey> it = selectedKeys.iterator();

            while(it.hasNext()) {
                SelectionKey key = it.next();

                if(key.isAcceptable()) {
                    /*接收连接事件 一定要消费   accept()*/
                } else if (key.isReadable()) {
                    /*读事件 一定要消费 read(ByteBuffer)*/
                } else if (key.isWritable()) {
                    // 通道可写
                }

                it.remove();
            }

        }
    }

}