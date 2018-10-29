/*
 * Copyright 2018 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.internal;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Reschedules a runnable lazily.
 */
final class Rescheduler {

  // deps
  private final ScheduledExecutorService scheduler;
  private final Executor serializingExecutor;
  private final Runnable runnable;

  // state
  private final Stopwatch stopwatch;
  private long runAtNanos;
  private boolean enabled;
  private ScheduledFuture<?> wakeUp;

  Rescheduler(
      Runnable r,
      Executor serializingExecutor,
      ScheduledExecutorService scheduler,
      Stopwatch stopwatch) {
    this.runnable = r;
    this.serializingExecutor = serializingExecutor;
    this.scheduler = scheduler;
    this.stopwatch = stopwatch;
    stopwatch.start();
  }

  /* must be called from the {@link #serializingExecutor} originally passed in. */
  void reschedule(long delay, TimeUnit timeUnit) {
    long delayNanos = timeUnit.toNanos(delay);
    long newRunAtNanos = nanoTime() + delayNanos;//Rescheduler创建的时间到现在的流逝时间+要延迟执行的时间间隔
    enabled = true;
    if (newRunAtNanos - runAtNanos < 0 || wakeUp == null) {
      if (wakeUp != null) {
        wakeUp.cancel(false);
      }
      wakeUp = scheduler.schedule(new FutureRunnable(this), delayNanos, TimeUnit.NANOSECONDS);//以指定的要延迟执行的时间之后执行FutureRunnable，
      //FutureRunnable中的任务是使用serializingExecutor(AutoDrainChannelExecutor)来执行ChannelFutureRunnable，而ChannelFutureRunnable中的任务执行的时候就是判断
      //newRunAtNanos是否大于流逝的时间，如果大于，说明还没到初始时的newRunAtNanos，继续scheduler时间(newRunAtNanos - 流逝的时间)再重复执行ChannelFutureRunnable，
      //一直到newRunAtNanos小于等于流逝的时间，开始真正的执行任务，在这里就是enterIdleMode()
      //通俗来说，这里任务真正执行的时间是要考虑Rescheduler对象刚刚创建时候的流逝时间，并且同一个Rescheduler对象可能会被多次调用reschedule方法，而每次的延迟的时间
      //可能都会不一样，因为reschedule()方法在多个地方会被调用。
    }
    runAtNanos = newRunAtNanos;
  }

  // must be called from channel executor
  void cancel(boolean permanent) {
    enabled = false;
    if (permanent && wakeUp != null) {
      wakeUp.cancel(false);
      wakeUp = null;
    }
  }

  private static final class FutureRunnable implements Runnable {

    private final Rescheduler rescheduler;

    FutureRunnable(Rescheduler rescheduler) {
      this.rescheduler = rescheduler;
    }

    @Override
    public void run() {
      rescheduler.serializingExecutor.execute(rescheduler.new ChannelFutureRunnable());
    }
  }

  private final class ChannelFutureRunnable implements Runnable {

    @Override
    public void run() {
      if (!enabled) {
        wakeUp = null;
        return;
      }
      long now = nanoTime();
      if (runAtNanos - now > 0) {
        wakeUp = scheduler.schedule(
            new FutureRunnable(Rescheduler.this), runAtNanos - now,  TimeUnit.NANOSECONDS);
      } else {
        enabled = false;
        wakeUp = null;
        runnable.run();
      }
    }
  }

  @VisibleForTesting
  static boolean isEnabled(Runnable r) {
    return ((FutureRunnable) r).rescheduler.enabled;
  }

  private long nanoTime() {
    return stopwatch.elapsed(TimeUnit.NANOSECONDS);
  }
}
