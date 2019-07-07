/*
 * Copyright 2014 The Netty Project
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

import io.netty.util.internal.InternalThreadLocalMap;
import io.netty.util.internal.PlatformDependent;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * A special variant of {@link ThreadLocal} that yields higher access performance when accessed from a
 * {@link FastThreadLocalThread}.
 * <p>
 * Internally, a {@link FastThreadLocal} uses a constant index in an array, instead of using hash code and hash table,
 * to look for a variable.  Although seemingly very subtle, it yields slight performance advantage over using a hash
 * table, and it is useful when accessed frequently.
 * </p><p>
 * To take advantage of this thread-local variable, your thread must be a {@link FastThreadLocalThread} or its subtype.
 * By default, all threads created by {@link DefaultThreadFactory} are {@link FastThreadLocalThread} due to this reason.
 * </p><p>
 * Note that the fast path is only possible on threads that extend {@link FastThreadLocalThread}, because it requires
 * a special field to store the necessary state.  An access by any other kind of thread falls back to a regular
 * {@link ThreadLocal}.
 * </p>
 *
 * @param <V> the type of the thread-local variable
 * @see ThreadLocal
 */
public class FastThreadLocal<V> {
    
    private static final int variablesToRemoveIndex = InternalThreadLocalMap.nextVariableIndex();
    
    /**
     * Removes all {@link FastThreadLocal} variables bound to the current thread.  This operation is useful when you
     * are in a container environment, and you don't want to leave the thread local variables in the threads you do not
     * manage.
     */
    public static void removeAll() {
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.getIfSet();
        if (threadLocalMap == null) {
            return;
        }
        
        try {
            Object v = threadLocalMap.indexedVariable(variablesToRemoveIndex);
            if (v != null && v != InternalThreadLocalMap.UNSET) {
                @SuppressWarnings("unchecked")
                Set<FastThreadLocal<?>> variablesToRemove = (Set<FastThreadLocal<?>>) v;
                FastThreadLocal<?>[] variablesToRemoveArray =
                        variablesToRemove.toArray(new FastThreadLocal[variablesToRemove.size()]);
                for (FastThreadLocal<?> tlv : variablesToRemoveArray) {
                    tlv.remove(threadLocalMap);
                }
            }
        } finally {
            InternalThreadLocalMap.remove();
        }
    }
    
    /**
     * Returns the number of thread local variables bound to the current thread.
     */
    public static int size() {
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.getIfSet();
        if (threadLocalMap == null) {
            return 0;
        } else {
            return threadLocalMap.size();
        }
    }
    
    /**
     * Destroys the data structure that keeps all {@link FastThreadLocal} variables accessed from
     * non-{@link FastThreadLocalThread}s.  This operation is useful when you are in a container environment, and you
     * do not want to leave the thread local variables in the threads you do not manage.  Call this method when your
     * application is being unloaded from the container.
     */
    public static void destroy() {
        InternalThreadLocalMap.destroy();
    }
    
    @SuppressWarnings("unchecked")
    private static void addToVariablesToRemove(InternalThreadLocalMap threadLocalMap, FastThreadLocal<?> variable) {
        Object v = threadLocalMap.indexedVariable(variablesToRemoveIndex);
        Set<FastThreadLocal<?>> variablesToRemove;
        if (v == InternalThreadLocalMap.UNSET || v == null) {
            // 第一次调用，创建一个Set对象variablesToRemove
            variablesToRemove = Collections.newSetFromMap(new IdentityHashMap<FastThreadLocal<?>, Boolean>());
            //将这个Set保存在数组的第一个元素，初始时variablesToRemoveIndex=0
            //因为InternalThreadLocalMap是数组实现，用这个set保存ThreadLocal对象
            threadLocalMap.setIndexedVariable(variablesToRemoveIndex, variablesToRemove);
        } else {
            variablesToRemove = (Set<FastThreadLocal<?>>) v;
        }
        
        //将value保存在variablesToRemoveIndex集合中
        variablesToRemove.add(variable);
    }
    
    /**
     * 将ThreadLocal从variablesToRemove集合中删除
     *
     * @param threadLocalMap
     * @param variable
     */
    private static void removeFromVariablesToRemove(
            InternalThreadLocalMap threadLocalMap, FastThreadLocal<?> variable) {
        
        Object v = threadLocalMap.indexedVariable(variablesToRemoveIndex);
        
        if (v == InternalThreadLocalMap.UNSET || v == null) {
            return;
        }
        
        @SuppressWarnings("unchecked")
        Set<FastThreadLocal<?>> variablesToRemove = (Set<FastThreadLocal<?>>) v;
        variablesToRemove.remove(variable);
    }
    
