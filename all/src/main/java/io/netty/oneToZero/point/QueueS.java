package io.netty.oneToZero.point;

/**
 * netty 中的任务队列类型是 {@link org.jctools.queues} 包下的工具类
 *  它的优点详见 {@link io.netty.channel.nio.NioEventLoop#newTaskQueue(int)}
 *  在老的版本中，netty 是自己写的 queue - MpscLinkedQueueNode，后来新版本中全部使用 JCTools 并发队列了
 *
 * 何为 JCTools？
 *  1、JCTools是服务虚拟机并发开发的工具，提供一些JDK没有的并发数据结构辅助开发。
 *  2、是一个聚合四种 SPSC/MPSC/SPMC/MPMC 数据变量的并发队列：
 *      SPSC(Single Producer Single Consumer)：单个生产者对单个消费者（无等待、有界和无界都有实现）
 *      MPSC(Multi Producer Single Consumer)：多个生产者对单个消费者（无锁、有界和无界都有实现）
 *      SPMC(Single Producer Multi Consumer)：单生产者对多个消费者（无锁 有界）
 *      MPMC(Multi Producer Multi Consumer)：多生产者对多个消费者（无锁、有界）
 *  3、SPSC/MPSC 提供了一个在性能，分配和分配规则之间的平衡的关联数组队列；
 *
 *     <dependency>
 *          <groupId>org.jctools</groupId>
 *          <artifactId>jctools-core</artifactId>
 *     </dependency>
 *
 * 简单介绍参考 https://www.jianshu.com/p/119a03332619
 *
 */
public class QueueS {
}