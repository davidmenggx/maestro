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
 * Production {@link Offload}. Work runs on a small daemon pool and its completion is queued for
 * {@link #drain}, which the server thread calls at the tick boundary, so results only ever reach
 * the controller from one thread.
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
  public <T> void submit(String name, Supplier<T> work, Consumer<T> completion) {
    workers.execute(
        () -> {
          try {
            T result = work.get();
            completions.add(() -> completion.accept(result));
          } catch (RuntimeException e) {
            // A failed job is dropped rather than retried: the caller times out.
            LOG.error("Maestro offload job '{}' failed", name, e);
          }
        });
  }

  /** Runs every finished job's completion on the calling thread. */
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
