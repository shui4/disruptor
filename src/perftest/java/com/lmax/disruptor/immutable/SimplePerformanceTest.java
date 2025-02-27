package com.lmax.disruptor.immutable;

import com.lmax.disruptor.BatchEventProcessor;
import com.lmax.disruptor.EventTranslatorOneArg;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.YieldingWaitStrategy;

import java.util.concurrent.locks.LockSupport;

public class SimplePerformanceTest {
  private static final EventTranslatorOneArg<EventHolder, SimpleEvent> TRANSLATOR =
      new EventTranslatorOneArg<EventHolder, SimpleEvent>() {
        @Override
        public void translateTo(EventHolder holder, long arg1, SimpleEvent event) {
          holder.event = event;
        }
      };
  private final EventHolderHandler eventHolderHandler;
  private final RingBuffer<EventHolder> ringBuffer;

  public SimplePerformanceTest() {
    ringBuffer =
        RingBuffer.createSingleProducer(
            EventHolder.FACTORY, Constants.SIZE, new YieldingWaitStrategy());
    eventHolderHandler = new EventHolderHandler(new SimpleEventHandler());
  }

  public static void main(String[] args) {
    new SimplePerformanceTest().run();
  }

  public void run() {
    try {
      doRun();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  private void doRun() throws InterruptedException {
    BatchEventProcessor<EventHolder> batchEventProcessor =
        new BatchEventProcessor<EventHolder>(
            ringBuffer, ringBuffer.newBarrier(), eventHolderHandler);
    ringBuffer.addGatingSequences(batchEventProcessor.getSequence());

    Thread t = new Thread(batchEventProcessor);
    t.start();

    long iterations = Constants.ITERATIONS;
    for (long l = 0; l < iterations; l++) {
      SimpleEvent e = new SimpleEvent(l, l, l, l);
      ringBuffer.publishEvent(TRANSLATOR, e);
    }

    while (batchEventProcessor.getSequence().get() != iterations - 1) {
      LockSupport.parkNanos(1);
    }

    batchEventProcessor.halt();
    t.join();
  }
}
