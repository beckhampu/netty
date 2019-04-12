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

import java.util.concurrent.TimeUnit;

/**
 * 表示一个异步操作已完成的结果
 * isDone = true isCancelled = false
 * isSuccess 和 cause 不明确
 *
 * A skeletal {@link Future} implementation which represents a {@link Future} which has been completed already.
 */
public abstract class CompleteFuture<V> extends AbstractFuture<V> {
    
    /**
     * 执行器，用于执行添加的listener回调操作。
     */
    private final EventExecutor executor;
    
    /**
     * Creates a new instance.
     *
     * @param executor the {@link EventExecutor} associated with this future
     */
    protected CompleteFuture(EventExecutor executor) {
        this.executor = executor;
    }
    
    /**
     * Return the {@link EventExecutor} which is used by this {@link CompleteFuture}.
     */
    protected EventExecutor executor() {
        return executor;
    }
    
    @Override
    public Future<V> addListener(GenericFutureListener<? extends Future<? super V>> listener) {
        if (listener == null) {
            throw new NullPointerException("listener");
        }
        //由于是complete future，代表已完成，因此立刻执行listener
        DefaultPromise.notifyListener(executor(), this, listener);
        return this;
    }
    
    @Override
    public Future<V> addListeners(GenericFutureListener<? extends Future<? super V>>... listeners) {
        if (listeners == null) {
            throw new NullPointerException("listeners");
        }
        for (GenericFutureListener<? extends Future<? super V>> l : listeners) {
            if (l == null) {
                break;
            }
            //由于是complete future，代表已完成，因此立刻执行listener
            DefaultPromise.notifyListener(executor(), this, l);
        }
        return this;
    }
    
    @Override
    public Future<V> removeListener(GenericFutureListener<? extends Future<? super V>> listener) {
        // 由于是complete future，故不需要删除listener
        // NOOP
        return this;
    }
    
    @Override
    public Future<V> removeListeners(GenericFutureListener<? extends Future<? super V>>... listeners) {
        // 由于是complete future，故不需要删除listener
        // NOOP
        return this;
    }
    
    @Override
    public Future<V> await() throws InterruptedException {
        // 若已被中断则抛出中断异常，否则直接返回，不需要等待
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        return this;
    }
    
    @Override
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        // 若已被中断则抛出中断异常，否则直接返回，不需要等待
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        return true;
    }
    
    @Override
    public Future<V> sync() throws InterruptedException {
        //阻塞直到异步操作完成，但本身已完成，则直接返回结果
        return this;
    }
    
    @Override
    public Future<V> syncUninterruptibly() {
        //阻塞直到异步操作完成（不中断），但本身已完成，则直接返回结果
        return this;
    }
    
    @Override
    public boolean await(long timeoutMillis) throws InterruptedException {
        // 若已被中断则抛出中断异常，否则直接返回，不需要等待
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        return true;
    }
    
    @Override
    public Future<V> awaitUninterruptibly() {
        return this;
    }
    
    @Override
    public boolean awaitUninterruptibly(long timeout, TimeUnit unit) {
        return true;
    }
    
    @Override
    public boolean awaitUninterruptibly(long timeoutMillis) {
        return true;
    }
    
    @Override
    public boolean isDone() {
        return true;
    }
    
    @Override
    public boolean isCancellable() {
        return false;
    }
    
    @Override
    public boolean isCancelled() {
        return false;
    }
    
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        return false;
    }
}
