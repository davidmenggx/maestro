package io.github.davidmenggx.maestro.load;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The scenarios `tools/bench.py` runs, read as the load mod reads them. */
class BundledScenariosTest {

  @BeforeAll
  static void bootstrap() {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
  }

  private static List<Path> scenarios() throws IOException {
    try (Stream<Path> files = Files.list(Path.of(System.getProperty("maestro.scenarios")))) {
      return files.filter(path -> path.toString().endsWith(".json")).sorted().toList();
    }
  }

  @Test
  void eachLoadsAndNamesOnlyRealEntitiesAndAttributes() throws IOException {
    List<Path> paths = scenarios();
    assertFalse(paths.isEmpty(), "no scenarios in " + System.getProperty("maestro.scenarios"));
    for (Path path : paths) {
      Scenario scenario = Scenario.load(path);
      for (Scenario.Group group : scenario.groups()) {
        String where = path.getFileName() + ": " + group.entity();
        assertTrue(
            BuiltInRegistries.ENTITY_TYPE.containsKey(Identifier.parse(group.entity())), where);
        CompoundTag tag;
        try {
          tag = TagParser.parseCompoundFully(group.nbt());
        } catch (CommandSyntaxException e) {
          throw new AssertionError(where + ": " + e.getMessage(), e);
        }
        // A misspelt attribute would be dropped without a word as the entity loads.
        for (Tag attribute : tag.getListOrEmpty("attributes")) {
          String id = ((CompoundTag) attribute).getStringOr("id", "");
          assertTrue(
              BuiltInRegistries.ATTRIBUTE.containsKey(Identifier.parse(id)), where + ": " + id);
        }
      }
    }
  }
}
