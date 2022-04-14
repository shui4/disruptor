package com.lmax.disruptor.support;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.LifecycleAware;

public class DummyEventHandler<T> implements EventHandler<T>, LifecycleAware {
  public T lastEvent;
  public long lastSequence;
  public int shutdownCalls = 0;
  public int startCalls = 0;

  @Override
  public void onEvent(T event, long sequence, boolean endOfBatch) throws Exception {
    lastEvent = event;
    lastSequence = sequence;
  }

  @Override
  public void onShutdown() {
    shutdownCalls++;
  }

  @Override
  public void onStart() {
    startCalls++;
  }
}
