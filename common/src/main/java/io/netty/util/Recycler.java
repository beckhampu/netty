/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.util;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.util.internal.MathUtil.safeFindNextPositivePowerOfTwo;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * Light-weight object pool based on a thread-local stack.
 *
 * @param <T> the type of the pooled object
 */
public abstract class Recycler<T> {
    
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(Recycler.class);
    
    /**
     * 表示一个不需要回收的包装对象
     */
    @SuppressWarnings("rawtypes")
    private static final Handle NOOP_HANDLE = new Handle() {
        @Override
        public void recycle(Object object) {
            // NOOP
        }
    };
    
    /**
     * 唯一ID生成器，用于WeakOrderQueue的ID生成
     *
     */
    private static final AtomicInteger ID_GENERATOR = new AtomicInteger(Integer.MIN_VALUE);
    
    private static final int OWN_THREAD_ID = ID_GENERATOR.getAndIncrement();
    
    /**
     * 每个stack的初始化默认最大容量
     */
    private static final int DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD = 32768; // Use 32k instances as default.
    
    /**
     *每个stack的默认最大容量
     */
    private static final int DEFAULT_MAX_CAPACITY_PER_THREAD;
    
    /**
     * 每个Stack默认的初始容量，默认为256
     * 后续根据需要进行扩容，直到<=MAX_CAPACITY_PER_THREAD
     */
    private static final int INITIAL_CAPACITY;
    
    /**
     * 最大可共享的容量因子。
     * 最大可共享的容量 = maxCapacity / maxSharedCapacityFactor，maxSharedCapacityFactor默认为2
     */
    private static final int MAX_SHARED_CAPACITY_FACTOR;
    
    /**
     * 每个线程可拥有多少个WeakOrderQueue，默认为2*cpu核数
     * 实际上就是当前线程的Map<Stack<?>, WeakOrderQueue>的size最大值
     */
    private static final int MAX_DELAYED_QUEUES_PER_THREAD;
    
    /**
     * WeakOrderQueue中的Link中的数组DefaultHandle<?>[] elements容量，默认为16，
     * 当一个Link中的DefaultHandle元素达到16个时，会新创建一个Link进行存储，这些Link组成链表，当然
     * 所有的Link加起来的容量要<=最大可共享容量。
     */
    private static final int LINK_CAPACITY;
    
    /**
     * 回收因子，默认为8。
     * 即默认每8个对象，允许回收一次，直接扔掉7个，可以让recycler的容量缓慢的增大，避免爆发式的请求
     */
    private static final int RATIO;
    
    static {
        // In the future, we might have different maxCapacity for different object types.
        // e.g. io.netty.recycler.maxCapacity.writeTask
        //      io.netty.recycler.maxCapacity.outboundBuffer
        int maxCapacityPerThread = SystemPropertyUtil.getInt("io.netty.recycler.maxCapacityPerThread",
                SystemPropertyUtil.getInt("io.netty.recycler.maxCapacity", DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD));
        if (maxCapacityPerThread < 0) {
            maxCapacityPerThread = DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD;
        }
        
        DEFAULT_MAX_CAPACITY_PER_THREAD = maxCapacityPerThread;
        
        MAX_SHARED_CAPACITY_FACTOR = max(2,
                SystemPropertyUtil.getInt("io.netty.recycler.maxSharedCapacityFactor",
                        2));
        
        MAX_DELAYED_QUEUES_PER_THREAD = max(0,
                SystemPropertyUtil.getInt("io.netty.recycler.maxDelayedQueuesPerThread",
                        // We use the same value as default EventLoop number
                        NettyRuntime.availableProcessors() * 2));
        
        LINK_CAPACITY = safeFindNextPositivePowerOfTwo(
                max(SystemPropertyUtil.getInt("io.netty.recycler.linkCapacity", 16), 16));
        
        // By default we allow one push to a Recycler for each 8th try on handles that were never recycled before.
        // This should help to slowly increase the capacity of the recycler while not be too sensitive to allocation
        // bursts.
        RATIO = safeFindNextPositivePowerOfTwo(SystemPropertyUtil.getInt("io.netty.recycler.ratio", 8));
        
        if (logger.isDebugEnabled()) {
            if (DEFAULT_MAX_CAPACITY_PER_THREAD == 0) {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: disabled");
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: disabled");
                logger.debug("-Dio.netty.recycler.linkCapacity: disabled");
                logger.debug("-Dio.netty.recycler.ratio: disabled");
            } else {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: {}", DEFAULT_MAX_CAPACITY_PER_THREAD);
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: {}", MAX_SHARED_CAPACITY_FACTOR);
                logger.debug("-Dio.netty.recycler.linkCapacity: {}", LINK_CAPACITY);
                logger.debug("-Dio.netty.recycler.ratio: {}", RATIO);
            }
        }
        
        INITIAL_CAPACITY = min(DEFAULT_MAX_CAPACITY_PER_THREAD, 256);
    }
    
