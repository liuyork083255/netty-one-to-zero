package io.netty.oneToZero.socket;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;

/**
 * @author yangliu48
 */
public class NIOSocketServer {
    public static void main(String[] args) throws Exception {
        Selector selector = Selector.open();
        ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
        ServerSocket serverSocket = serverSocketChannel.socket();
        serverSocket.bind(new InetSocketAddress(666));
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

        while (true) {
            int opCount = selector.select();
            if (opCount <= 0) {
                continue;
            }

            Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
            while (iterator.hasNext()) {
                SelectionKey key = iterator.next();
                if (key.isAcceptable()) { /* 新连接事件 */
                    ServerSocketChannel channel = (ServerSocketChannel)key.channel();
                    SocketChannel clientSocket = channel.accept();
                    clientSocket.configureBlocking(false);
                    clientSocket.register(selector, SelectionKey.OP_READ);
                }
                if (key.isReadable()) { /* 读事件 */
                    SocketChannel clientSocket = (SocketChannel)key.channel();
                    ByteBuffer buffer = ByteBuffer.allocate(1024);
                    clientSocket.read(buffer);
                    System.out.println(new String(buffer.array()));
                }

            }

        }

    }
}
