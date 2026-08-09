package com.smpler.maestro.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smpler.maestro.controller.env.Clock;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Reads the compiled controller and fails if it references a clock, a random source, a thread pool
 * or a socket directly. Those must arrive through {@code ControllerEnv}, or a replay stops being a
 * replay.
 *
 * <p>Iteration order is not checkable this way. The repeated-run digest in {@code SimulationTest}
 * covers it instead: identity hash codes differ between two runs in one JVM, so an identity-ordered
 * collection shows up there.
 */
class NondeterminismGuardTest {

  /** {@code member} is a method or field name, or {@code *} for the whole type. */
  private record Rule(String owner, String member) {}

  private static final List<Rule> RULES =
      List.of(
          new Rule("java/lang/System", "currentTimeMillis"),
          new Rule("java/lang/System", "nanoTime"),
          new Rule("java/lang/System", "identityHashCode"),
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
          new Rule("java/nio/channels/DatagramChannel", "*"));

  /** flatc output is not controller code and is not held to the rule. */
  private static final String GENERATED = "maestro/fbs/";

  @Test
  void controllerCodeTakesItsNondeterminismFromTheSeams() throws IOException, URISyntaxException {
    List<String> findings = scan(classesRoot(Clock.class));
    assertEquals(List.of(), findings);
  }

  @Test
  void theScanFindsAViolationWhenThereIsOne() throws IOException, URISyntaxException {
    Path self =
        classesRoot(NondeterminismGuardTest.class)
            .resolve("com/smpler/maestro/controller/NondeterminismGuardTest$Naughty.class");
    assertTrue(Files.exists(self), "expected the sample class at " + self);

    List<String> findings = scan(self);
    assertFalse(findings.isEmpty(), "the scan missed a direct System.nanoTime call");
  }

  /** Deliberately breaks the rule, so the scan has something to catch. */
  static final class Naughty {
    long now() {
      return System.nanoTime();
    }
  }

  private static Path classesRoot(Class<?> anchor) throws URISyntaxException {
    return Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
  }

  private static List<String> scan(Path root) throws IOException {
    List<String> findings = new ArrayList<>();
    if (Files.isRegularFile(root)) {
      inspect(root, root.getFileName().toString(), findings);
      return findings;
    }
    try (Stream<Path> files = Files.walk(root)) {
      List<Path> classes =
          files
              .filter(p -> p.toString().endsWith(".class"))
              .filter(p -> !root.relativize(p).toString().replace('\\', '/').startsWith(GENERATED))
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
        if (rule.owner().equals(owner)
            && (rule.member().equals("*") || rule.member().equals(member))) {
          findings.add(label + " references " + owner + "." + member);
        }
      }
    }
  }
}
