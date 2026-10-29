package io.github.davidmenggx.maestro.obs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.davidmenggx.maestro.controller.env.Metrics;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TickTimerTest {

  private final List<String> recorded = new ArrayList<>();
  private long nanos;

  @Test
  void eachTickIsRecordedInMillisecondsFromItsStart() {
    TickTimer timer = new TickTimer(new Recorder(), () -> nanos);

    nanos = 5_000_000;
    timer.tickStarted();
    nanos = 17_500_000;
    timer.tickEnded();
    timer.tickStarted();
    nanos = 18_000_000;
    timer.tickEnded();

    assertEquals(List.of("server.tick.millis=12.5", "server.tick.millis=0.5"), recorded);
  }

  private final class Recorder implements Metrics {

    @Override
    public void count(String name, long delta, String... labels) {
      recorded.add(name + "+" + delta);
    }

    @Override
    public void record(String name, double value, String... labels) {
      recorded.add(name + "=" + value + String.join(",", labels));
    }

    @Override
    public void gauge(String name, double value, String... labels) {
      recorded.add(name + ":" + value);
    }
  }
}
