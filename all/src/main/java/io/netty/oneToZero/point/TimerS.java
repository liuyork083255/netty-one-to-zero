package io.netty.oneToZero.point;

import java.util.Timer;

/**
 * {@link Timer}
 *  java 原生 Timer 定时器分析
 *  思想：
 *      0 所有的定时任务都放在 {@link Timer#queue} 中，下一次执行的时间都是按照最小堆数据结构排放
 *      1 每新增一个任务 task 到 queue 中，都会判断这个任务的下一次执行时间 {@link java.util.TaskQueue#fixUp(int)} 是不是最小
 *          这里的判断采用了二分法判断，也即是新增的任务默认加在最后，然后用中间的元素和最后一个比较，采用 while 循环比较
 *      2 线程的休眠时间是获取 queue 堆顶任务，因为它是所有任务中最先要执行的，取出来后和当前时间做一个比较，如果到了就执行，没有到就让线程沉睡这个差值
 *      3 关于计算下次任务执行时间的策略：
 *        这里设置下一次执行时间的算法会根据传入 peroid 的值来判断使用哪种策略：
 *              - 如果 peroid 是负数,那下一次的执行时间就是当前时间 +peroid 的值
 *              - 如果 peroid 是正数，那下一次执行时间就是该任务这次的执行时间 +peroid 的值。
 *          这两个策略的不同点在于，如果计算下次执行时间是以当前时间为基数，那它就不是以固定频率来执行任务的。
 *          因为 Timer 是单线程执行任务的,如果 A 任务执行周期是 10 秒，但是有个 B 任务执行了 20 几秒，
 *          那么下一次 A 任务的执行时间就要等 B 执行完后轮到自己时，再过 10 秒才会执行下一次。
 *
 *        但是看完源码返现，如果构造方法传入负值，则直接异常了，上面说的这种情况不是很理解
 *
 *  Note:
 *      这是 java-util 下的定时器，默认只有一个线程执行，juc 下还提供了基于线程池的定时任务工具类 {@link java.util.concurrent.ScheduledThreadPoolExecutor}
 *      该类相接见：https://www.jianshu.com/p/dd2971b47af4
 *
 */
public class TimerS {
    /**
     * {@link Timer#queue} 用作保存要执行的定时任务，数据结构是最小堆，最先指定的任务会放在最前面
     */

    /**
     * {@link Timer#cancel} 用作保存要执行的定时任务，数据结构是最小堆，最先指定的任务会放在最前面
     */

    /**
     * {@link java.util.TimerThread#newTasksMayBeScheduled} 简单理解：true表示当前定时器还有效， false表示当前定时器无效
     */

    /** 分析核心方法 {@link java.util.TimerThread#mainLoop()} */
    /*private void mainLoop() {
        while (true) {
            try {
                TimerTask task;
                boolean taskFired;
                synchronized(queue) {
                    // 如果任务队列为空，并且 newTasksMayBeScheduled 为 true，那么就会一直休眠等待，知道有任务进来唤醒这个线程
                    // 如果 newTasksMayBeScheduled 为 false，表示定时器无效了，这时候如果 queue 为空，则直接退出，意味着退出线程的 run 方法
                    while (queue.isEmpty() && newTasksMayBeScheduled)
                        queue.wait();
                    if (queue.isEmpty())
                        break;

                    // 获取当前时间和下次任务的执行时间
                    long currentTime, executionTime;
                    // 获取队列中最早要执行的任务，其实就是从 queue[] 数组中获取第一个元素，虽然是数组，但是获取的是 queue[1]
                    task = queue.getMin();
                    synchronized(task.lock) {
                        //如果这个任务已经被结束了，就从队列中移除
                        if (task.state == TimerTask.CANCELLED) {
                            queue.removeMin();
                            continue;
                        }
                        // 获取当前时间
                        currentTime = System.currentTimeMillis();
                        // 获取下次任务执行时间，这时间是任务 task 被加入到 queue 中就会被计算出来的
                        executionTime = task.nextExecutionTime;

                        // 判断任务执行时间是否小于当前时间,如果小于就说明可以执行了
                        // taskFired = true：表示任务需要被执行
                        // taskFired = false：表示任务不需要被执行
                        if (taskFired = (executionTime<=currentTime)) {
                            // 如果任务的执行周期是0,说明只要执行一次就好了,就从队列中移除它,这样下一次就不会获取到该任务了
                            if (task.period == 0) {
                                queue.removeMin();
                                // 标记这个状态为已执行
                                task.state = TimerTask.EXECUTED;
                            } else {
                                // 如果这个任务的指定周期不是0，说明下次还要执行
                                queue.rescheduleMin(task.period < 0 ? currentTime   - task.period : executionTime + task.period);
                            }
                        }
                    }
                    // 如果任务的执行时间还没到，就计算出还有多久才到达执行时间,然后线程进入休眠
                    if (!taskFired)
                        queue.wait(executionTime - currentTime);
                }
                // 真正执行用户任务逻辑
                if (taskFired)
                    task.run();
            } catch(InterruptedException e) {
            }
        }
    }*/

}