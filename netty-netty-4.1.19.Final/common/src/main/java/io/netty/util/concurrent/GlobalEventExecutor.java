/*
 * Copyright 2012 The Netty Project
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

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single-thread singleton {@link EventExecutor}.  It starts the thread automatically and stops it when there is no
 * task pending in the task queue for 1 second.  Please note it is not scalable to schedule large number of tasks to
 * this executor; use a dedicated executor.
 */
public final class GlobalEventExecutor extends AbstractScheduledEventExecutor {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(GlobalEventExecutor.class);

    private static final long SCHEDULE_QUIET_PERIOD_INTERVAL = TimeUnit.SECONDS.toNanos(1);

    public static final GlobalEventExecutor INSTANCE = new GlobalEventExecutor();

    final BlockingQueue<Runnable> taskQueue = new LinkedBlockingQueue<Runnable>();
    final ScheduledFutureTask<Void> quietPeriodTask = new ScheduledFutureTask<Void>(
            this, Executors.<Void>callable(new Runnable() {
        @Override
        public void run() {
            // NOOP
        }
    }, null), ScheduledFutureTask.deadlineNanos(SCHEDULE_QUIET_PERIOD_INTERVAL), -SCHEDULE_QUIET_PERIOD_INTERVAL);

    // because the GlobalEventExecutor is a singleton, tasks submitted to it can come from arbitrary threads and this
    // can trigger the creation of a thread from arbitrary thread groups; for this reason, the thread factory must not
    // be sticky about its thread group
    // visible for testing
    final ThreadFactory threadFactory =
            new DefaultThreadFactory(DefaultThreadFactory.toPoolName(getClass()), false, Thread.NORM_PRIORITY, null);
    private final TaskRunner taskRunner = new TaskRunner();
    private final AtomicBoolean started = new AtomicBoolean();
    volatile Thread thread;

    private final Future<?> terminationFuture = new FailedFuture<Object>(this, new UnsupportedOperationException());

    private GlobalEventExecutor() {
        scheduledTaskQueue().add(quietPeriodTask);
    }

    /**
     * Take the next {@link Runnable} from the task queue and so will block if no task is currently present.
     *
     * @return {@code null} if the executor thread has been interrupted or waken up.
     */
    Runnable takeTask() {
        BlockingQueue<Runnable> taskQueue = this.taskQueue;
        for (;;) {
            ScheduledFutureTask<?> scheduledTask = peekScheduledTask();//从调度队列的头部取出一个等待调度的任务，这里是直接ｐｅｅｋ一个，而pollScheduledTask()方法需要判断头结点的截止时间．
            if (scheduledTask == null) {//如果没有，尝试从taskQueue中取，如果取不到等待；不过这种情况在这里应该不会发生，因为在初始化的时候已经有一个quietPeriodTask在里边；
                //而在外围的调用里如果判断是quietPeriodTask这个任务，会继续continue外围的循环，又走到这里调用这个方法，形成循环(见TaskRunner#run()方法逻辑)．所以scheduledTask
                // 在这里是永远不会为空．
                Runnable task = null;
                try {
                    task = taskQueue.take();//按照上面的解释，应该不会走到这里．
                } catch (InterruptedException e) {
                    // Ignore
                }
                return task;
            } else {
                long delayNanos = scheduledTask.delayNanos();
                Runnable task;
                if (delayNanos > 0) {
                    try {
                        task = taskQueue.poll(delayNanos, TimeUnit.NANOSECONDS);//如果从调度队列的头部取到任务，判断是否到时间，如果没到则从taskQueue等待时间间隔
                    } catch (InterruptedException e) {
                        // Waken up.
                        return null;
                    }
                } else {
                    task = taskQueue.poll();//或者不用等，如果没有直接返回空．
                }

                /**
                 * 这里的控制是先对调度队列中取一个任务，获取还有多长时间会执行，
                 * 如果时间没到，在taskQueue中获取，如果taskQueue也为空，
                 * 那么就等待需要到的时间间隔，在这个过程中taskQueue中可能有新增的任务，就会直接返回；或者一直没有任务，就会等待要到的
                 * 时间，并返回为空，并通过下面的代码从调度队列中转移待调度的任务到taskQueue中，返回一个，并从外围的调用循环中继续下一次
                 * 的循环；
                 * 如果时间到了，也在taskQueue中获取，如果taskQueue也为空，直接返回空；当然在这个过程中taskQueue中可能有新增的任务，就会
                 * 返回一个任务；或者都没有任务，那么就通过下面的代码从调度队列中转移待调度的任务到taskQueue中，返回一个，并从外围的调用循环中继续下一次
                 * 的循环；
                 */

                if (task == null) {//为空，说明需要从调度队列中取一部分到时间的调度任务转移到taskQueue中．
                    fetchFromScheduledTaskQueue();
                    task = taskQueue.poll();
                }

                if (task != null) {
                    return task;
                }
            }
        }
    }

