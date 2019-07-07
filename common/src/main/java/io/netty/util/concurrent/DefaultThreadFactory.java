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

package io.netty.util.concurrent;

import io.netty.util.internal.StringUtil;

import java.util.Locale;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 带有简单命名规则的默认线程工厂
 *
 * A {@link ThreadFactory} implementation with a simple naming rule.
 */
public class DefaultThreadFactory implements ThreadFactory {
    
    //自增的线程池Id(static final)
    private static final AtomicInteger poolId = new AtomicInteger();
    
    //自增的线程Id
    private final AtomicInteger nextId = new AtomicInteger();
    private final String prefix;
    private final boolean daemon;
    private final int priority;
    protected final ThreadGroup threadGroup;
    
    public DefaultThreadFactory(Class<?> poolType) {
        this(poolType, false, Thread.NORM_PRIORITY);
    }
    
    public DefaultThreadFactory(String poolName) {
        this(poolName, false, Thread.NORM_PRIORITY);
    }
    
    public DefaultThreadFactory(Class<?> poolType, boolean daemon) {
        this(poolType, daemon, Thread.NORM_PRIORITY);
    }
    
    public DefaultThreadFactory(String poolName, boolean daemon) {
        this(poolName, daemon, Thread.NORM_PRIORITY);
    }
    
    public DefaultThreadFactory(Class<?> poolType, int priority) {
        this(poolType, false, priority);
    }
    
    public DefaultThreadFactory(String poolName, int priority) {
        this(poolName, false, priority);
    }
    
    public DefaultThreadFactory(Class<?> poolType, boolean daemon, int priority) {
        this(toPoolName(poolType), daemon, priority);
    }
    
    /**
     * 获取poolName，首字母变小写
     *
     * @param poolType
     * @return
     */
    public static String toPoolName(Class<?> poolType) {
        if (poolType == null) {
            throw new NullPointerException("poolType");
        }
        
        String poolName = StringUtil.simpleClassName(poolType);
        switch (poolName.length()) {
            case 0:
                return "unknown";
            case 1:
                return poolName.toLowerCase(Locale.US);
            default:
                if (Character.isUpperCase(poolName.charAt(0)) && Character.isLowerCase(poolName.charAt(1))) {
                    return Character.toLowerCase(poolName.charAt(0)) + poolName.substring(1);
                } else {
                    return poolName;
                }
        }
    }
    
    /**
     * 线程工厂构造方法
     *
     * @param poolName
     * @param daemon
     * @param priority
     * @param threadGroup
     */
    public DefaultThreadFactory(String poolName, boolean daemon, int priority, ThreadGroup threadGroup) {
        if (poolName == null) {
            throw new NullPointerException("poolName");
        }
        if (priority < Thread.MIN_PRIORITY || priority > Thread.MAX_PRIORITY) {
            throw new IllegalArgumentException(
                    "priority: " + priority + " (expected: Thread.MIN_PRIORITY <= priority <= Thread.MAX_PRIORITY)");
        }
        //设置线程名前缀
        prefix = poolName + '-' + poolId.incrementAndGet() + '-';
        //设置是否是daemon线程
        this.daemon = daemon;
        //设置优先级
        this.priority = priority;
        //设置线程组
        this.threadGroup = threadGroup;
    }
    
    public DefaultThreadFactory(String poolName, boolean daemon, int priority) {
        this(poolName, daemon, priority, System.getSecurityManager() == null ?
                Thread.currentThread().getThreadGroup() : System.getSecurityManager().getThreadGroup());
    }
    
    /**
     * 创建线程,得到的是netty封装的FastThreadLocalThread
     *
     * @param r
     * @return
     */
    @Override
    public Thread newThread(Runnable r) {
        Thread t = newThread(new DefaultRunnableDecorator(r), prefix + nextId.incrementAndGet());
        try {
            if (t.isDaemon() != daemon) {
                t.setDaemon(daemon);
            }
            
            if (t.getPriority() != priority) {
                t.setPriority(priority);
            }
        } catch (Exception ignored) {
            // Doesn't matter even if failed to set.
        }
        return t;
    }
    
    /**
     * 创建线程
     *
     * @param r
     * @param name
     * @return
     */
    protected Thread newThread(Runnable r, String name) {
        return new FastThreadLocalThread(threadGroup, r, name);
    }
    
    /**
     * 装饰模式，封装runnable，执行时清空FastThreadLocalThread对应的FastThreadLocal
     */
    private static final class DefaultRunnableDecorator implements Runnable {
        
        private final Runnable r;
        
        DefaultRunnableDecorator(Runnable r) {
            this.r = r;
        }
        
        @Override
        public void run() {
            try {
                r.run();
            } finally {
                FastThreadLocal.removeAll();
            }
        }
    }
}
