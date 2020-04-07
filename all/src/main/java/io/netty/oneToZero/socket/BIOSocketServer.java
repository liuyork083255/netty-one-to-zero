package io.netty.oneToZero.socket;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * @author yangliu48
 */
public class BIOSocketServer {
    public static void main(String[] args) throws Exception {
        ServerSocket serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress(666));
        while (true) {
            final Socket socket = serverSocket.accept(); /* main 线程阻塞 */
            new Thread(new Runnable() { /* 如果不新建 thread 会发生 ？ */
                @Override
                public void run() {
                    try {
                        InputStream inputStream = socket.getInputStream();
                        byte[] bytes = new byte[1024];
                        while (true) {
                            int read = inputStream.read(bytes); /* 子线程阻塞 */
                            System.out.println(Thread.currentThread().getName() + ", read:" + read);
                        }
                    } catch (Exception e) { }
                }
            }).start();
        }
    }
}
