package io.github.davidmenggx.maestro.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.controller.env.Offload.Lane;
import java.util.ArrayList;
import java.util.List;
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
          Lane.WORLD,
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
          Lane.WORLD,
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
          Lane.WORLD,
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
          Lane.WORLD,
          () -> "a",
          value -> {
            throw new IllegalStateException("expected");
          },
          () -> {});
      offload.submit("second", Lane.WORLD, () -> "b", value -> second.set(true), () -> {});

      for (int i = 0; i < 100 && !second.get(); i++) {
        offload.drain();
        Thread.sleep(10);
      }
      assertTrue(second.get());
    }
  }

  @Test
  void aWorkerTakesQueuedRequestJobsBeforeQueuedWorldJobs() throws InterruptedException {
    try (PoolOffload offload = new PoolOffload(1)) {
      CountDownLatch running = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      List<String> ran = new ArrayList<>();

      // Holds the only worker, so every later job waits in the queue.
      offload.submit(
          "busy",
          Lane.WORLD,
          () -> {
            running.countDown();
            try {
              release.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            return "busy";
          },
          value -> {},
          () -> {});
      assertTrue(running.await(5, TimeUnit.SECONDS), "the first job never ran");
      for (String name : List.of("world 1", "request 1", "world 2", "request 2")) {
        Lane lane = name.startsWith("request") ? Lane.REQUESTS : Lane.WORLD;
        offload.submit(name, lane, () -> name, ran::add, () -> {});
      }
      release.countDown();

      for (int i = 0; i < 100 && ran.size() < 4; i++) {
        offload.drain();
        Thread.sleep(10);
      }
      // Completions are queued in the order the jobs ran.
      assertEquals(List.of("request 1", "request 2", "world 1", "world 2"), ran);
    }
  }

  @Test
  void aJobSubmittedAfterCloseFails() {
    PoolOffload offload = new PoolOffload(1);
    offload.close();
    AtomicBoolean completed = new AtomicBoolean();
    AtomicBoolean failed = new AtomicBoolean();

    offload.submit(
        "late", Lane.WORLD, () -> "a", value -> completed.set(true), () -> failed.set(true));
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
        Lane.WORLD,
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
        "queued",
        Lane.WORLD,
        () -> "b",
        value -> queuedCompleted.set(true),
        () -> queuedFailed.set(true));

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
          offload.submit("retried", Lane.WORLD, () -> "a", value -> {}, retry[0]);
        };
    offload.submit("retried", Lane.WORLD, () -> "a", value -> {}, retry[0]);

    offload.drain();
    assertEquals(1, attempts.get());
    offload.drain();
    assertEquals(2, attempts.get());
  }
}
