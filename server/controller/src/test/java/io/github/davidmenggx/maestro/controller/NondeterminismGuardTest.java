package io.github.davidmenggx.maestro.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.controller.env.Clock;
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
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Fails if the compiled controller directly references a clock, random source, thread pool, socket
 * or host. These must come through {@code ControllerEnv}. So must math whose last bit may differ
 * between platforms: use {@code StrictMath}, not {@code Math}.
 *
 * <p>It also refuses hashed collections and the immutable {@code Set.of}/{@code Map.of} family,
 * whose iteration order is not insertion or key order. The repeated-run digest in {@code
 * SimulationTest} misses the second kind, since both runs share a JVM.
 */
class NondeterminismGuardTest {

  /**
   * {@code owner} is an internal class name or {@code *}. {@code member} is a method or field name
   * or {@code *}. {@code descriptor}, if not null, narrows the rule to one signature.
   */
  private record Rule(String owner, String member, String descriptor) {

    Rule(String owner, String member) {
      this(owner, member, null);
    }
  }

  /** {@code Math} functions that may round differently on another platform. */
  private static final List<String> PLATFORM_MATH =
      List.of(
          "exp", "expm1", "log", "log10", "log1p", "pow", "sin", "cos", "tan", "asin", "acos",
          "atan", "atan2", "sinh", "cosh", "tanh", "cbrt", "hypot");

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
          new Rule("java/util/SplittableRandom", "<init>", "()V"),
          new Rule("java/util/random/RandomGenerator", "getDefault"),
          new Rule("java/util/random/RandomGenerator", "of"),
          new Rule(
              "java/util/random/RandomGeneratorFactory",
              "create",
              "()Ljava/util/random/RandomGenerator;"),
          new Rule("java/util/concurrent/ThreadLocalRandom", "*"),
          new Rule("java/security/SecureRandom", "*"),
          new Rule("java/util/UUID", "randomUUID"),
          // Shuffles with a hidden random source unless given one, which the seam provides.
          new Rule("java/util/Collections", "shuffle"),
          // A parallel stream's side-effect order depends on thread scheduling.
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

  private static final List<Rule> ALL_RULES =
      Stream.concat(
              RULES.stream(), PLATFORM_MATH.stream().map(name -> new Rule("java/lang/Math", name)))
          .toList();

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
          DefaultGenerator.class,
          UnseededFactory.class,
          PlatformMath.class,
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
    for (Rule rule : ALL_RULES) {
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
  static final class DefaultGenerator {
    long draw() {
      return RandomGenerator.getDefault().nextLong();
    }
  }

  @SuppressWarnings("unused")
  static final class UnseededFactory {
    long draw() {
      return RandomGeneratorFactory.of("L64X128MixRandom").create().nextLong();
    }
  }

  @SuppressWarnings("unused")
  static final class PlatformMath {
    double odds(double phi) {
      return Math.pow(10, -phi);
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

    long draw(long seed) {
      return RandomGeneratorFactory.of("L64X128MixRandom").create(seed).nextLong();
    }

    double odds(double phi) {
      return StrictMath.pow(10, -phi) + Math.sqrt(phi) + Math.max(phi, 1);
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
      String descriptor = ref.type().stringValue();
      for (Rule rule : ALL_RULES) {
        if ((rule.owner().equals("*") || rule.owner().equals(owner))
            && (rule.member().equals("*") || rule.member().equals(member))
            && (rule.descriptor() == null || rule.descriptor().equals(descriptor))) {
          findings.add(label + " references " + owner + "." + member);
        }
      }
    }
  }
}
