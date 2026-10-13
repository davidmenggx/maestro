package io.github.davidmenggx.maestro.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PoolOffloadTest {

  @Test
  void completionsRunOnlyWhenDrained() throws InterruptedException {
    try (PoolOffload offload = new PoolOffload(1)) {
      CountDownLatch worked = new CountDownLatch(1);
      AtomicReference<String> published = new AtomicReference<>();
      AtomicBoolean failed = new AtomicBoolean();

      offload.submit(
          "job",
          () -> {
            worked.countDown();
            return "done";
          },
          published::set,
          () -> failed.set(true));

      assertTrue(worked.await(5, TimeUnit.SECONDS), "the job never ran");
      // The work is finished, but only `drain` may publish it.
      assertNull(published.get());

      for (int i = 0; i < 100 && published.get() == null; i++) {
        offload.drain();
        Thread.sleep(10);
      }
      assertEquals("done", published.get());
      assertFalse(failed.get());
    }
  }

  @Test
  void aFailedJobReportsItsFailureWhenDrainedAndPublishesNothing() throws InterruptedException {
    try (PoolOffload offload = new PoolOffload(1)) {
      CountDownLatch attempted = new CountDownLatch(1);
      AtomicReference<String> published = new AtomicReference<>();
      AtomicReference<Thread> failedOn = new AtomicReference<>();

      offload.<String>submit(
          "boom",
          () -> {
            attempted.countDown();
            throw new IllegalStateException("expected");
          },
          value -> published.set("published"),
          () -> failedOn.set(Thread.currentThread()));

      assertTrue(attempted.await(5, TimeUnit.SECONDS), "the job never ran");
      Thread.sleep(50);
      // Like a completion, the failure waits for the tick boundary.
      assertNull(failedOn.get());
      for (int i = 0; i < 100 && failedOn.get() == null; i++) {
        offload.drain();
        Thread.sleep(10);
      }
      assertEquals(Thread.currentThread(), failedOn.get());
      assertNull(published.get());
    }
  }

  @Test
  void anErrorInAJobIsReportedAsAFailure() throws InterruptedException {
    try (PoolOffload offload = new PoolOffload(1)) {
      AtomicBoolean failed = new AtomicBoolean();

      offload.<String>submit(
          "error",
          () -> {
            throw new StackOverflowError("expected");
          },
          value -> {},
          () -> failed.set(true));

      for (int i = 0; i < 100 && !failed.get(); i++) {
        offload.drain();
        Thread.sleep(10);
      }
      assertTrue(failed.get());
    }
  }

  @Test
  void aCompletionThatThrowsDoesNotStopTheOthers() throws InterruptedException {
    try (PoolOffload offload = new PoolOffload(1)) {
      AtomicBoolean second = new AtomicBoolean();

      offload.submit(
          "first",
          () -> "a",
          value -> {
            throw new IllegalStateException("expected");
          },
          () -> {});
      offload.submit("second", () -> "b", value -> second.set(true), () -> {});

      for (int i = 0; i < 100 && !second.get(); i++) {
        offload.drain();
        Thread.sleep(10);
      }
      assertTrue(second.get());
    }
  }

  @Test
  void aJobSubmittedAfterCloseFails() {
    PoolOffload offload = new PoolOffload(1);
    offload.close();
    AtomicBoolean completed = new AtomicBoolean();
    AtomicBoolean failed = new AtomicBoolean();

    offload.submit("late", () -> "a", value -> completed.set(true), () -> failed.set(true));
    offload.drain();

    assertTrue(failed.get(), "a job the pool refused reported nothing");
    assertFalse(completed.get());
  }

  @Test
  void aJobThatNeverStartedFailsWhenThePoolCloses() throws InterruptedException {
    PoolOffload offload = new PoolOffload(1);
    CountDownLatch running = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean queuedCompleted = new AtomicBoolean();
    AtomicBoolean queuedFailed = new AtomicBoolean();

    // Holds the only worker, so the second job waits in the queue.
    offload.submit(
        "busy",
        () -> {
          running.countDown();
          try {
            release.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return "a";
        },
        value -> {},
        () -> {});
    assertTrue(running.await(5, TimeUnit.SECONDS), "the first job never ran");
    offload.submit(
        "queued", () -> "b", value -> queuedCompleted.set(true), () -> queuedFailed.set(true));

    offload.close();
    release.countDown();
    offload.drain();

    assertTrue(queuedFailed.get(), "a job dropped by close reported nothing");
    assertFalse(queuedCompleted.get());
  }

  @Test
  void aRetryQueuedDuringADrainWaitsForTheNextOne() {
    PoolOffload offload = new PoolOffload(1);
    offload.close();
    AtomicInteger attempts = new AtomicInteger();

    // Every attempt fails at once, because the pool is closed, and its failure retries.
    Runnable[] retry = new Runnable[1];
    retry[0] =
        () -> {
          attempts.incrementAndGet();
          offload.submit("retried", () -> "a", value -> {}, retry[0]);
        };
    offload.submit("retried", () -> "a", value -> {}, retry[0]);

    offload.drain();
    assertEquals(1, attempts.get());
    offload.drain();
    assertEquals(2, attempts.get());
  }
}
