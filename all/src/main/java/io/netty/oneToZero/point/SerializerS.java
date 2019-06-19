package io.netty.oneToZero.point;

/**
 * 序列化
 *
 * 1 Netty还提供了两个ByteBuf的流封装：ByteBufInputStream, ByteBufOutputStream。比如我们在使用一些序列化工具，
 *  比如Hessian之类的时候，我们往往需要传递一个InputStream(反序列化)，OutputStream(序列化)到这些工具。
 *  而很多协议的实现都涉及大量的内存copy。比如对于反序列化，先将ByteBuf里的数据读取到byte[]，然后包装成ByteArrayInputStream，
 *  而序列化的时候是先将对象序列化成ByteArrayOutputStream再copy到ByteBuf。
 *  而使用ByteBufInputStream和ByteBufOutputStream就不再有这样的内存拷贝了，大大节约了内存开销。
 *
 * 2 socket.write和socket.read都需要一个direct byte buffer(即使你传入的是一个heap byte buffer，
 *  socket内部也会将内容copy到direct byte buffer)。如果我们直接使用ByteBufInputStream和ByteBufOutputStream封装的direct byte buffer再加上Netty 4的内存池，
 *  那么内存将更有效的使用。
 *  这里提一个问题：为什么socket.read和socket.write都需要direct byte buffer呢？heap byte buffer不行？
 *      参考链接：https://www.zhihu.com/question/57374068
 *      原因是：一个 Java 里的 byte[] 对象的引用传给native代码，让 native 代码直接访问数组的内容的话，
 *              就必须要保证native代码在访问的时候这个 byte[] 对象不能被移动，也就是要被“pin”（钉）住。
 *              但是问题是：如果 native 代码正好在访问 byte[] 对象，那么这个对象的地址就发生了改变，所以 JVM 就做了一个权衡，
 *              如果 native 访问的 byte[] 是堆内的，就复制一份到内核空间，那么 native 代码该对象就没有问题了。
 *
 *
 */
public class SerializerS {
}