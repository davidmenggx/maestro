package io.github.davidmenggx.maestro.bridge;

import io.github.davidmenggx.maestro.controller.env.Offload;
import java.util.Comparator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production {@link Offload}. Work runs on a small daemon pool. Its completion or failure is queued
 * for {@link #drain}, which the server thread calls at the tick boundary, so results reach the
 * controller from one thread only. A free worker takes the oldest job of the first {@link Lane}
 * that has one, so a request job never waits behind a world job that has not started.
 *
 * <p>Every submitted job queues exactly one of the two, also after {@link #close}: a job that can
 * no longer run queues its failure.
 */
public final class PoolOffload implements Offload, AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger("maestro");

  /** Lanes in declaration order, then submission order within a lane. */
  private static final Comparator<Runnable> ORDER =
      Comparator.comparing((Runnable job) -> ((Job<?>) job).lane)
          .thenComparingLong(job -> ((Job<?>) job).order);

  /** One submitted job. Queues its completion or its failure, never both. */
  private final class Job<T> implements Runnable {

    private final String name;
    private final Lane lane;
    private final long order;
    private final Supplier<T> work;
    private final Consumer<T> completion;
    private final Runnable failure;

    Job(
        String name,
        Lane lane,
        long order,
        Supplier<T> work,
        Consumer<T> completion,
        Runnable failure) {
      this.name = name;
      this.lane = lane;
      this.order = order;
      this.work = work;
      this.completion = completion;
      this.failure = failure;
    }

    @Override
    public void run() {
      T result;
      try {
        result = work.get();
      } catch (Throwable e) {
        // An `Error` is reported too: a job that posts nothing would leave its caller
        // waiting forever. The caller decides how to handle a failure.
        LOG.error("Maestro offload job '{}' failed", name, e);
        completions.add(failure);
        return;
      }
      completions.add(() -> completion.accept(result));
    }

    /** The job will not run. */
    void abandon() {
      LOG.warn("Maestro offload job '{}' abandoned: the pool is closed", name);
      completions.add(failure);
    }
  }

  private final ThreadPoolExecutor workers;
  private final AtomicLong submitted = new AtomicLong();
  private final Queue<Runnable> completions = new ConcurrentLinkedQueue<>();

  public PoolOffload(int threads) {
    this.workers =
        new ThreadPoolExecutor(
            threads,
            threads,
            0L,
            TimeUnit.MILLISECONDS,
            // 11 is the default initial capacity. The queue grows as needed.
            new PriorityBlockingQueue<>(11, ORDER),
            runnable -> {
              Thread thread = new Thread(runnable, "maestro-offload");
              thread.setDaemon(true);
              return thread;
            });
    // A job submitted while a worker has yet to start would run on it at once, skipping the order.
    workers.prestartAllCoreThreads();
  }

  @Override
  public <T> void submit(
      String name, Lane lane, Supplier<T> work, Consumer<T> completion, Runnable failure) {
    Job<T> job = new Job<>(name, lane, submitted.getAndIncrement(), work, completion, failure);
    try {
      workers.execute(job);
    } catch (RejectedExecutionException e) {
      job.abandon();
    }
  }

  /**
   * Runs the completions and failures queued before this call, on the calling thread. One queued
   * while it runs, such as a retry submitted by a failure, waits for the next call, so a job that
   * keeps failing cannot hold up the tick boundary. One that throws is logged and does not stop the
   * others.
   */
  public void drain() {
    // Only this thread removes entries, so at least this many are present.
    for (int pending = completions.size(); pending > 0; pending--) {
      try {
        completions.poll().run();
      } catch (RuntimeException e) {
        LOG.error("Maestro offload completion failed", e);
      }
    }
  }

  /** Stops the workers. Jobs that had not started queue their failure. */
  @Override
  public void close() {
    for (Runnable notStarted : workers.shutdownNow()) {
      ((Job<?>) notStarted).abandon();
    }
  }
}
