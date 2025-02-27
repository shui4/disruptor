/*
 * Copyright 2011 LMAX Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lmax.disruptor;

import java.util.concurrent.locks.LockSupport;

/**
 * Sleeping strategy that initially spins, then uses a Thread.yield(), and eventually sleep (<code>
 * LockSupport.parkNanos(n)</code>) for the minimum number of nanos the OS and JVM will allow while
 * the {@link com.lmax.disruptor.EventProcessor}s are waiting on a barrier.
 *
 * <p>This strategy is a good compromise between performance and CPU resource. Latency spikes can
 * occur after quiet periods. It will also reduce the impact on the producing thread as it will not
 * need signal any conditional variables to wake up the event handling thread.
 */
public final class SleepingWaitStrategy implements WaitStrategy {
  private static final int DEFAULT_RETRIES = 200;
  private static final long DEFAULT_SLEEP = 100;

  private final int retries;
  private final long sleepTimeNs;

  public SleepingWaitStrategy() {
    this(DEFAULT_RETRIES, DEFAULT_SLEEP);
  }

  public SleepingWaitStrategy(int retries, long sleepTimeNs) {
    this.retries = retries;
    this.sleepTimeNs = sleepTimeNs;
  }

  public SleepingWaitStrategy(int retries) {
    this(retries, DEFAULT_SLEEP);
  }

  @Override
  public void signalAllWhenBlocking() {}

  @Override
  public long waitFor(
      final long sequence,
      Sequence cursor,
      final Sequence dependentSequence,
      final SequenceBarrier barrier)
      throws AlertException {
    long availableSequence;
    int counter = retries;

    while ((availableSequence = dependentSequence.get()) < sequence) {
      counter = applyWaitMethod(barrier, counter);
    }

    return availableSequence;
  }

  private int applyWaitMethod(final SequenceBarrier barrier, int counter) throws AlertException {
    barrier.checkAlert();

    if (counter > 100) {
      --counter;
    } else if (counter > 0) {
      --counter;
      Thread.yield();
    } else {
      LockSupport.parkNanos(sleepTimeNs);
    }

    return counter;
  }
}
