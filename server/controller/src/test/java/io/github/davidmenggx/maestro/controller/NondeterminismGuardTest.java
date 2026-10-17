package io.github.davidmenggx.maestro.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.controller.env.Clock;
import io.github.davidmenggx.maestro.controller.sim.Controller;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.net.URISyntaxException;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Reads the compiled controller, and the simulated controller that will become it, and fails if
 * either directly references a clock, a random source, a thread pool, a socket or the host it runs
 * on. These must come through {@code ControllerEnv}, or replay breaks.
 *
 * <p>It also refuses collections whose iteration order is neither insertion nor key order: hashed
 * ones, whose order follows identity hash codes for most keys, and the immutable {@code Set.of} and
 * {@code Map.of} family, whose order is randomized per JVM. The repeated-run digest in {@code
 * SimulationTest} catches the first kind but not the second, since both of its runs share a JVM.
 */
class NondeterminismGuardTest {

  /**
   * {@code owner} is an internal class name, or {@code *} for any class. {@code member} is a method
   * or field name, or {@code *} for the whole type.
   */
  private record Rule(String owner, String member) {}

  private static final List<Rule> RULES =
      List.of(
          new Rule("java/lang/System", "currentTimeMillis"),
          new Rule("java/lang/System", "nanoTime"),
          new Rule("java/lang/System", "identityHashCode"),
          new Rule("java/lang/System", "getenv"),
          new Rule("java/lang/Runtime", "availableProcessors"),
          new Rule("java/lang/Math", "random"),
          new Rule("java/time/Instant", "now"),
          new Rule("java/time/Clock", "*"),
          new Rule("java/time/LocalDate", "now"),
          new Rule("java/time/LocalDateTime", "now"),
          new Rule("java/time/ZonedDateTime", "now"),
          new Rule("java/util/Random", "*"),
          new Rule("java/util/concurrent/ThreadLocalRandom", "*"),
          new Rule("java/security/SecureRandom", "*"),
          new Rule("java/util/UUID", "randomUUID"),
          // Shuffles with a hidden random source unless given one, which the seam provides.
          new Rule("java/util/Collections", "shuffle"),
          // A parallel stream's order of side effects depends on thread scheduling. The owner is
          // whatever collection or stream type the call was made on.
          new Rule("*", "parallelStream"),
          new Rule("*", "parallel"),
          new Rule("java/util/concurrent/Executors", "*"),
          new Rule("java/util/concurrent/CompletableFuture", "*"),
          new Rule("java/util/concurrent/ForkJoinPool", "*"),
          new Rule("java/util/concurrent/ThreadPoolExecutor", "*"),
          new Rule("java/util/concurrent/ScheduledThreadPoolExecutor", "*"),
          new Rule("java/util/Timer", "*"),
          new Rule("java/lang/Thread", "*"),
          new Rule("java/net/Socket", "*"),
          new Rule("java/net/ServerSocket", "*"),
          new Rule("java/net/DatagramSocket", "*"),
          new Rule("java/net/http/HttpClient", "*"),
          new Rule("java/nio/channels/SocketChannel", "*"),
          new Rule("java/nio/channels/ServerSocketChannel", "*"),
          new Rule("java/nio/channels/DatagramChannel", "*"),
          new Rule("java/util/HashMap", "*"),
          new Rule("java/util/HashSet", "*"),
          new Rule("java/util/IdentityHashMap", "*"),
          new Rule("java/util/WeakHashMap", "*"),
          new Rule("java/util/concurrent/ConcurrentHashMap", "*"),
          new Rule("java/util/Set", "of"),
          new Rule("java/util/Set", "copyOf"),
          new Rule("java/util/Map", "of"),
          new Rule("java/util/Map", "ofEntries"),
          new Rule("java/util/Map", "copyOf"),
          new Rule("java/util/stream/Collectors", "toSet"),
          new Rule("java/util/stream/Collectors", "toUnmodifiableSet"),
          new Rule("java/util/stream/Collectors", "toMap"),
          new Rule("java/util/stream/Collectors", "toUnmodifiableMap"),
          new Rule("java/util/stream/Collectors", "groupingBy"));

  /** The simulated controller, which is to become the production one. */
  private static final Class<?> SIMULATED_CONTROLLER = Controller.class;

  /** flatc output is not controller code, so the rule does not apply. */
  private static final String GENERATED = "maestro/fbs/";

  /** One sample for each category of violation the rules catch. */
  private static final List<Class<?>> VIOLATIONS =
      List.of(
          WallClock.class,
          MonotonicClock.class,
          InstantClock.class,
          Randomness.class,
          CoinFlip.class,
          Identity.class,
          OwnThread.class,
          ThreadPool.class,
          AsyncFuture.class,
          RawSocket.class,
          HashOrdered.class,
          SaltedOrder.class,
          Shuffled.class,
          ParallelSideEffects.class,
          HostDependent.class);

  @Test
  void controllerCodeTakesItsNondeterminismFromTheSeams() throws IOException, URISyntaxException {
    assertEquals(List.of(), scanTree(classesRoot(Clock.class)));
  }

  @Test
  void theSimulatedControllerTakesItsNondeterminismFromTheSeams()
      throws IOException, URISyntaxException {
    List<String> findings = scanClassAndNested(SIMULATED_CONTROLLER);
    assertEquals(List.of(), findings);
  }

  @Test
  void theScanCatchesEveryCategoryItClaimsTo() throws IOException, URISyntaxException {
    List<String> missed = new ArrayList<>();
    for (Class<?> sample : VIOLATIONS) {
      if (scanClass(sample).isEmpty()) {
        missed.add(sample.getSimpleName());
      }
    }
    assertEquals(List.of(), missed, "the scan let a known violation through");
  }

