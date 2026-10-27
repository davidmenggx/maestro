package io.github.davidmenggx.maestro.load;

import com.google.gson.GsonBuilder;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.github.davidmenggx.maestro.MaestroServer;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /maestro load start <scenario>} runs the scenario in `<game
 * dir>/maestro-load/scenarios/<scenario>.json` on the overworld, and {@code /maestro load stop}
 * ends it. A run that ends while measuring writes its report to `<game dir>/maestro-load/reports/`.
 * One run goes at a time, and the server stopping ends it.
 */
final class LoadCommand {

  /** Logged with each step of a run, so a script driving the server can wait for it. */
  static final String STARTED = "Maestro load started";

  static final String MEASURING = "Maestro load measuring";

  static final String DONE = "Maestro load done";

  static final String FAILED = "Maestro load failed";

  private static final Logger LOG = LoggerFactory.getLogger("maestro");

  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

  private static LoadRun run;

  private static String scenario;

  private LoadCommand() {}

  static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
    dispatcher.register(
        Commands.literal("maestro")
            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
            .then(
                Commands.literal("load")
                    .then(
                        Commands.literal("start")
                            .then(
                                Commands.argument("scenario", StringArgumentType.word())
                                    .executes(LoadCommand::start)))
                    .then(Commands.literal("stop").executes(LoadCommand::stop))));
  }

  private static Path directory() {
    return FabricLoader.getInstance().getGameDir().resolve("maestro-load");
  }

  private static int start(CommandContext<CommandSourceStack> context) {
    CommandSourceStack source = context.getSource();
    String name = StringArgumentType.getString(context, "scenario");
    if (run != null) {
      source.sendFailure(Component.literal("Scenario " + scenario + " is running; stop it first"));
      return 0;
    }
    try {
      Scenario loaded = Scenario.load(Scenario.file(directory().resolve("scenarios"), name));
      String version =
          FabricLoader.getInstance()
              .getModContainer(MaestroServer.MOD_ID)
              .orElseThrow()
              .getMetadata()
              .getVersion()
              .getFriendlyString();
      // The config the running mod uses, which the file may no longer match.
      run =
          LoadRun.start(
              name, loaded, source.getServer().overworld(), MaestroServer.config(), version);
      scenario = name;
      String message =
          String.format(
              "%s: scenario=%s players=%d entities=%d",
              STARTED, name, run.players(), loaded.entities());
      LOG.info(message);
      source.sendSuccess(() -> Component.literal(message), false);
      return 1;
    } catch (RuntimeException e) {
      LOG.error("{}: scenario={}: {}", FAILED, name, e.getMessage(), e);
      source.sendFailure(Component.literal(FAILED + ": " + e.getMessage()));
      return 0;
    }
  }

  private static int stop(CommandContext<CommandSourceStack> context) {
    if (run == null) {
      context.getSource().sendFailure(Component.literal("No scenario is running"));
      return 0;
    }
    String message = end("stopped");
    if (message.startsWith(FAILED)) {
      context.getSource().sendFailure(Component.literal(message));
      return 0;
    }
    context.getSource().sendSuccess(() -> Component.literal(message), false);
    return 1;
  }

  static void tickStarted() {
    if (run != null) {
      run.tickStarted();
    }
  }

  static void tickEnded() {
    if (run == null) {
      return;
    }
    boolean wasMeasuring = run.measuring();
    boolean over;
    try {
      over = run.tickEnded();
    } catch (RuntimeException e) {
      LoadRun failed = run;
      run = null;
      failed.abort();
      LOG.error("{}: scenario={}: {}", FAILED, scenario, e.getMessage(), e);
      return;
    }
    if (!wasMeasuring && run.measuring()) {
      LOG.info("{}: scenario={}", MEASURING, scenario);
    }
    if (over) {
      end("its duration ended");
    }
  }

  /** The server stops: a running scenario ends while the fleet can still be read. */
  static void serverStopping() {
    if (run != null) {
      end("the server stopped");
    }
  }

  /**
   * Ends the run, writing its report if it was measuring, and returns the line logged for it.
   * {@code why} says what ended it.
   */
  private static String end(String why) {
    LoadRun ending = run;
    run = null;
    String message;
    if (!ending.measuring()) {
      ending.abort();
      message = String.format("%s: scenario=%s: %s before measuring", FAILED, scenario, why);
      LOG.error(message);
      return message;
    }
    Report report = ending.finish();
    Path file =
        directory()
            .resolve("reports")
            .resolve(scenario + "-" + STAMP.format(ZonedDateTime.now(ZoneOffset.UTC)) + ".json");
    try {
      write(report, file);
    } catch (IOException e) {
      message = String.format("%s: scenario=%s: could not write %s", FAILED, scenario, file);
      LOG.error(message, e);
      return message;
    }
    message =
        String.format(
            "%s: scenario=%s ticks=%d report=%s", DONE, scenario, report.measuredTicks(), file);
    LOG.info(message);
    return message;
  }

  private static void write(Report report, Path file) throws IOException {
    Files.createDirectories(file.getParent());
    try (Writer writer = Files.newBufferedWriter(file)) {
      new GsonBuilder().setPrettyPrinting().create().toJson(report.toJson(), writer);
      writer.write('\n');
    }
  }
}