    private final int index;
    
    public FastThreadLocal() {
        //每个ThreadLocal创建时，获取一个索引编号,对应InternalThreadLocalMap中的数组下标
        index = InternalThreadLocalMap.nextVariableIndex();
    }
    
    /**
     * Returns the current value for the current thread
     */
    public final V get() {
        // 先获取InternalThreadLocalMap，然后从InternalThreadLocalMap中获取value
        return get(InternalThreadLocalMap.get());
    }
    
    /**
     * 从InternalThreadLocalMap中获取属性值
     *
     * Returns the current value for the specified thread local map.
     * The specified thread local map must be for the current thread.
     */
    @SuppressWarnings("unchecked")
    public final V get(InternalThreadLocalMap threadLocalMap) {
        //根据index在InternalThreadLocalMap中获取值
        Object v = threadLocalMap.indexedVariable(index);
        
        //如果不等于UNSET，表示获取到值，返回
        if (v != InternalThreadLocalMap.UNSET) {
            return (V) v;
        }
        
        //获取值为UNSET，进行value初始化
        return initialize(threadLocalMap);
    }
    
    private V initialize(InternalThreadLocalMap threadLocalMap) {
        V v = null;
        try {
            // 获取到初始值，可重写initialValue()方法进行设置
            v = initialValue();
        } catch (Exception e) {
            PlatformDependent.throwException(e);
        }
        
        // 将初始值保存在threadLocalMap数组的index位置
        threadLocalMap.setIndexedVariable(index, v);
        
        //添加新属性值的后，将FastThreadLocal对象保存在一个Set中，方便删除
        addToVariablesToRemove(threadLocalMap, this);
        return v;
    }
    
    /**
     * Set the value for the current thread.
     */
    public final void set(V value) {
        if (value != InternalThreadLocalMap.UNSET) {
            // 若设置的值不是UNSET，则获取InternalThreadLocalMap，然后进行set
            set(InternalThreadLocalMap.get(), value);
        } else {
            // 若为UNSET，则进行删除操作
            remove();
        }
    }
    
    /**
     * Set the value for the specified thread local map. The specified thread local map must be for the current thread.
     */
    public final void set(InternalThreadLocalMap threadLocalMap, V value) {
        if (value != InternalThreadLocalMap.UNSET) {
            if (threadLocalMap.setIndexedVariable(index, value)) {
                // 若第一次设置属性值，返回true，则需调用addToVariablesToRemove，将this添加到set中
                // 若不是第一次设置，返回false，不需要调用addToVariablesToRemove
                addToVariablesToRemove(threadLocalMap, this);
            }
        } else {
            // 若为UNSET，调用删除操作
            remove(threadLocalMap);
        }
    }
    
    /**
     * Returns {@code true} if and only if this thread-local variable is set.
     */
    public final boolean isSet() {
        return isSet(InternalThreadLocalMap.getIfSet());
    }
    
    /**
     * Returns {@code true} if and only if this thread-local variable is set.
     * The specified thread local map must be for the current thread.
     */
    public final boolean isSet(InternalThreadLocalMap threadLocalMap) {
        return threadLocalMap != null && threadLocalMap.isIndexedVariableSet(index);
    }
    
    /**
     * Sets the value to uninitialized; a proceeding call to get() will trigger a call to initialValue().
     */
    public final void remove() {
        remove(InternalThreadLocalMap.getIfSet());
    }
    
    /**
     * Sets the value to uninitialized for the specified thread local map;
     * a proceeding call to get() will trigger a call to initialValue().
     * The specified thread local map must be for the current thread.
     */
    @SuppressWarnings("unchecked")
    public final void remove(InternalThreadLocalMap threadLocalMap) {
        //若InternalThreadLocalMap为null，直接返回
        if (threadLocalMap == null) {
            return;
        }
        
        // 将InternalThreadLocalMap当前index的值改为UNSET
        Object v = threadLocalMap.removeIndexedVariable(index);
        removeFromVariablesToRemove(threadLocalMap, this);
        
        // 若删除前原始值不为UNSET，从触发onRemoval回调
        if (v != InternalThreadLocalMap.UNSET) {
            try {
                onRemoval((V) v);
            } catch (Exception e) {
                PlatformDependent.throwException(e);
            }
        }
    }
    
    /**
     * Returns the initial value for this thread-local variable.
     */
    protected V initialValue() throws Exception {
        return null;
    }
    
    /**
     * Invoked when this thread local variable is removed by {@link #remove()}.
     */
    protected void onRemoval(@SuppressWarnings("UnusedParameters") V value) throws Exception {
    }
}
