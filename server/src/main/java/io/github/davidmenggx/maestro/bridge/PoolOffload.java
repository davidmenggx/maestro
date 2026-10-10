package io.github.davidmenggx.maestro.bridge;

import io.github.davidmenggx.maestro.controller.env.Offload;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production {@link Offload}. Work runs on a small daemon pool. Its completion or failure is queued
 * for {@link #drain}, which the server thread calls at the tick boundary, so results reach the
 * controller from one thread only.
 */
public final class PoolOffload implements Offload, AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger("maestro");

  private final ExecutorService workers;
  private final Queue<Runnable> completions = new ConcurrentLinkedQueue<>();

  public PoolOffload(int threads) {
    this.workers =
        Executors.newFixedThreadPool(
            threads,
            runnable -> {
              Thread thread = new Thread(runnable, "maestro-offload");
              thread.setDaemon(true);
              return thread;
            });
  }

  @Override
  public <T> void submit(String name, Supplier<T> work, Consumer<T> completion, Runnable failure) {
    workers.execute(
        () -> {
          T result;
          try {
            result = work.get();
          } catch (RuntimeException e) {
            // The caller decides how to handle a failure.
            LOG.error("Maestro offload job '{}' failed", name, e);
            completions.add(failure);
            return;
          }
          completions.add(() -> completion.accept(result));
        });
  }

  /** Runs every finished job's completion or failure on the calling thread. */
  public void drain() {
    Runnable completion;
    while ((completion = completions.poll()) != null) {
      completion.run();
    }
  }

  @Override
  public void close() {
    workers.shutdownNow();
  }
}
