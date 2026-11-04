package io.github.davidmenggx.maestro.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The load mod runs without the released mod, so only {@link InstalledMaestro}, loaded only when
 * the released mod is, may name the released mod's classes.
 */
class OptionalMaestroTest {

  /** A class of this project's, named in a class file. */
  private static final Pattern PROJECT_CLASS =
      Pattern.compile("io/github/davidmenggx/maestro/([A-Za-z0-9_$/]+)");

  @Test
  void onlyInstalledMaestroNamesTheReleasedMod() throws IOException, URISyntaxException {
    Path root = Path.of(LoadMod.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    String allowed = InstalledMaestro.class.getName().replace('.', '/') + ".class";
    List<String> findings = new ArrayList<>();
    List<String> scanned = new ArrayList<>();
    try (Stream<Path> files = Files.walk(root)) {
      for (Path file : files.filter(f -> f.toString().endsWith(".class")).sorted().toList()) {
        String name = root.relativize(file).toString().replace('\\', '/');
        scanned.add(name);
        if (!name.equals(allowed)) {
          releasedModNames(file).forEach(named -> findings.add(name + " names " + named));
        }
      }
    }
    assertTrue(scanned.contains(allowed), "no load mod classes under " + root);
    assertEquals(List.of(), findings);
    // The scan finds the names in the one class allowed them.
    assertFalse(releasedModNames(root.resolve(allowed)).isEmpty());
  }

  /** The released mod's classes named in the class file {@code file}. */
  private static List<String> releasedModNames(Path file) throws IOException {
    List<String> names = new ArrayList<>();
    for (PoolEntry entry : ClassFile.of().parse(Files.readAllBytes(file)).constantPool()) {
      if (entry instanceof Utf8Entry text) {
        Matcher named = PROJECT_CLASS.matcher(text.stringValue());
        while (named.find()) {
          if (!named.group(1).startsWith("load/")) {
            names.add(named.group());
          }
        }
      }
    }
    return names;
  }

  @Test
  void withoutTheReleasedModNothingIsDescribedOrOffloaded() {
    assertNull(Maestro.ABSENT.release());
    assertEquals(0, Maestro.ABSENT.run());
    assertEquals(Offloads.NONE, Maestro.ABSENT.stats());
  }
}
