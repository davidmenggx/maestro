package io.github.davidmenggx.maestro.capture;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.io.IOException;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /maestro capture <seed> <cases>}: builds the terrain for {@code seed} in the overworld,
 * records {@code cases} vanilla searches on it, and writes them to {@code
 * maestro-capture/seed-<seed>.bin} in the game directory. Registered by the development mod only,
 * since it overwrites part of the world.
 */
public final class CaptureCommand {

  /** Logged when a capture finishes, so a script driving the server can wait for it. */
  public static final String DONE = "Maestro capture done";

  /** Logged when a capture fails. */
  public static final String FAILED = "Maestro capture failed";

  private static final Logger LOG = LoggerFactory.getLogger("maestro");

  private static final int MAX_CASES = 10_000;

  private CaptureCommand() {}

  public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
    dispatcher.register(
        Commands.literal("maestro")
            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
            .then(
                Commands.literal("capture")
                    .then(
                        Commands.argument("seed", LongArgumentType.longArg())
                            .then(
                                Commands.argument(
                                        "cases", IntegerArgumentType.integer(1, MAX_CASES))
                                    .executes(CaptureCommand::capture)))));
  }

  private static int capture(CommandContext<CommandSourceStack> context) {
    long seed = LongArgumentType.getLong(context, "seed");
    int cases = IntegerArgumentType.getInteger(context, "cases");
    CommandSourceStack source = context.getSource();
    Path file =
        FabricLoader.getInstance()
            .getGameDir()
            .resolve("maestro-capture")
            .resolve("seed-" + seed + ".bin");
    try {
      ParityCapture.Summary summary =
          ParityCapture.run(source.getServer().overworld(), seed, cases, file);
      String message =
          String.format(
              "%s: seed=%d cases=%d exact=%d reached=%d offStart=%d modelDiffered=%d deltas=%d"
                  + " file=%s",
              DONE,
              seed,
              summary.cases(),
              summary.exact(),
              summary.reached(),
              summary.offStart(),
              summary.modelDiffered(),
              summary.deltas(),
              file);
      LOG.info(message);
      source.sendSuccess(() -> Component.literal(message), false);
      return summary.cases();
    } catch (IOException | RuntimeException e) {
      LOG.error("{}: seed={}", FAILED, seed, e);
      source.sendFailure(Component.literal(FAILED + ": " + e.getMessage()));
      return 0;
    }
  }
}
