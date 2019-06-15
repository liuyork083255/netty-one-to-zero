package io.netty.oneToZero.pointClass;

/**
 * {@link io.netty.channel.ChannelFuture}
 *
 * Netty 中的所有 I/O 操作都是异步的。这意味着任何 I/O 调用将立即返回，但不能保证请求的 I/O 操作在调用结束时已经完成。
 * 相反，您将得到一个 ChannelFuture 实例，该实例将提供有关 I/O 操作的结果或状态的信息。
 *
 * ChannelFuture 要么是完成的，要么是未完成，不可能有第三种状态
 * ChannelFuture 的创建机制是 当一个 IO 操作开始的时候，就会被创建，这个新建的 ChannelFuture 是未开始的，既不是 成功，失败，也不是取消。
 *      只有这个 ChannelFuture 完成之后，才会被标识状态为 completed，对应有三种结果：成功 失败 取消
 *      请注意，即使失败和取消也属于完成状态。
 *
 * ChannelFuture 的几个判断状态的核心方法在完成和未完成的返回值却别：
 *                                       +---------------------------+
 *                                       | Completed successfully    |
 *                                       +---------------------------+
 *                                  +---->      isDone() = true      |
 *  +--------------------------+    |    |   isSuccess() = true      |
 *  |        Uncompleted       |    |    +===========================+
 *  +--------------------------+    |    | Completed with failure    |
 *  |      isDone() = false    |    |    +---------------------------+
 *  |   isSuccess() = false    |----+---->      isDone() = true      |
 *  | isCancelled() = false    |    |    |       cause() = non-null  |
 *  |       cause() = null     |    |    +===========================+
 *  +--------------------------+    |    | Completed by cancellation |
 *                                   |    +---------------------------+
 *                                 +---->      isDone() = true      |
 *                                      | isCancelled() = true      |
 *                                       +---------------------------+
 *
 * ChannelFuture 事件完成如果需要被通知，那么可以添加 监听器（监听器是可以被移除的），这种方式是高效的，而不是通过调用 await 方法，后者会阻塞。
 *      监听器的编程方式是 事件驱动，所以如果要理解 ChannelFuture 监听器，需要有良好的 事件驱动 编程习惯。
 *      此外：调用 await 要格外消息，因为可能到导致死锁
 *      告诫：
 *           不要在 ChannelHandler 中调用 await() 方法
 *      原因：
 *          ChannelHandler 中的事件处理程序方法通常由I/O线程调用。如果事件处理程序方法调用了wait()(该方法由I/O线程调用)，
 *          那么它正在等待的I/O操作可能永远不会完成，因为 await() 会阻塞它正在等待的I/O操作，这是一个死锁。
 *          可以这么理解：
 *              netty 的 IO 操作都是异步的，就是提交任务到 task queue 中
 *              比如事件有三个：io事件1，io事件2，io事件3，  io事件1 依赖 io事件2 的完成，此时 io 线程正在执行 io事件1，
 *              那么问题来了：谁来执行 io事件2 ？永远都不会，因为唯一一个线程被阻塞了
 *
 *      遇到这种情况不要使用 await 方法，而是使用 监听器 方式
 *              future.addListener(new ChannelFutureListener(){...})
 *
 *
 * 不要混淆I/O超时和等待超时 !!!
 *      调用  await(long), await(long, TimeUnit), awaitUninterruptibly(long), or awaitUninterruptibly(long, TimeUnit)
 *      这些方法都是等待超时，而不是 IO 操作
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */
public class ChannelFutureS {
}