    private final int maxCapacityPerThread;
    private final int maxSharedCapacityFactor;
    
    /**
     * 默认为8-1=7，即2^3-1，控制每8个元素只有一个可以被recycle，其余7个被扔掉
     */
    private final int ratioMask;
    
    /**
     * Map<Stack<?>, WeakOrderQueue>的size大小，默认为2*cpu核数
     */
    private final int maxDelayedQueuesPerThread;
    
    /**
     * 存储本线程回收的对象。对象的获取和回收对应Stack的pop和push
     */
    private final FastThreadLocal<Stack<T>> threadLocal = new FastThreadLocal<Stack<T>>() {
    
        // Stack存储对象的数据结构。对象池的真正的 “池”
        @Override
        protected Stack<T> initialValue() {
            return new Stack<T>(Recycler.this, Thread.currentThread(), maxCapacityPerThread, maxSharedCapacityFactor,
                    ratioMask, maxDelayedQueuesPerThread);
        }
    };
    
    protected Recycler() {
        this(DEFAULT_MAX_CAPACITY_PER_THREAD);
    }
    
    protected Recycler(int maxCapacityPerThread) {
        this(maxCapacityPerThread, MAX_SHARED_CAPACITY_FACTOR);
    }
    
    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor) {
        this(maxCapacityPerThread, maxSharedCapacityFactor, RATIO, MAX_DELAYED_QUEUES_PER_THREAD);
    }
    
    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor,
                       int ratio, int maxDelayedQueuesPerThread) {
        ratioMask = safeFindNextPositivePowerOfTwo(ratio) - 1;
        if (maxCapacityPerThread <= 0) {
            this.maxCapacityPerThread = 0;
            this.maxSharedCapacityFactor = 1;
            this.maxDelayedQueuesPerThread = 0;
        } else {
            this.maxCapacityPerThread = maxCapacityPerThread;
            this.maxSharedCapacityFactor = max(1, maxSharedCapacityFactor);
            this.maxDelayedQueuesPerThread = max(0, maxDelayedQueuesPerThread);
        }
    }
    
    @SuppressWarnings("unchecked")
    public final T get() {
        // 若maxCapacityPerThread为0，则创建一个不需要回收的对象
        if (maxCapacityPerThread == 0) {
            return newObject((Handle<T>) NOOP_HANDLE);
        }
        
        // 从当前线程中获取到Stack对象
        Stack<T> stack = threadLocal.get();
        
        // 从stack中获取到一个handle对象
        DefaultHandle<T> handle = stack.pop();
        
        // 若从stack中没有获取到对象，则在stack中创建一个新对象
        if (handle == null) {
            handle = stack.newHandle();
            handle.value = newObject(handle);
        }
        return (T) handle.value;
    }
    
    /**
     * @deprecated use {@link Handle#recycle(Object)}.
     */
    @Deprecated
    public final boolean recycle(T o, Handle<T> handle) {
        if (handle == NOOP_HANDLE) {
            return false;
        }
        
        DefaultHandle<T> h = (DefaultHandle<T>) handle;
        if (h.stack.parent != this) {
            return false;
        }
        
        h.recycle(o);
        return true;
    }
    
    final int threadLocalCapacity() {
        return threadLocal.get().elements.length;
    }
    
    final int threadLocalSize() {
        return threadLocal.get().size;
    }
    
    protected abstract T newObject(Handle<T> handle);
    
    /**
     * 回收处理方法声明接口
     * @param <T>
     */
    public interface Handle<T> {
        void recycle(T object);
    }
    
    /**
     * 对象的包装类，在Recycler中缓存的对象都会包装成DefaultHandle
     *
     * @param <T>
     */
    static final class DefaultHandle<T> implements Handle<T> {
    
        /**
         * pushNow() = OWN_THREAD_ID
         * 在pushLater中的add(DefaultHandle handle)操作中 == id（当前的WeakOrderQueue的唯一ID）
         * 在pop()中置为0
         */
        private int lastRecycledId;
    
        /**
         * 只有在pushNow()中会设置值OWN_THREAD_ID
         * 在pop()中置为0
         */
        private int recycleId;
    
        /**
         * 标记是否已经被回收：
         * 该值仅仅用于控制是否执行 (++handleRecycleCount & ratioMask) != 0 这段逻辑，而不会用于阻止重复回收的操作，
         * 重复回收的操作由item.recycleId | item.lastRecycledId来阻止
         */
        boolean hasBeenRecycled;
    
        /**
         * 当前的DefaultHandle对象所属的Stack
         */
        private Stack<?> stack;
    
        /**
         * 真实的对象
         */
        private Object value;
        
        DefaultHandle(Stack<?> stack) {
            this.stack = stack;
        }
        
        @Override
        public void recycle(Object object) {
            if (object != value) {
                throw new IllegalArgumentException("object does not belong to handle");
            }
            // 调用stack的push方法回收对象
            stack.push(this);
        }
    }
    
    /**
     * 1、每个Recycler类（而不是每一个Recycler对象）都有一个DELAYED_RECYCLED
     * 原因：可以根据一个Stack<T>对象唯一的找到一个WeakOrderQueue对象，所以此处不需要每个对象建立一个DELAYED_RECYCLED
     * 2、由于DELAYED_RECYCLED是一个类变量，所以需要包容多个T，此处泛型需要使用?
     * 3、WeakHashMap：当Stack没有强引用可达时，整个Entry{Stack<?>, WeakOrderQueue}都会加入相应的弱引用队列等待回收
     */
    private static final FastThreadLocal<Map<Stack<?>, WeakOrderQueue>> DELAYED_RECYCLED =
            new FastThreadLocal<Map<Stack<?>, WeakOrderQueue>>() {
                @Override
                protected Map<Stack<?>, WeakOrderQueue> initialValue() {
                    return new WeakHashMap<Stack<?>, WeakOrderQueue>();
                }
            };
    
    // a queue that makes only moderate guarantees about visibility: items are seen in the correct order,
    // but we aren't absolutely guaranteed to ever see anything at all, thereby keeping the queue cheap to maintain
    private static final class WeakOrderQueue {
    
        /**
         * 如果DELAYED_RECYCLED中的key-value对已经达到了maxDelayedQueues，
         * 对于后续的Stack，其对应的WeakOrderQueue设置为DUMMY，
         * 后续如果检测到DELAYED_RECYCLED中对应的Stack的value是WeakOrderQueue.DUMMY时，直接返回，不做存储操作
         */
        static final WeakOrderQueue DUMMY = new WeakOrderQueue();
        
        // Let Link extend AtomicInteger for intrinsics. The Link itself will be used as writerIndex.
        @SuppressWarnings("serial")
        private static final class Link extends AtomicInteger {
            private final DefaultHandle<?>[] elements = new DefaultHandle[LINK_CAPACITY];
            
            private int readIndex;
            private Link next;
        }
        
        // chain of data items
        private Link head, tail;
        // pointer to another queue of delayed items for the same stack
        private WeakOrderQueue next;
        private final WeakReference<Thread> owner;
    
        /**
         * WeakOrderQueue的唯一ID
         */
        private final int id = ID_GENERATOR.getAndIncrement();
        private final AtomicInteger availableSharedCapacity;
        
        private WeakOrderQueue() {
            owner = null;
            availableSharedCapacity = null;
        }
        
        private WeakOrderQueue(Stack<?> stack, Thread thread) {
            head = tail = new Link();
            owner = new WeakReference<Thread>(thread);
            
            // Its important that we not store the Stack itself in the WeakOrderQueue as the Stack also is used in
            // the WeakHashMap as key. So just store the enclosed AtomicInteger which should allow to have the
            // Stack itself GCed.
            availableSharedCapacity = stack.availableSharedCapacity;
        }
        
        static WeakOrderQueue newQueue(Stack<?> stack, Thread thread) {
            //创建一个WeakOrderQueue
            WeakOrderQueue queue = new WeakOrderQueue(stack, thread);
            // Done outside of the constructor to ensure WeakOrderQueue.this does not escape the constructor and so
            // may be accessed while its still constructed.
            
            // 将此queue设置给stack的head属性
            stack.setHead(queue);
            return queue;
        }
        
        private void setNext(WeakOrderQueue next) {
            assert next != this;
            this.next = next;
        }
        
        /**
         * Allocate a new {@link WeakOrderQueue} or return {@code null} if not possible.
         */
        static WeakOrderQueue allocate(Stack<?> stack, Thread thread) {
            // We allocated a Link so reserve the space
            return reserveSpace(stack.availableSharedCapacity, LINK_CAPACITY)
                    ? WeakOrderQueue.newQueue(stack, thread) : null;
        }
    
        /**
         * 判断储备内存是否够用
         */
        private static boolean reserveSpace(AtomicInteger availableSharedCapacity, int space) {
            assert space >= 0;
            for (; ; ) {
                int available = availableSharedCapacity.get();
                // 首先判断当前的Stack对象的可用共享内存（初始默认值为2k）是否还有足够的空间（LINK_CAPACITY，默认为16）来存放一个Link，如果不够了，直接返回；
                // 否则，使用cas+无限重试的方式设置Stack的可用共享内存 = 当前的可用共享内存大小 - 16，也就是分配一个Link大小的空间
                if (available < space) {
                    return false;
                }
                if (availableSharedCapacity.compareAndSet(available, available - space)) {
                    return true;
                }
            }
        }
        
        private void reclaimSpace(int space) {
            assert space >= 0;
            availableSharedCapacity.addAndGet(space);
        }
        
        void add(DefaultHandle<?> handle) {
            handle.lastRecycledId = id;
            
            Link tail = this.tail;
            int writeIndex;
            if ((writeIndex = tail.get()) == LINK_CAPACITY) {
                if (!reserveSpace(availableSharedCapacity, LINK_CAPACITY)) {
                    // Drop it.
                    return;
                }
                // We allocate a Link so reserve the space
                this.tail = tail = tail.next = new Link();
                
                writeIndex = tail.get();
            }
            tail.elements[writeIndex] = handle;
            handle.stack = null;
            // we lazy set to ensure that setting stack to null appears before we unnull it in the owning thread;
            // this also means we guarantee visibility of an element in the queue if we see the index updated
            tail.lazySet(writeIndex + 1);
        }
        
        boolean hasFinalData() {
            return tail.readIndex != tail.get();
        }
        
        // transfer as many items as we can from this queue to the stack, returning true if any were transferred
        @SuppressWarnings("rawtypes")
        /**
         * 将queue中对象迁移到创建对象的当前stack中
         */
        boolean transfer(Stack<?> dst) {
            //取queue中的第一个link
            Link head = this.head;
            
            //第一个link为空，则返回false
            if (head == null) {
                return false;
            }
    
            // 如果第一个Link节点的readIndex索引已经到达该Link对象的DefaultHandle[]的尾部，
            // 则判断当前的Link节点的下一个节点是否为null，如果为null，说明已经达到了Link链表尾部，直接返回，
            // 否则，将当前的Link节点的下一个Link节点赋值给head和this.head.link，进而对下一个Link节点进行操作
            if (head.readIndex == LINK_CAPACITY) {
                if (head.next == null) {
                    return false;
                }
                this.head = head = head.next;
            }
            
            // 获取Link节点的readIndex,即当前的Link节点的第一个有效元素的位置
            final int srcStart = head.readIndex;
    
            // 获取Link节点的writeIndex，即当前的Link节点的最后一个有效元素的位置
            int srcEnd = head.get();
            
            // 计算Link节点中可以被转移的元素个数
            final int srcSize = srcEnd - srcStart;
            if (srcSize == 0) {
                return false;
            }
    
            // 获取转移元素的目的地Stack中当前的元素个数
            final int dstSize = dst.size;
    
            // 计算期盼的容量
            final int expectedCapacity = dstSize + srcSize;
    
            /**
             * 如果expectedCapacity大于目的地Stack中对象数组的长度
             * 1、对目的地Stack进行扩容
             * 2、计算Link中最终的可转移的最后一个元素的下标
             */
            if (expectedCapacity > dst.elements.length) {
                final int actualCapacity = dst.increaseCapacity(expectedCapacity);
                srcEnd = min(srcStart + actualCapacity - dstSize, srcEnd);
            }
            
            if (srcStart != srcEnd) {
                
                // 获取Link节点的DefaultHandle[]
                final DefaultHandle[] srcElems = head.elements;
                
                // 获取目的地Stack的DefaultHandle[]
                final DefaultHandle[] dstElems = dst.elements;
    
                // 记录dst数组的大小，会随着元素的迁入而增加，如果最后发现没有增加，那么表示没有迁移成功任何一个元素
                int newDstSize = dstSize;
                
                //进行对象迁移，从link的element数组中的起始位置到标记终止位置
                for (int i = srcStart; i < srcEnd; i++) {
                    DefaultHandle element = srcElems[i];
    
                    /**
                     * 设置element.recycleId 或者 进行防护性判断
                     */
                    if (element.recycleId == 0) {
                        element.recycleId = element.lastRecycledId;
                    } else if (element.recycleId != element.lastRecycledId) {
                        throw new IllegalStateException("recycled already");
                    }
    
                    // 置空Link节点的DefaultHandle[i]
                    srcElems[i] = null;
                    
                    // 判断是否需要丢弃
                    if (dst.dropHandle(element)) {
                        // Drop the object.
                        continue;
                    }
                    
                    // 将可转移成功的DefaultHandle元素的stack属性设置为目的地Stack
                    element.stack = dst;
                    // 将DefaultHandle元素转移到目的地Stack的DefaultHandle[newDstSize ++]中
                    dstElems[newDstSize++] = element;
                }
                
                // 如果当前link元素以全部迁移，这要进行回收
                // 将Head指向下一个Link，也就是将当前的Link给回收掉了
                // 假设之前为Head -> Link1 -> Link2，回收之后为Head -> Link2
                if (srcEnd == LINK_CAPACITY && head.next != null) {
                    // Add capacity back as the Link is GCed.
                    reclaimSpace(LINK_CAPACITY);
                    
                    this.head = head.next;
                }
                
                //当前link还有元素，则重置readIndex
                head.readIndex = srcEnd;
                if (dst.size == newDstSize) {
                    return false;
                }
                dst.size = newDstSize;
                return true;
            } else {
                // The destination stack is full already.
                return false;
            }
        }
        
        @Override
        protected void finalize() throws Throwable {
            try {
                super.finalize();
            } finally {
                // We need to reclaim all space that was reserved by this WeakOrderQueue so we not run out of space in
                // the stack. This is needed as we not have a good life-time control over the queue as it is used in a
                // WeakHashMap which will drop it at any time.
                Link link = head;
                while (link != null) {
                    reclaimSpace(LINK_CAPACITY);
                    link = link.next;
                }
            }
        }
    }
    
    /**
     * 存储对象的结构类
     * @param <T>
     */
    static final class Stack<T> {
        
        // we keep a queue of per-thread queues, which is appended to once only, each time a new thread other
        // than the stack owner recycles: when we run out of items in our stack we iterate this collection
        // to scavenge those that can be reused. this permits us to incur minimal thread synchronisation whilst
        // still recycling all items.
    
        /**
         * 所属的Recycler对象
         */
        final Recycler<T> parent;
    
        /**
         * stack所属的线程
         */
        final Thread thread;
    
        /**
         * 可用的共享内存大小，默认为maxCapacity/maxSharedCapacityFactor = 4k/2 = 2k = 2048
         * 假设当前的Stack是线程A的，则其他线程B~X等去回收线程A创建的对象时，可回收最多A创建的多少个对象
         * 注意：实际上线程A创建的对象最终最多可以被回收maxCapacity + availableSharedCapacity个，默认为6k个
         *
         * why AtomicInteger?
         * 当线程B和线程C同时创建线程A的WeakOrderQueue的时候，会同时分配内存，需要同时操作availableSharedCapacity
         * 具体见：WeakOrderQueue.allocate
         */
        final AtomicInteger availableSharedCapacity;
    
        /**
         * DELAYED_RECYCLED中最多可存储的{Stack，WeakOrderQueue}键值对个数
         */
        final int maxDelayedQueues;
    
        /**
         * elements最大的容量：默认最大为4k，4096
         */
        private final int maxCapacity;
    
        /**
         * 默认为8-1=7，即2^3-1，控制每8个元素只有一个可以被recycle，其余7个被扔掉
         */
        private final int ratioMask;
    
        /**
         * 存储对象的数组
         */
        private DefaultHandle<?>[] elements;
    
        /**
         * elements中的元素个数，同时也可作为操作数组的下标
         * 数组只有elements.length来计算数组容量的函数，没有计算当前数组中的元素个数的函数，所以需要我们去记录，不然需要每次都去计算
         */
        private int size;
    
        /**
         * 每有一个元素将要被回收, 则该值+1，例如第一个被回收的元素的handleRecycleCount=handleRecycleCount+1=0
         * 与ratioMask配合，用来决定当前的元素是被回收还是被drop。
         * 例如 ++handleRecycleCount & ratioMask（7），其实相当于 ++handleRecycleCount % 8，
         * 则当 ++handleRecycleCount = 0/8/16/...时，元素被回收，其余的元素直接被drop
         */
        private int handleRecycleCount = -1; // Start with -1 so the first one will be recycled.
    
        /**
         * cursor：当前操作的WeakOrderQueue
         * prev：cursor的前一个WeakOrderQueue
         */
        private WeakOrderQueue cursor, prev;
    
        /**
         * 该值是当线程B回收线程A创建的对象时，线程B会为线程A的Stack对象创建一个WeakOrderQueue对象，
         * 该WeakOrderQueue指向这里的head，用于后续线程A对对象的查找操作
         * Q: why volatile?
         * A: 假设线程A正要读取对象X，此时需要从其他线程的WeakOrderQueue中读取，假设此时线程B正好创建Queue，并向Queue中放入一个对象X；假设恰好次Queue就是线程A的Stack的head
         * 使用volatile可以立即读取到该queue。
         *
         * 对于head的设置，具有同步问题。具体见此处pre的volatile和synchronized void setHead(WeakOrderQueue queue)
         */
        private volatile WeakOrderQueue head;
        
        Stack(Recycler<T> parent, Thread thread, int maxCapacity, int maxSharedCapacityFactor,
              int ratioMask, int maxDelayedQueues) {
            this.parent = parent;
            this.thread = thread;
            this.maxCapacity = maxCapacity;
            availableSharedCapacity = new AtomicInteger(max(maxCapacity / maxSharedCapacityFactor, LINK_CAPACITY));
            elements = new DefaultHandle[min(INITIAL_CAPACITY, maxCapacity)];
            this.ratioMask = ratioMask;
            this.maxDelayedQueues = maxDelayedQueues;
        }
        
        // Marked as synchronized to ensure this is serialized.
        /**
         * 假设线程B和线程C同时回收线程A的对象时，有可能会同时newQueue，就可能同时setHead，所以这里需要加锁
         * 以head==null的时候为例，
         * 加锁：
         * 线程B先执行，则head = 线程B的queue；之后线程C执行，此时将当前的head也就是线程B的queue作为线程C的queue的next，组成链表，之后设置head为线程C的queue
         * 不加锁：
         * 线程B先执行queue.setNext(head);此时线程B的queue.next=null->线程C执行queue.setNext(head);线程C的queue.next=null
         * -> 线程B执行head = queue;设置head为线程B的queue -> 线程C执行head = queue;设置head为线程C的queue
         *
         * 注意：此时线程B和线程C的queue没有连起来，则之后的pop()就不会从B进行查询。（B就是资源泄露）
         */
        synchronized void setHead(WeakOrderQueue queue) {
            queue.setNext(head);
            head = queue;
        }
        
        int increaseCapacity(int expectedCapacity) {
            // 获取当前容量
            int newCapacity = elements.length;
            
            // 获取最大容量
            int maxCapacity = this.maxCapacity;
            
            // 设置newCapacity，保证设置newCapacity大于期望，小于最大容量
            do {
                newCapacity <<= 1;
            } while (newCapacity < expectedCapacity && newCapacity < maxCapacity);
            
            
            newCapacity = min(newCapacity, maxCapacity);
            
            // 扩容拷贝
            if (newCapacity != elements.length) {
                elements = Arrays.copyOf(elements, newCapacity);
            }
            
            return newCapacity;
        }
    
        /**
         * 获取对象
         * @return
         */
        @SuppressWarnings({"unchecked", "rawtypes"})
        DefaultHandle<T> pop() {
            int size = this.size;
            
            // 如果size为0,则从其他线程的与当前的Stack对象关联的WeakOrderQueue中获取元素，并转移到Stack的DefaultHandle[]中
            if (size == 0) {
                if (!scavenge()) {
                    // 获取失败，返回
                    return null;
                }
                // 转移了数据，stack的数组size发生了变化，重新获取
                size = this.size;
            }
            // size减一
            size--;
            //获取一个对象，准备出栈
            DefaultHandle ret = elements[size];
            elements[size] = null;
            if (ret.lastRecycledId != ret.recycleId) {
                throw new IllegalStateException("recycled multiple times");
            }
            ret.recycleId = 0;
            ret.lastRecycledId = 0;
            this.size = size;
            return ret;
        }
    
        /**
         * 转移获取，从其他线程的WeakOrderQueue中获取并移到stack中
         *
         * @return
         */
        boolean scavenge() {
            // continue an existing scavenge, if any
            if (scavengeSome()) {
                return true;
            }
            
            // reset our scavenge cursor
            // 异步线程获取对象迁移失败，重置当前stack的queue的游标。
            prev = null;
            cursor = head;
            return false;
        }
    
        /**
         * 从其他线程中获取对象迁移到stack的数组中
         *
         * @return
         */
        boolean scavengeSome() {
            WeakOrderQueue prev;
            WeakOrderQueue cursor = this.cursor;
            
            //将当前游标cursor赋值为head
            if (cursor == null) {
                prev = null;
                cursor = head;
                // 如果head为空，表示当前的stack对象没有WeakOrderQueue，直接返回，无法获取对象迁移
                if (cursor == null) {
                    return false;
                }
            } else {
                // 设置prev
                prev = this.prev;
            }
            
            boolean success = false;
            do {
                if (cursor.transfer(this)) {
                    //当前的queue获取对象并迁移成功，则退出循环
                    success = true;
                    break;
                }
    
                // 遍历下一个WeakOrderQueue，进行下一次循环
                WeakOrderQueue next = cursor.next;
    
                /**
                 * 当前queue已经获取不到对象了，则做清理工作。
                 */
                if (cursor.owner.get() == null) {
                    /**
                     * 如果当前的WeakOrderQueue的线程已经不可达了，则
                     * 1、如果该WeakOrderQueue中有数据，则将其中的数据全部转移到当前Stack中
                     * 2、将当前的WeakOrderQueue的前一个节点prev指向当前的WeakOrderQueue的下一个节点，即将当前的WeakOrderQueue从Queue链表中移除。方便后续GC
                     */
                    
                    // If the thread associated with the queue is gone, unlink it, after
                    // performing a volatile read to confirm there is no data left to collect.
                    // We never unlink the first queue, as we don't want to synchronize on updating the head.
                    if (cursor.hasFinalData()) {
                        for (; ; ) {
                            if (cursor.transfer(this)) {
                                success = true;
                            } else {
                                break;
                            }
                        }
                    }
                    
                    if (prev != null) {
                        prev.setNext(next);
                    }
                } else {
                    // 关联线程还在，赋值给prev
                    prev = cursor;
                }
                
                cursor = next;
                
            } while (cursor != null && !success);
            
            // 设置查找的queue的游标
            this.prev = prev;
            this.cursor = cursor;
            return success;
        }
        
        void push(DefaultHandle<?> item) {
            Thread currentThread = Thread.currentThread();
            if (thread == currentThread) {
                // The current Thread is the thread that belongs to the Stack, we can try to push the object now.
                // 同线程回收
                pushNow(item);
            } else {
                // The current Thread is not the one that belongs to the Stack, we need to signal that the push
                // happens later.
                // 异线程回收
                pushLater(item, currentThread);
            }
        }
    
        /**
         * 同线程回收，立刻将item元素压入Stack中
         * @param item
         */
        private void pushNow(DefaultHandle<?> item) {
            // (item.recycleId | item.lastRecycleId) != 0 等价于 item.recycleId!=0 && item.lastRecycleId!=0
            // 当item开始创建时item.recycleId==0 && item.lastRecycleId==0
            // 当item被recycle时，item.recycleId==x，item.lastRecycleId==y 进行赋值
            // 当item被pop之后， item.recycleId = item.lastRecycleId = 0
            // 所以当item.recycleId 和 item.lastRecycleId 任何一个不为0，则表示回收过
            if ((item.recycleId | item.lastRecycledId) != 0) {
                throw new IllegalStateException("recycled already");
            }
            
            //recycleId和lastRecycledId赋值
            item.recycleId = item.lastRecycledId = OWN_THREAD_ID;
            
            int size = this.size;
            
            // 如果超过容量或需要丢弃，则直接return，不进行回收
            if (size >= maxCapacity || dropHandle(item)) {
                // Hit the maximum capacity or should drop - drop the possibly youngest object.
                return;
            }
    
            // 若达到当前数组的长度，则stack中的elements扩容两倍，复制元素，将新数组赋值给stack.elements
            if (size == elements.length) {
                elements = Arrays.copyOf(elements, min(size << 1, maxCapacity));
            }
            
            // 将新元素放入stack的数组中，设置size的大小
            elements[size] = item;
            this.size = size + 1;
        }
    
        /**
         * 异线程回收，先将item元素加入WeakOrderQueue，后续再从WeakOrderQueue中将元素压入Stack中
         *
         * @param item
         * @param thread
         */
        private void pushLater(DefaultHandle<?> item, Thread thread) {
            // we don't want to have a ref to the queue as the value in our weak map
            // so we null it out; to ensure there are no races with restoring it later
            // we impose a memory ordering here (no-op on x86)
            
            //从DELAYED_RECYCLED中获取到map
            Map<Stack<?>, WeakOrderQueue> delayedRecycled = DELAYED_RECYCLED.get();
            
            //根据此stack获取WeakOrderQueue
            WeakOrderQueue queue = delayedRecycled.get(this);
            if (queue == null) {
                // 如果DELAYED_RECYCLED中的key-value对已经达到了maxDelayedQueues，则后续的无法回收 - 内存保护
                if (delayedRecycled.size() >= maxDelayedQueues) {
                    // Add a dummy queue so we know we should drop the object
                    // 对设置DUMMY占位
                    delayedRecycled.put(this, WeakOrderQueue.DUMMY);
                    return;
                }
                // Check if we already reached the maximum number of delayed queues and if we can allocate at all.
                // 创建queue，如果为空，则容量不够，直接丢弃
                if ((queue = WeakOrderQueue.allocate(this, thread)) == null) {
                    // drop object
                    return;
                }
                // 为当前stack创建queue
                delayedRecycled.put(this, queue);
            } else if (queue == WeakOrderQueue.DUMMY) {
                // drop object
                return;
            }
            
            // 将对象添加到queue中
            queue.add(item);
        }
    
        /**
         * 两个drop的时机
         * 1、pushNow：当前线程将数据push到Stack中
         * 2、transfer：将其他线程的WeakOrderQueue中的数据转移到当前的Stack中
         */
        boolean dropHandle(DefaultHandle<?> handle) {
            // 每8个对象：扔掉7个，回收一个
            // 回收的索引：handleRecycleCount - 0/8/16/24/32/..
            if (!handle.hasBeenRecycled) {
                if ((++handleRecycleCount & ratioMask) != 0) {
                    // Drop the object.
                    return true;
                }
                // 设置已经被回收了的标志，实际上此处还没有被回收，在pushNow(DefaultHandle<T> item)接下来的逻辑就会进行回收
                // 对于pushNow(DefaultHandle<T> item)：该值仅仅用于控制是否执行 (++handleRecycleCount & ratioMask) != 0 这段逻辑，而不会用于阻止重复回收的操作，重复回收的操作由item.recycleId | item.lastRecycledId来阻止
                handle.hasBeenRecycled = true;
            }
            return false;
        }
        
        DefaultHandle<T> newHandle() {
            return new DefaultHandle<T>(this);
        }
    }
}
