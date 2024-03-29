package io.netty.oneToZero.point;

/**
 * 都说IO是同步的，NIO是异步的，那到底同步体现在哪里？异步系现在哪里？
 *
 * 同步时：
 *  应用程序（代码）会直接参与IO读写操作，并且我们的应用程序会直接阻塞到某一个方法上，直到数据准备就绪；
 *  或者采用轮询的策略实时检查数据的就绪状态，如果就绪则获取数据。
 *
 * 异步时：
 *  所有的IO读写操作交给操作系统处理，与我们的应用程序没有直接关系，我们的程序不需要关系IO读写，
 *  当操作系统完成IO读写操作时，会给我们应用程序发送通知，我们的应用程序直接拿走数据即可。
 *
 * 也就是说：NIO 异步非阻塞是利用了操作系统的特性，而非仅仅依靠 selector channel buffer 的功能
 *  佐证：《netty 权威指南2》第一章 1.1.1
 *      unix 提供了5种IO模型
 *      阻塞IO
 *      非阻塞IO
 *      IO复用
 *      信号驱动IO
 *      异步IO
 *
 *  unix 其实很早就有非阻塞IO了，只是java一直没有提供相关的类库，知道NIO出来后，才有了异步非阻塞IO
 *  需要注意的是：
 *      阻塞IO是采用了 5种IO模型 的第一种
 *      而NIO是采用了 5种IO模型 的第三种 'IO复用' 技术
 *      IO复用：
 *          程序调用 select 方法阻塞，直到有数据到来，这时候，操作系统会返回可读条件（可以理解成 selectKeys）
 *          程序收到可读条件，然后开始读取数据 selector.selectedKeys() 获取key对象，然后根据key对象可以获取到对应的channel，
 *          从而获取数据 获取读取数据操作完全是操作系统完成，等数据
 *
 *  对比 IO 和 NIO
 *  1 两者都会有阻塞，就是在数据到来之时，但是数据到了之后，IO模型连同读取数据到达且被复制到应用进程的缓冲区时才返回，在此期间一直是阻塞等待的
 *  2 复用selector可以让多个channel注册，所以理论上一个线程就可以完成所有操作，而IO没有selector概念，一个等待就是一个线程，所以会说多少个链接就要多少个线程
 *      因为在 BIO 中，一个 channel 就要对应一个 线程，这个线程只要调用 read 方法，就会阻塞，
 *      但是在 NIO 中，有了 selector 对象，那么多个 channel 就可以被注册到一个 selector 上，并且负责这个 selector 的监听一个线程就可以完成
 *
 */
public class IoNioS {
}