package com.neverwinterdp.kafka.producer.retry;

import org.apache.log4j.Logger;

// TODO rename
public class RetryRunnable implements Runnable {

  private static final Logger logger = Logger.getLogger(RetryRunnable.class);
  private RetryStrategy retryStrategy;
  private RetryableRunnable runnable;
  private boolean isSuccess;

  public RetryRunnable(RetryStrategy retryStrategy, RetryableRunnable runnable) {
    super();
    this.retryStrategy = retryStrategy;
    this.runnable = runnable;
    isSuccess = false;
  }

  @Override
  public void run() {
    retryStrategy.reset();
    do {
      try {
        runnable.run();
        isSuccess = true;
        retryStrategy.shouldRetry(false);
      } catch (Exception ex) {
        logger.debug("We got an exception: " + ex.toString());
        retryStrategy.errorOccured(ex);
        if (retryStrategy.shouldRetry()) {
          try {
            runnable.beforeRetry();
            retryStrategy.await(retryStrategy.getWaitDuration());
          } catch (InterruptedException e) {
            retryStrategy.shouldRetry(false);
          }
        } else {
          throw new RetryException("Runnable did not complete succesfully after "
              + retryStrategy.getRetries() + ". Last Exception was "
              + ex.getCause());
        }
      }
    } while (retryStrategy.shouldRetry());
  }

  public RetryStrategy getRetryStrategy() {
    return retryStrategy;
  }

  public void setRetryStrategy(RetryStrategy retryStrategy) {
    this.retryStrategy = retryStrategy;
  }

  public RetryableRunnable getRunnable() {
    return runnable;
  }

  public void setRunnable(RetryableRunnable runnable) {
    this.runnable = runnable;
  }

  public boolean isSuccess() {
    return isSuccess;
  }

  public void setSuccess(boolean isSuccess) {
    this.isSuccess = isSuccess;
  }
}