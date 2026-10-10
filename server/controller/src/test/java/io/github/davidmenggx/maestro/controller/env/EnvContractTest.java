package io.github.davidmenggx.maestro.controller.env;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class EnvContractTest {

  private static final Clock CLOCK =
      new Clock() {
        @Override
        public long tick() {
          return 0;
        }

        @Override
        public long nowNanos() {
          return 0;
        }
      };

  private static final Offload OFFLOAD =
      new Offload() {
        @Override
        public <T> void submit(
            String name, Supplier<T> work, Consumer<T> completion, Runnable failure) {}
      };

  private static final Transport TRANSPORT =
      new Transport() {
        @Override
        public void send(NodeId node, Channel channel, ByteBuffer frame) {}

        @Override
        public void connect(NodeId node, String endpoint) {}

        @Override
        public void listen(Listener listener) {}
      };

  private static final Metrics METRICS =
      new Metrics() {
        @Override
        public void count(String name, long delta, String... labels) {}

        @Override
        public void record(String name, double value, String... labels) {}

        @Override
        public void gauge(String name, long value, String... labels) {}
      };

  @Test
  void channelsMatchTheirTransports() {
    // World and control use TCP, requests use UDP.
    assertTrue(Transport.Channel.WORLD.ordered());
    assertTrue(Transport.Channel.CONTROL.ordered());
    assertFalse(Transport.Channel.REQUEST.ordered());
  }

  @Test
  void nodeIdsAreNonNegativeValues() {
    assertThrows(IllegalArgumentException.class, () -> NodeId.of(-1));
    assertEquals(NodeId.of(3), new NodeId(3));
    assertEquals("node-3", NodeId.of(3).toString());
  }

  @Test
  void anEnvironmentHasEverySeam() {
    Rng rng = SeededRng.of(1L);
    new ControllerEnv(CLOCK, rng, OFFLOAD, TRANSPORT, METRICS);
    assertThrows(
        NullPointerException.class,
        () -> new ControllerEnv(null, rng, OFFLOAD, TRANSPORT, METRICS));
    assertThrows(
        NullPointerException.class,
        () -> new ControllerEnv(CLOCK, null, OFFLOAD, TRANSPORT, METRICS));
    assertThrows(
        NullPointerException.class, () -> new ControllerEnv(CLOCK, rng, null, TRANSPORT, METRICS));
    assertThrows(
        NullPointerException.class, () -> new ControllerEnv(CLOCK, rng, OFFLOAD, null, METRICS));
    assertThrows(
        NullPointerException.class, () -> new ControllerEnv(CLOCK, rng, OFFLOAD, TRANSPORT, null));
  }
}
