package io.netty.oneToZero.pointClass;

import io.netty.util.Recycler;

/**
 * {@link Recycler}
 *  netty 中轻量级的对象内存池实现
 *  下面是 Recycler 的简单使用  参考链接：https://www.jianshu.com/p/854b855bd198
 *
 *
 */
public class RecyclerS {

    private static final Recycler<User> USER_RECYCLER = new Recycler<User>() {
        @Override
        protected User newObject(Handle<User> handle) {
            return new User(handle);
        }
    };

    /**
     * 测试同一个线程中获取、释放、获取
     */
    public void fun1() {
        // 1、从回收池获取对象
        User user1 = USER_RECYCLER.get();
        // 2、设置对象并使用
        user1.setUsername("hello,java");
        System.out.println(user1);
        // 3、对象恢复出厂设置
        user1.setUsername(null);
        // 4、回收对象到对象池
        user1.recycle();
        // 5、从回收池获取对象
        User user2 = USER_RECYCLER.get();
        System.out.println(user1 == user2); // 输出 true
    }

    /**
     * 测试不同线程中获取、释放、获取
     * 流程说明：
     *  1 线程A 回收 user1 时，直接将 user1 的 DefaultHandle 对象（内部包含user1对象）压入 Stack 的 DefaultHandle[] 中；
     *  2 线程B 回收 user1 时，会首先从其 Map<Stack<?>, WeakOrderQueue> 对象中获取 key=线程A 的 Stack 对象的 WeakOrderQueue，
     *    然后直接将 user1 的 DefaultHandle 对象（内部包含user1对象）压入该 WeakOrderQueue 中的 Link 链表中的尾部 Link 的 DefaultHandle[] 中，
     *    同时，这个 WeakOrderQueue 会与线程A 的 Stack 中的 head 属性进行关联，用于后续对象的 pop 操作；
     *  3 当线程A 从对象池获取对象时，如果线程A 的 Stack 中有对象，则直接弹出；如果没有对象，则先从其 head 属性所指向的 WeakOrderQueue 开始遍历 queue 链表，
     *    将 User 对象从其他线程的 WeakOrderQueue 中转移到线程A 的 Stack 中（一次 pop 操作只转移一个包含了元素的 Link），再弹出。
     *
     * 所以: Stack 中存储的是线程A 回收的对象，以及从线程X 的 WeakOrderQueue 中转移过来的对象。
     *       WeakOrderQueue 中存储的是线程X回收的线程A 创建的对象。
     */
    public void fun2() throws InterruptedException {
        // 1、从回收池获取对象
        final User user1 = USER_RECYCLER.get();
        // 2、设置对象并使用
        user1.setUsername("hello,java");

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                System.out.println(user1);
                // 3、对象恢复出厂设置
                user1.setUsername(null);
                // 4、回收对象到对象池
                user1.recycle();
            }
        });

        thread.start();
        thread.join();

        // 5、从回收池获取对象
        User user2 = USER_RECYCLER.get();
        System.out.println(user1 == user2); // 输出 true
    }

}




class User {
    private String username;
    private final Recycler.Handle<User> handle;

    User(Recycler.Handle<User> handle) {
        this.handle = handle;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public void recycle() {
        handle.recycle(this);
    }
}


