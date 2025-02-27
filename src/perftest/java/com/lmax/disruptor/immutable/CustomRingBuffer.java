package com.lmax.disruptor.immutable;

import com.lmax.disruptor.*;

public class CustomRingBuffer<T> implements DataProvider<EventAccessor<T>>, EventAccessor<T> {
  private final Object[] buffer;
  private final int mask;
  private final Sequencer sequencer;
  public CustomRingBuffer(Sequencer sequencer) {
    this.sequencer = sequencer;
    buffer = new Object[sequencer.getBufferSize()];
    mask = sequencer.getBufferSize() - 1;
  }

  public BatchEventProcessor<EventAccessor<T>> createHandler(final EventHandler<T> handler) {
    BatchEventProcessor<EventAccessor<T>> processor =
        new BatchEventProcessor<EventAccessor<T>>(
            this, sequencer.newBarrier(), new AccessorEventHandler<T>(handler));
    sequencer.addGatingSequences(processor.getSequence());

    return processor;
  }

  @Override
  public EventAccessor<T> get(long sequence) {
    return this;
  }

  public void put(T e) {
    long next = sequencer.next();
    buffer[index(next)] = e;
    sequencer.publish(next);
  }

  private int index(long sequence) {
    return (int) sequence & mask;
  }

  @SuppressWarnings("unchecked")
  @Override
  public T take(long sequence) {
    int index = index(sequence);

    T t = (T) buffer[index];
    buffer[index] = null;

    return t;
  }

  private static final class AccessorEventHandler<T>
      implements EventHandler<EventAccessor<T>>, LifecycleAware {
    private final EventHandler<T> handler;
    private final LifecycleAware lifecycle;

    private AccessorEventHandler(EventHandler<T> handler) {
      this.handler = handler;
      lifecycle = handler instanceof LifecycleAware ? (LifecycleAware) handler : null;
    }

    @Override
    public void onEvent(EventAccessor<T> accessor, long sequence, boolean endOfBatch)
        throws Exception {
      this.handler.onEvent(accessor.take(sequence), sequence, endOfBatch);
    }

    @Override
    public void onShutdown() {
      if (null != lifecycle) {
        lifecycle.onShutdown();
      }
    }

    @Override
    public void onStart() {
      if (null != lifecycle) {
        lifecycle.onStart();
      }
    }
  }
}