  @Test
  void theScanLeavesSeamOnlyCodeAlone() throws IOException, URISyntaxException {
    assertEquals(List.of(), scanClass(Wellbehaved.class));
  }

  @Test
  void everyRuleIsWellFormed() {
    for (Rule rule : RULES) {
      assertFalse(rule.owner().contains("."), "owners are internal names: " + rule.owner());
      assertFalse(rule.member().isBlank(), "empty member in " + rule.owner());
      // Any member of any class would match everything.
      assertFalse(rule.owner().equals("*") && rule.member().equals("*"), "a rule matches all");
    }
  }

  // Samples. Each breaks the rule in exactly one way.

  @SuppressWarnings("unused")
  static final class WallClock {
    long now() {
      return System.currentTimeMillis();
    }
  }

  @SuppressWarnings("unused")
  static final class MonotonicClock {
    long now() {
      return System.nanoTime();
    }
  }

  @SuppressWarnings("unused")
  static final class InstantClock {
    long now() {
      return Instant.now().toEpochMilli();
    }
  }

  @SuppressWarnings("unused")
  static final class Randomness {
    int draw() {
      return new Random().nextInt();
    }
  }

  @SuppressWarnings("unused")
  static final class CoinFlip {
    double draw() {
      return Math.random();
    }
  }

  @SuppressWarnings("unused")
  static final class Identity {
    String key() {
      return UUID.randomUUID().toString();
    }
  }

  @SuppressWarnings("unused")
  static final class OwnThread {
    void go(Runnable task) {
      new Thread(task).start();
    }
  }

  @SuppressWarnings("unused")
  static final class ThreadPool {
    void go(Runnable task) {
      Executors.newSingleThreadExecutor().execute(task);
    }
  }

  @SuppressWarnings("unused")
  static final class AsyncFuture {
    void go() {
      CompletableFuture.runAsync(() -> {});
    }
  }

  @SuppressWarnings("unused")
  static final class RawSocket {
    void go() throws IOException {
      SocketChannel.open().close();
    }
  }

  @SuppressWarnings("unused")
  static final class HashOrdered {
    Map<Object, Integer> index() {
      return new HashMap<>();
    }
  }

  @SuppressWarnings("unused")
  static final class SaltedOrder {
    Set<String> names() {
      return Set.of("a", "b");
    }
  }

  @SuppressWarnings("unused")
  static final class Shuffled {
    void mix(List<Integer> values) {
      Collections.shuffle(values);
    }
  }

  @SuppressWarnings("unused")
  static final class ParallelSideEffects {
    void visit(List<Integer> values, List<Integer> seen) {
      values.parallelStream().forEach(seen::add);
    }
  }

  @SuppressWarnings("unused")
  static final class HostDependent {
    int workers() {
      return Runtime.getRuntime().availableProcessors();
    }
  }

  /** The negative control: takes time from the seam and uses nothing forbidden. */
  @SuppressWarnings("unused")
  static final class Wellbehaved {
    long now(Clock clock) {
      return clock.tick() + clock.nowNanos();
    }
  }

  private static Path classesRoot(Class<?> anchor) throws URISyntaxException {
    return Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
  }

  private static List<String> scanClass(Class<?> target) throws IOException, URISyntaxException {
    Path file = classesRoot(target).resolve(target.getName().replace('.', '/') + ".class");
    assertTrue(Files.exists(file), "expected a compiled sample at " + file);
    List<String> findings = new ArrayList<>();
    inspect(file, target.getSimpleName(), findings);
    return findings;
  }

  /** {@code target} and the classes nested in it, which compile to files of their own. */
  private static List<String> scanClassAndNested(Class<?> target)
      throws IOException, URISyntaxException {
    Path file = classesRoot(target).resolve(target.getName().replace('.', '/') + ".class");
    assertTrue(Files.exists(file), "expected compiled classes at " + file);
    String prefix = target.getSimpleName();
    List<String> findings = new ArrayList<>();
    try (Stream<Path> siblings = Files.list(file.getParent())) {
      for (Path sibling :
          siblings
              .filter(
                  path -> {
                    String name = path.getFileName().toString();
                    return name.equals(prefix + ".class") || name.startsWith(prefix + "$");
                  })
              .sorted()
              .toList()) {
        inspect(sibling, sibling.getFileName().toString(), findings);
      }
    }
    return findings;
  }

  private static List<String> scanTree(Path root) throws IOException {
    List<String> findings = new ArrayList<>();
    try (Stream<Path> files = Files.walk(root)) {
      List<Path> classes =
          files
              .filter(path -> path.toString().endsWith(".class"))
              .filter(
                  path ->
                      !root.relativize(path).toString().replace('\\', '/').startsWith(GENERATED))
              .sorted()
              .toList();
      for (Path file : classes) {
        inspect(file, root.relativize(file).toString(), findings);
      }
    }
    return findings;
  }

  private static void inspect(Path file, String label, List<String> findings) throws IOException {
    ClassModel model = ClassFile.of().parse(Files.readAllBytes(file));
    for (PoolEntry entry : model.constantPool()) {
      if (!(entry instanceof MemberRefEntry ref)) {
        continue;
      }
      String owner = ref.owner().asInternalName();
      String member = ref.name().stringValue();
      for (Rule rule : RULES) {
        if ((rule.owner().equals("*") || rule.owner().equals(owner))
            && (rule.member().equals("*") || rule.member().equals(member))) {
          findings.add(label + " references " + owner + "." + member);
        }
      }
    }
  }
}
