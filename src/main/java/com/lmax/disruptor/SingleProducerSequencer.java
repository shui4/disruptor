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

import com.lmax.disruptor.util.Util;

import java.util.concurrent.locks.LockSupport;

/**
 * Coordinator for claiming sequences for access to a data structure while tracking dependent {@link
 * Sequence}s. Not safe for use from multiple threads as it does not implement any barriers.
 *
 * <p>* Note on {@link Sequencer#getCursor()}: With this sequencer the cursor value is updated after
 * the call to {@link Sequencer#publish(long)} is made.
 */
public final class SingleProducerSequencer extends SingleProducerSequencerFields {
  protected long p1, p2, p3, p4, p5, p6, p7;

  /**
   * Construct a Sequencer with the selected wait strategy and buffer size.
   *
   * @param bufferSize the size of the buffer that this will sequence over.
   * @param waitStrategy for those waiting on sequences.
   */
  public SingleProducerSequencer(int bufferSize, WaitStrategy waitStrategy) {
    super(bufferSize, waitStrategy);
  }

  /**
   * @see Sequencer#claim(long)
   */
  @Override
  public void claim(long sequence) {
    this.nextValue = sequence;
  }

  @Override
  public long getHighestPublishedSequence(long lowerBound, long availableSequence) {
    return availableSequence;
  }

  /**
   * @see Sequencer#isAvailable(long)
   */
  @Override
  public boolean isAvailable(long sequence) {
    return sequence <= cursor.get();
  }

  /**
   * @see Sequencer#hasAvailableCapacity(int)
   */
  @Override
  public boolean hasAvailableCapacity(int requiredCapacity) {
    return hasAvailableCapacity(requiredCapacity, false);
  }

  private boolean hasAvailableCapacity(int requiredCapacity, boolean doStore) {
    long nextValue = this.nextValue;

    long wrapPoint = (nextValue + requiredCapacity) - bufferSize;
    long cachedGatingSequence = this.cachedValue;

    if (wrapPoint > cachedGatingSequence || cachedGatingSequence > nextValue) {
      if (doStore) {
        cursor.setVolatile(nextValue); // StoreLoad fence
      }

      long minSequence = Util.getMinimumSequence(gatingSequences, nextValue);
      this.cachedValue = minSequence;
    
        return wrapPoint <= minSequence;
    }

    return true;
  }

  /**
   * @see Sequencer#next()
   */
  @Override
  public long next() {
    return next(1);
  }

  /**
   * @see Sequencer#next(int)
   */
  @Override
  public long next(int n) {
    if (n < 1) {
      throw new IllegalArgumentException("n must be > 0");
    }

    long nextValue = this.nextValue;

    long nextSequence = nextValue + n;
    long wrapPoint = nextSequence - bufferSize;
    long cachedGatingSequence = this.cachedValue;

    if (wrapPoint > cachedGatingSequence || cachedGatingSequence > nextValue) {
      cursor.setVolatile(nextValue); // StoreLoad fence

      long minSequence;
      while (wrapPoint > (minSequence = Util.getMinimumSequence(gatingSequences, nextValue))) {
        LockSupport.parkNanos(1L); // TODO: Use waitStrategy to spin?
      }

      this.cachedValue = minSequence;
    }

    this.nextValue = nextSequence;

    return nextSequence;
  }

  /**
   * @see Sequencer#publish(long)
   */
  @Override
  public void publish(long sequence) {
    cursor.set(sequence);
    waitStrategy.signalAllWhenBlocking();
  }

  /**
   * @see Sequencer#publish(long, long)
   */
  @Override
  public void publish(long lo, long hi) {
    publish(hi);
  }

  /**
   * @see Sequencer#remainingCapacity()
   */
  @Override
  public long remainingCapacity() {
    long nextValue = this.nextValue;

    long consumed = Util.getMinimumSequence(gatingSequences, nextValue);
    long produced = nextValue;
    return getBufferSize() - (produced - consumed);
  }

  /**
   * @see Sequencer#tryNext()
   */
  @Override
  public long tryNext() throws InsufficientCapacityException {
    return tryNext(1);
  }

  /**
   * @see Sequencer#tryNext(int)
   */
  @Override
  public long tryNext(int n) throws InsufficientCapacityException {
    if (n < 1) {
      throw new IllegalArgumentException("n must be > 0");
    }

    if (!hasAvailableCapacity(n, true)) {
      throw InsufficientCapacityException.INSTANCE;
    }

    long nextSequence = this.nextValue += n;

    return nextSequence;
  }
}

abstract class SingleProducerSequencerFields extends SingleProducerSequencerPad {
  long cachedValue = Sequence.INITIAL_VALUE;
  /** Set to -1 as sequence starting point */
  long nextValue = Sequence.INITIAL_VALUE;

  SingleProducerSequencerFields(int bufferSize, WaitStrategy waitStrategy) {
    super(bufferSize, waitStrategy);
  }
}

abstract class SingleProducerSequencerPad extends AbstractSequencer {
  protected long p1, p2, p3, p4, p5, p6, p7;

  SingleProducerSequencerPad(int bufferSize, WaitStrategy waitStrategy) {
    super(bufferSize, waitStrategy);
  }
}
