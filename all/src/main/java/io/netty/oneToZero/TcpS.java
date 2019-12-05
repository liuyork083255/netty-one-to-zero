package io.netty.oneToZero;

import java.nio.ByteBuffer;

/**
 * 关于 tcp 的连接中的几个状态
 *
 *  LISTEN：表示监听状态。服务端调用了listen函数，可以开始accept连接了。
 *  SYN_SENT：表示客户端已经发送了SYN报文。当客户端调用connect函数发起连接时，首先发SYN给服务端，然后自己进入SYN_SENT状态，并等待服务端发送ACK+SYN。
 *  SYN_RCVD：表示服务端收到客户端发送SYN报文。服务端收到这个报文后，进入SYN_RCVD状态，然后发送ACK+SYN给客户端。
 *  ESTABLISHED：表示连接已经建立成功了。服务端发送完ACK+SYN后进入该状态，客户端收到ACK后也进入该状态。
 *  FIN_WAIT_1：表示主动关闭连接。无论哪方调用close函数发送FIN报文都会进入这个这个状态。
 *  FIN_WAIT_2：表示被动关闭方同意关闭连接。主动关闭连接方收到被动关闭方返回的ACK后，会进入该状态。
 *  TIME_WAIT：表示收到对方的FIN报文并发送了ACK报文，就等2MSL后即可回到CLOSED状态了。如果FIN_WAIT_1状态下，收到对方同时带FIN标志和ACK标志的报文时，可以直接进入TIME_WAIT状态，而无须经过FIN_WAIT_2状态。
 *  CLOSING：表示双方同时关闭连接。如果双方几乎同时调用close函数，那么会出现双方同时发送FIN报文的情况，此时就会出现CLOSING状态，表示双方都在关闭连接。
 *  CLOSE_WAIT：表示被动关闭方等待关闭。当收到对方调用close函数发送的FIN报文时，回应对方ACK报文，此时进入CLOSE_WAIT状态。
 *  LAST_ACK：表示被动关闭方发送FIN报文后，等待对方的ACK报文状态，当收到ACK后进入CLOSED状态。
 *  CLOSED：表示初始状态。对服务端和C客户端双方都一样。
 *
 */

/**
 * 对于一个长连接，server 和 client 都是正常的情况下，client 突然挂断，server 如何得知?
 *  首先必须了解这个方法 {@link java.nio.channels.ReadableByteChannel#read(ByteBuffer)}
 *  这个方法在非阻塞模式下，会立马返回读取到的字节数
 *      >0: 这种情况最理想，因为读取到了数据，进入业务逻辑
 *      =0: 由于是非阻塞的，并且这个方法可以被任意时间、地点调用，所以很有可能没有读取到数据，返回0，netty 对于这种情况会 break，重新进入 select 操作
 *      -1: 如果返回 -1，就可以理解为该链接断开了，netty 对于这种情况会直接执行 close 方法 {@link AbstractNioChannel.AbstractNioUnsafe#read()}
 *  返回 -1 的源码逻辑并不是开源的，代码在 sun. 包下，参考{@link sun.nio.ch.SocketChannelImpl#read(ByteBuffer)}
 *
 * 那如何得知 clinet 断开的瞬间呢？
 *  1 根据四次挥手协议，主动方断开连接，都是发送 FIN 消息给对方，比如 client 断开，server 会收到这个信息
 *    根据 debug 测试，server 先会读取到一段空格字符串，然后接着会读取到 -1，得知
 *  2 如果网络不稳定，client 断开发送的 FIN 信息很有可能服务端是不能接收到的，所以服务端在第一时间内根本不知道，
 *    所以业界才会采用 心跳 模式来处理这种情况
 *
 *  Note:
 *      如果 client 断开后，server 读取到了 -1 不做处理，那么下次 select() 操作不会阻塞，一直会触发 read 事件，进入死循环
 *
 */

/**
 * 对于一个已经断开连接的 channel，如果让里面写数据会抛出 java.io.IOException: Broken pipe 异常
 *  但是在 tomcat 场景中，一个请求达到 tomcat，服务端还没有处理完成，如果客户端断开连接，服务端再响应，为什么没有发生异常？？？
 *  其实内部已经抛出异常，只不过 tomcat 自己处理了
 *  需要知道几个 tcp 状态
 *      LISTEN      监听状态
 *      ESTABLISHED 正常建立好连接的状态
 *      CLOSE_WAIT  client 主动断开连接，服务端处于的等待关闭状态，这个状态是允许向 socket 发送数据的
 *      CLOSE       关闭状态，如果这个状态，不管是往 socket 中发送还是写数据，都会抛出异常
 *
 *
 *  对于断开情况，都是经历 ESTABLISHED -> CLOSE_WAIT -> CLOSE
 *  其中: CLOSE_WAIT -> CLOSE 状态的改变过程如下:
 *      1 client 断开连接，会触发 server 的 read 事件，这个时候 server 的状态时 CLOSE_WAIT
 *      2 只要 server 往 socket 里写数据，那么 CLOSE_WAIT 就会转变为 CLOSE 状态，不管是写什么
 *   netty 和 tomcat 如何处理上面这种情况?
 *      netty: client 断开连接，会触发 read 事件，然后 server 读取数据，返回 -1，则 server 也断开连接
 *      tomcat: client 断开连接，server 先不管，而是等到 response 响应体写入输入，将状态从 CLOSE_WAIT 转变为 CLOSE
 *              然后 tomcat 在响应完成之后还会有 read 操作，此时已经是 CLOSE 状态了，所以抛出异常，释放资源
 *
 *  详细参考:https://www.cnblogs.com/549294286/p/5100072.html
 *
 */
public class TcpS {


    /**
     *
     */
    void fun1(){}

    /**
     * Tcp 四次挥手
     * 参考连接:https://www.cnblogs.com/549294286/p/5100072.html
     *
     *
     *
     *
     */
    void fun2(){}
}
