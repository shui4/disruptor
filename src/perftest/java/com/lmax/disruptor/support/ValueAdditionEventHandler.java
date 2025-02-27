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
package com.lmax.disruptor.support;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.util.PaddedLong;

import java.util.concurrent.CountDownLatch;

public final class ValueAdditionEventHandler implements EventHandler<ValueEvent> {
  private final PaddedLong value = new PaddedLong();
  private long count;
  private CountDownLatch latch;

  public long getValue() {
    return value.get();
  }

  @Override
  public void onEvent(final ValueEvent event, final long sequence, final boolean endOfBatch)
      throws Exception {
    value.set(value.get() + event.getValue());

    if (count == sequence) {
      latch.countDown();
    }
  }

  public void reset(final CountDownLatch latch, final long expectedCount) {
    value.set(0L);
    this.latch = latch;
    count = expectedCount;
  }
}
