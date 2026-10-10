package io.github.davidmenggx.maestro.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
}
