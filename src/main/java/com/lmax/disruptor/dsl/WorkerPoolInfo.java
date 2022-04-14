package com.lmax.disruptor.dsl;

import com.lmax.disruptor.Sequence;
import com.lmax.disruptor.SequenceBarrier;
import com.lmax.disruptor.WorkerPool;

import java.util.concurrent.Executor;

class WorkerPoolInfo<T> implements ConsumerInfo {
  private final SequenceBarrier sequenceBarrier;
  private final WorkerPool<T> workerPool;
  private boolean endOfChain = true;

  WorkerPoolInfo(final WorkerPool<T> workerPool, final SequenceBarrier sequenceBarrier) {
    this.workerPool = workerPool;
    this.sequenceBarrier = sequenceBarrier;
  }

  @Override
  public SequenceBarrier getBarrier() {
    return sequenceBarrier;
  }

  @Override
  public Sequence[] getSequences() {
    return workerPool.getWorkerSequences();
  }

  @Override
  public void halt() {
    workerPool.halt();
  }

  @Override
  public boolean isEndOfChain() {
    return endOfChain;
  }

  @Override
  public boolean isRunning() {
    return workerPool.isRunning();
  }

  @Override
  public void markAsUsedInBarrier() {
    endOfChain = false;
  }

  @Override
  public void start(Executor executor) {
    workerPool.start(executor);
  }
}
