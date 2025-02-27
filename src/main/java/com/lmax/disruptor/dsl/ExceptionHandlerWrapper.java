package com.lmax.disruptor.dsl;

import com.lmax.disruptor.ExceptionHandler;
import com.lmax.disruptor.ExceptionHandlers;

public class ExceptionHandlerWrapper<T> implements ExceptionHandler<T> {
  private ExceptionHandler<? super T> delegate;

  @Override
  public void handleEventException(final Throwable ex, final long sequence, final T event) {
    getExceptionHandler().handleEventException(ex, sequence, event);
  }

  @Override
  public void handleOnStartException(final Throwable ex) {
    getExceptionHandler().handleOnStartException(ex);
  }

  @Override
  public void handleOnShutdownException(final Throwable ex) {
    getExceptionHandler().handleOnShutdownException(ex);
  }

  private ExceptionHandler<? super T> getExceptionHandler() {
    ExceptionHandler<? super T> handler = delegate;
    if (handler == null) {
      return ExceptionHandlers.defaultHandler();
    }
    return handler;
  }

  public void switchTo(final ExceptionHandler<? super T> exceptionHandler) {
    this.delegate = exceptionHandler;
  }
}
