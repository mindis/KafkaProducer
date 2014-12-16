package com.neverwinterdp.kafka.producer.retry;

import java.util.concurrent.TimeUnit;

public interface RetryContext {
  boolean shouldRetry();

  long getDelay();

  TimeUnit getTimeUnit();

  long waitDuration();

  void await() throws InterruptedException;

  void reset();

  void setException(Exception e);

  void incrementRetryCount();

  void setShouldRetry(boolean b);
}