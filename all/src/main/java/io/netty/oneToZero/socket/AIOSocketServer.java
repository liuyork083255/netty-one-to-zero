package io.netty.oneToZero.socket;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;

/**
 * @author yangliu48
 */
public class AIOSocketServer {


    public static void main(String[] args) throws IOException {

        AsynchronousServerSocketChannel serverSocketChannel = AsynchronousServerSocketChannel.open();

        serverSocketChannel.bind(new InetSocketAddress(666));

        serverSocketChannel.accept(serverSocketChannel, new CompletionHandler<AsynchronousSocketChannel, AsynchronousServerSocketChannel>() {
            @Override
            public void completed(AsynchronousSocketChannel client, AsynchronousServerSocketChannel attachment) {
                ByteBuffer buffer = ByteBuffer.allocate(1024);
                client.read(buffer, client, new CompletionHandler<Integer, AsynchronousSocketChannel>() {
                    @Override
                    public void completed(Integer result, AsynchronousSocketChannel attachment) {
                        System.out.println("thread:" + Thread.currentThread().getName() + ", count:" + result);
                    }

                    @Override
                    public void failed(Throwable exc, AsynchronousSocketChannel attachment) {
                        // ignore
                    }
                });
            }

            @Override
            public void failed(Throwable exc, AsynchronousServerSocketChannel attachment) {
                // ignore
            }

        });


    }

}
