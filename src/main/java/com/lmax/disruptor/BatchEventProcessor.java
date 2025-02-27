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

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Convenience class for handling the batching semantics of consuming entries from a {@link
 * RingBuffer} and delegating the available events to an {@link EventHandler}.
 *
 * <p>If the {@link EventHandler} also implements {@link LifecycleAware} it will be notified just
 * after the thread is started and just before the thread is shutdown.
 *
 * @param <T> event implementation storing the data for sharing during exchange or parallel
 *     coordination of an event.
 */
public final class BatchEventProcessor<T> implements EventProcessor {
  private static final int IDLE = 0;
  private static final int HALTED = IDLE + 1;
  private static final int RUNNING = HALTED + 1;
  private final BatchStartAware batchStartAware;
  private final DataProvider<T> dataProvider;
  private final EventHandler<? super T> eventHandler;
  private final AtomicInteger running = new AtomicInteger(IDLE);
  private final Sequence sequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);
  private final SequenceBarrier sequenceBarrier;
  private final TimeoutHandler timeoutHandler;
  private ExceptionHandler<? super T> exceptionHandler;

  /**
   * Construct a {@link EventProcessor} that will automatically track the progress by updating its
   * sequence when the {@link EventHandler#onEvent(Object, long, boolean)} method returns.
   *
   * @param dataProvider to which events are published.
   * @param sequenceBarrier on which it is waiting.
   * @param eventHandler is the delegate to which events are dispatched.
   */
  public BatchEventProcessor(
      final DataProvider<T> dataProvider,
      final SequenceBarrier sequenceBarrier,
      final EventHandler<? super T> eventHandler) {
    this.dataProvider = dataProvider;
    this.sequenceBarrier = sequenceBarrier;
    this.eventHandler = eventHandler;

    if (eventHandler instanceof SequenceReportingEventHandler) {
      ((SequenceReportingEventHandler<?>) eventHandler).setSequenceCallback(sequence);
    }

    batchStartAware =
        (eventHandler instanceof BatchStartAware) ? (BatchStartAware) eventHandler : null;
    timeoutHandler =
        (eventHandler instanceof TimeoutHandler) ? (TimeoutHandler) eventHandler : null;
  }

  @Override
  public Sequence getSequence() {
    return sequence;
  }

  @Override
  public void halt() {
    running.set(HALTED);
    sequenceBarrier.alert();
  }

  @Override
  public boolean isRunning() {
    return running.get() != IDLE;
  }

  /**
   * It is ok to have another thread rerun this method after a halt().
   *
   * @throws IllegalStateException if this object instance is already running in a thread
   */
  @Override
  public void run() {
    if (running.compareAndSet(IDLE, RUNNING)) {
      sequenceBarrier.clearAlert();

      notifyStart();
      try {
        if (running.get() == RUNNING) {
          processEvents();
        }
      } finally {
        notifyShutdown();
        running.set(IDLE);
      }
    } else {
      // This is a little bit of guess work.  The running state could of changed to HALTED by
      // this point.  However, Java does not have compareAndExchange which is the only way
      // to get it exactly correct.
      if (running.get() == RUNNING) {
        throw new IllegalStateException("Thread is already running");
      } else {
        earlyExit();
      }
    }
  }

  private void earlyExit() {
    notifyStart();
    notifyShutdown();
  }

  private ExceptionHandler<? super T> getExceptionHandler() {
    ExceptionHandler<? super T> handler = exceptionHandler;
    if (handler == null) {
      return ExceptionHandlers.defaultHandler();
    }
    return handler;
  }

  /**
   * Set a new {@link ExceptionHandler} for handling exceptions propagated out of the {@link
   * BatchEventProcessor}
   *
   * @param exceptionHandler to replace the existing exceptionHandler.
   */
  public void setExceptionHandler(final ExceptionHandler<? super T> exceptionHandler) {
    if (null == exceptionHandler) {
      throw new NullPointerException();
    }

    this.exceptionHandler = exceptionHandler;
  }

  /**
   * Delegate to {@link ExceptionHandler#handleEventException(Throwable, long, Object)} on the
   * delegate or the default {@link ExceptionHandler} if one has not been configured.
   */
  private void handleEventException(final Throwable ex, final long sequence, final T event) {
    getExceptionHandler().handleEventException(ex, sequence, event);
  }

  /**
   * Delegate to {@link ExceptionHandler#handleOnShutdownException(Throwable)} on the delegate or
   * the default {@link ExceptionHandler} if one has not been configured.
   */
  private void handleOnShutdownException(final Throwable ex) {
    getExceptionHandler().handleOnShutdownException(ex);
  }

  /**
   * Delegate to {@link ExceptionHandler#handleOnStartException(Throwable)} on the delegate or the
   * default {@link ExceptionHandler} if one has not been configured.
   */
  private void handleOnStartException(final Throwable ex) {
    getExceptionHandler().handleOnStartException(ex);
  }

  /** Notifies the EventHandler immediately prior to this processor shutting down */
  private void notifyShutdown() {
    if (eventHandler instanceof LifecycleAware) {
      try {
        ((LifecycleAware) eventHandler).onShutdown();
      } catch (final Throwable ex) {
        handleOnShutdownException(ex);
      }
    }
  }

  /** Notifies the EventHandler when this processor is starting up */
  private void notifyStart() {
    if (eventHandler instanceof LifecycleAware) {
      try {
        ((LifecycleAware) eventHandler).onStart();
      } catch (final Throwable ex) {
        handleOnStartException(ex);
      }
    }
  }

  private void notifyTimeout(final long availableSequence) {
    try {
      if (timeoutHandler != null) {
        timeoutHandler.onTimeout(availableSequence);
      }
    } catch (Throwable e) {
      handleEventException(e, availableSequence, null);
    }
  }

  private void processEvents() {
    T event = null;
    long nextSequence = sequence.get() + 1L;

    while (true) {
      try {
        final long availableSequence = sequenceBarrier.waitFor(nextSequence);
        if (batchStartAware != null) {
          batchStartAware.onBatchStart(availableSequence - nextSequence + 1);
        }

        while (nextSequence <= availableSequence) {
          event = dataProvider.get(nextSequence);
          eventHandler.onEvent(event, nextSequence, nextSequence == availableSequence);
          nextSequence++;
        }

        sequence.set(availableSequence);
      } catch (final TimeoutException e) {
        notifyTimeout(sequence.get());
      } catch (final AlertException ex) {
        if (running.get() != RUNNING) {
          break;
        }
      } catch (final Throwable ex) {
        handleEventException(ex, nextSequence, event);
        sequence.set(nextSequence);
        nextSequence++;
      }
    }
  }
}
