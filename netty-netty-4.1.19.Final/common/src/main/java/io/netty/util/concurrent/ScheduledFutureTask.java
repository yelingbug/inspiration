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

import io.netty.util.internal.DefaultPriorityQueue;
import io.netty.util.internal.PriorityQueueNode;

import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@SuppressWarnings("ComparableImplementedButEqualsNotOverridden")
final class ScheduledFutureTask<V> extends PromiseTask<V> implements ScheduledFuture<V>, PriorityQueueNode {
    private static final AtomicLong nextTaskId = new AtomicLong();
    private static final long START_TIME = System.nanoTime();

    static long nanoTime() {
        return System.nanoTime() - START_TIME;
    }

    static long deadlineNanos(long delay) {
        return nanoTime() + delay;
    }

    private final long id = nextTaskId.getAndIncrement();
    private long deadlineNanos;
    /* 0 - no repeat, >0 - repeat at fixed rate, <0 - repeat with fixed delay */
    private final long periodNanos;

    private int queueIndex = INDEX_NOT_IN_QUEUE;

    ScheduledFutureTask(
            AbstractScheduledEventExecutor executor,
            Runnable runnable, V result, long nanoTime) {

        this(executor, toCallable(runnable, result), nanoTime);
    }

    ScheduledFutureTask(
            AbstractScheduledEventExecutor executor,
            Callable<V> callable, long nanoTime, long period) {

        super(executor, callable);
        if (period == 0) {
            throw new IllegalArgumentException("period: 0 (expected: != 0)");
        }
        deadlineNanos = nanoTime;
        periodNanos = period;
    }

    ScheduledFutureTask(
            AbstractScheduledEventExecutor executor,
            Callable<V> callable, long nanoTime) {

        super(executor, callable);
        deadlineNanos = nanoTime;
        periodNanos = 0;
    }

    @Override
    protected EventExecutor executor() {
        return super.executor();
    }

    public long deadlineNanos() {
        return deadlineNanos;
    }

    public long delayNanos() {
        return Math.max(0, deadlineNanos() - nanoTime());
    }

    public long delayNanos(long currentTimeNanos) {
        return Math.max(0, deadlineNanos() - (currentTimeNanos - START_TIME));
    }

    @Override
    public long getDelay(TimeUnit unit) {
        return unit.convert(delayNanos(), TimeUnit.NANOSECONDS);
    }

    @Override
    public int compareTo(Delayed o) {
        if (this == o) {
            return 0;
        }

        ScheduledFutureTask<?> that = (ScheduledFutureTask<?>) o;
        long d = deadlineNanos() - that.deadlineNanos();
        if (d < 0) {
            return -1;
        } else if (d > 0) {
            return 1;
        } else if (id < that.id) {
            return -1;
        } else if (id == that.id) {
            throw new Error();
        } else {
            return 1;
        }
    }

    @Override
    public void run() {
        assert executor().inEventLoop();
        try {
            if (periodNanos == 0) {//如果周期为0,说明是delay执行,并只执行一次,这个参数是在AbstractScheduledEventExecutor中创建任务时传入的.
                if (setUncancellableInternal()) {
                    V result = task.call();
                    setSuccessInternal(result);
                }
            } else {
                // check if is done as it may was cancelled
                if (!isCancelled()) {//如果没有取消,并且周期不为0,那么就是rate或者initDelay/delay执行.
                    task.call();//执行任务
                    if (!executor().isShutdown()) {
                        long p = periodNanos;
                        if (p > 0) {//根据是p>0(rate)还是<=0(fixed),更新deadlineNanos
                            deadlineNanos += p;
                        } else {
                            deadlineNanos = nanoTime() - p;
                        }
                        if (!isCancelled()) {
                            // scheduledTaskQueue can never be null as we lazy init it before submit the task!
                            Queue<ScheduledFutureTask<?>> scheduledTaskQueue =
                                    ((AbstractScheduledEventExecutor) executor()).scheduledTaskQueue;
                            assert scheduledTaskQueue != null;
                            scheduledTaskQueue.add(this);//重新加入DefaultPriorityQueue队列便于下次执行,因为deadlineNanos被更新,所以会被重新排序.
                        }
                    }
                }
            }
        } catch (Throwable cause) {
            setFailureInternal(cause);
        }
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        boolean canceled = super.cancel(mayInterruptIfRunning);
        if (canceled) {
            ((AbstractScheduledEventExecutor) executor()).removeScheduled(this);
        }
        return canceled;
    }

    boolean cancelWithoutRemove(boolean mayInterruptIfRunning) {
        return super.cancel(mayInterruptIfRunning);
    }

    @Override
    protected StringBuilder toStringBuilder() {
        StringBuilder buf = super.toStringBuilder();
        buf.setCharAt(buf.length() - 1, ',');

        return buf.append(" id: ")
                  .append(id)
                  .append(", deadline: ")
                  .append(deadlineNanos)
                  .append(", period: ")
                  .append(periodNanos)
                  .append(')');
    }

    @Override
    public int priorityQueueIndex(DefaultPriorityQueue<?> queue) {
        return queueIndex;
    }

    @Override
    public void priorityQueueIndex(DefaultPriorityQueue<?> queue, int i) {
        queueIndex = i;
    }
}