    private void fetchFromScheduledTaskQueue() {
        long nanoTime = AbstractScheduledEventExecutor.nanoTime();
        Runnable scheduledTask = pollScheduledTask(nanoTime);
        while (scheduledTask != null) {
            taskQueue.add(scheduledTask);
            scheduledTask = pollScheduledTask(nanoTime);
        }
    }

    /**
     * Return the number of tasks that are pending for processing.
     *
     * <strong>Be aware that this operation may be expensive as it depends on the internal implementation of the
     * SingleThreadEventExecutor. So use it was care!</strong>
     */
    public int pendingTasks() {
        return taskQueue.size();
    }

    /**
     * Add a task to the task queue, or throws a {@link RejectedExecutionException} if this instance was shutdown
     * before.
     */
    private void addTask(Runnable task) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        taskQueue.add(task);
    }

    @Override
    public boolean inEventLoop(Thread thread) {
        return thread == this.thread;
    }

    @Override
    public Future<?> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit) {
        return terminationFuture();
    }

    @Override
    public Future<?> terminationFuture() {
        return terminationFuture;
    }

    @Override
    @Deprecated
    public void shutdown() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isShuttingDown() {
        return false;
    }

    @Override
    public boolean isShutdown() {
        return false;
    }

    @Override
    public boolean isTerminated() {
        return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return false;
    }

    /**
     * Waits until the worker thread of this executor has no tasks left in its task queue and terminates itself.
     * Because a new worker thread will be started again when a new task is submitted, this operation is only useful
     * when you want to ensure that the worker thread is terminated <strong>after</strong> your application is shut
     * down and there's no chance of submitting a new task afterwards.
     *
     * @return {@code true} if and only if the worker thread has been terminated
     */
    public boolean awaitInactivity(long timeout, TimeUnit unit) throws InterruptedException {
        if (unit == null) {
            throw new NullPointerException("unit");
        }

        final Thread thread = this.thread;
        if (thread == null) {
            throw new IllegalStateException("thread was not started");
        }
        thread.join(unit.toMillis(timeout));
        return !thread.isAlive();
    }

    @Override
    public void execute(Runnable task) {
        if (task == null) {
            throw new NullPointerException("task");
        }

        addTask(task);
        if (!inEventLoop()) {
            startThread();
        }
    }

    private void startThread() {
        if (started.compareAndSet(false, true)) {
            Thread t = threadFactory.newThread(taskRunner);
            // Set to null to ensure we not create classloader leaks by holds a strong reference to the inherited
            // classloader.
            // See:
            // - https://github.com/netty/netty/issues/7290
            // - https://bugs.openjdk.java.net/browse/JDK-7008595
            t.setContextClassLoader(null);//为什么要设置为null的理由？This is fine if the thread's context class loader is the system class loader, but it's bad if the context class loader is a custom class loader that may need to be unloaded at some future point (e.g. in the context of an appserver). The reference held by this daemon thread means that the class loader can never become eligible for GC.


            // Set the thread before starting it as otherwise inEventLoop() may return false and so produce
            // an assert error.
            // See https://github.com/netty/netty/issues/4357
            thread = t;
            t.start();
        }
    }

    final class TaskRunner implements Runnable {
        @Override
        public void run() {
            for (;;) {
                Runnable task = takeTask();
                if (task != null) {
                    try {
                        task.run();
                    } catch (Throwable t) {
                        logger.warn("Unexpected exception from the global event executor: ", t);
                    }

                    if (task != quietPeriodTask) {
                        continue;
                    }
                }

                Queue<ScheduledFutureTask<?>> scheduledTaskQueue = GlobalEventExecutor.this.scheduledTaskQueue;
                // Terminate if there is no task in the queue (except the noop task).
                if (taskQueue.isEmpty() && (scheduledTaskQueue == null || scheduledTaskQueue.size() == 1)) {
                    // Mark the current thread as stopped.
                    // The following CAS must always success and must be uncontended,
                    // because only one thread should be running at the same time.
                    boolean stopped = started.compareAndSet(true, false);
                    assert stopped;

                    // Check if there are pending entries added by execute() or schedule*() while we do CAS above.
                    if (taskQueue.isEmpty() && (scheduledTaskQueue == null || scheduledTaskQueue.size() == 1)) {
                        // A) No new task was added and thus there's nothing to handle
                        //    -> safe to terminate because there's nothing left to do
                        // B) A new thread started and handled all the new tasks.
                        //    -> safe to terminate the new thread will take care the rest
                        break;
                    }

                    // There are pending tasks added again.
                    if (!started.compareAndSet(false, true)) {//如果上面已经把started变为false，假设这个时候再调用startThread()方法，那么就会启动一个新的线程，
                        //这个线程就什么也不做，直接退出，让新启动的线程take care of everything...
                        // startThread() started a new thread and set 'started' to true.
                        // -> terminate this thread so that the new thread reads from taskQueue exclusively.
                        break;
                    }

                    // New tasks were added, but this worker was faster to set 'started' to true.
                    // i.e. a new worker thread was not started by startThread().
                    // -> keep this thread alive to handle the newly added entries.
                }
            }
        }
    }
}
