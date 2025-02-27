package com.lmax.disruptor.offheap;

import com.lmax.disruptor.*;
import com.lmax.disruptor.util.DaemonThreadFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.LockSupport;

public class OneToOneOnHeapThroughputTest extends AbstractPerfTestDisruptor {
  private static final int BLOCK_SIZE = 256;
  private static final int BUFFER_SIZE = 1024 * 1024;
  private static final long ITERATIONS = 1000 * 1000 * 10L;

  private static final boolean SLICED_BUFFER = Boolean.getBoolean("sliced");
  private final byte[] data = new byte[BLOCK_SIZE];
  private final Executor executor = Executors.newFixedThreadPool(1, DaemonThreadFactory.INSTANCE);
  private final ByteBufferHandler handler = new ByteBufferHandler();
  private final Random r = new Random(1);
  private final WaitStrategy waitStrategy = new YieldingWaitStrategy();
  private final RingBuffer<ByteBuffer> buffer =
      RingBuffer.createSingleProducer(
          SLICED_BUFFER
              ? SlicedBufferFactory.direct(BLOCK_SIZE, BUFFER_SIZE)
              : BufferFactory.direct(BLOCK_SIZE),
          BUFFER_SIZE,
          waitStrategy);
  private final BatchEventProcessor<ByteBuffer> processor =
      new BatchEventProcessor<ByteBuffer>(buffer, buffer.newBarrier(), handler);

  {
    buffer.addGatingSequences(processor.getSequence());
  }

  public OneToOneOnHeapThroughputTest() {
    r.nextBytes(data);
  }

  public static void main(String[] args) throws Exception {
    new OneToOneOnHeapThroughputTest().testImplementations();
  }

  @Override
  protected int getRequiredProcessorCount() {
    return 2;
  }

  @Override
  protected long runDisruptorPass() throws Exception {
    byte[] data = this.data;

    final CountDownLatch latch = new CountDownLatch(1);
    long expectedCount = processor.getSequence().get() + ITERATIONS;
    handler.reset(latch, ITERATIONS);
    executor.execute(processor);
    long start = System.currentTimeMillis();

    final RingBuffer<ByteBuffer> rb = buffer;

    for (long i = 0; i < ITERATIONS; i++) {
      long next = rb.next();
      ByteBuffer event = rb.get(next);
      event.clear();
      event.put(data);
      event.flip();
      rb.publish(next);
    }

    latch.await();
    long opsPerSecond = (ITERATIONS * 1000L) / (System.currentTimeMillis() - start);
    waitForEventProcessorSequence(expectedCount);
    processor.halt();

    return opsPerSecond;
  }

  private void waitForEventProcessorSequence(long expectedCount) {
    while (processor.getSequence().get() < expectedCount) {
      LockSupport.parkNanos(1);
    }
  }

  private static final class BufferFactory implements EventFactory<ByteBuffer> {
    private final boolean isDirect;
    private final int size;

    private BufferFactory(boolean isDirect, int size) {
      this.isDirect = isDirect;
      this.size = size;
    }

    public static BufferFactory direct(int size) {
      return new BufferFactory(true, size);
    }

    @SuppressWarnings("unused")
    public static BufferFactory heap(int size) {
      return new BufferFactory(false, size);
    }

    @Override
    public ByteBuffer newInstance() {
      if (isDirect) {
        return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
      } else {
        return ByteBuffer.allocate(size).order(ByteOrder.nativeOrder());
      }
    }
  }

  public static class ByteBufferHandler implements EventHandler<ByteBuffer> {
    private long expectedCount;
    private CountDownLatch latch;
    private long total = 0;

    public long getTotal() {
      return total;
    }

    @Override
    public void onEvent(ByteBuffer event, long sequence, boolean endOfBatch) throws Exception {
      for (int i = 0; i < BLOCK_SIZE; i += 8) {
        total += event.getLong(i);
      }

      if (--expectedCount == 0) {
        latch.countDown();
      }
    }

    public void reset(CountDownLatch latch, long expectedCount) {
      this.latch = latch;
      this.expectedCount = expectedCount;
    }
  }

  private static final class SlicedBufferFactory implements EventFactory<ByteBuffer> {
    private final boolean isDirect;
    private final int size;
    private final int total;
    private ByteBuffer buffer;

    private SlicedBufferFactory(boolean isDirect, int size, int total) {
      this.isDirect = isDirect;
      this.size = size;
      this.total = total;
      this.buffer =
          (isDirect ? ByteBuffer.allocateDirect(size * total) : ByteBuffer.allocate(size * total))
              .order(ByteOrder.nativeOrder());
      this.buffer.limit(0);
    }

    public static SlicedBufferFactory direct(int size, int total) {
      return new SlicedBufferFactory(true, size, total);
    }

    @SuppressWarnings("unused")
    public static SlicedBufferFactory heap(int size, int total) {
      return new SlicedBufferFactory(false, size, total);
    }

    @Override
    public ByteBuffer newInstance() {
      if (this.buffer.limit() == this.buffer.capacity()) {
        this.buffer =
            (isDirect ? ByteBuffer.allocateDirect(size * total) : ByteBuffer.allocate(size * total))
                .order(ByteOrder.nativeOrder());
        this.buffer.limit(0);
      }
      final int limit = this.buffer.limit();
      this.buffer.limit(limit + size);
      this.buffer.position(limit);
      final ByteBuffer slice = this.buffer.slice().order(ByteOrder.nativeOrder());
      return slice;
    }
  }
}
