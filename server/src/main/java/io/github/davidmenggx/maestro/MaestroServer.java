package io.github.davidmenggx.maestro;

import io.github.davidmenggx.maestro.bridge.PoolOffload;
import io.github.davidmenggx.maestro.bridge.ServerClock;
import io.github.davidmenggx.maestro.capture.CaptureCommand;
import io.github.davidmenggx.maestro.config.MaestroConfig;
import io.github.davidmenggx.maestro.obs.Telemetry;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MaestroServer implements DedicatedServerModInitializer {

  public static final String MOD_ID = "maestro";
  public static final String VERSION = "0.1.0";

  /** Serialization is the only offloaded work, so one worker is enough. */
  private static final int OFFLOAD_THREADS = 1;

  private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

  private static MaestroConfig config;
  private static ServerClock clock;
  private static PoolOffload offload;

  public static MaestroConfig config() {
    return initialized(config, "config");
  }

  /** Production half of the controller's time seam. */
  public static ServerClock clock() {
    return initialized(clock, "clock");
  }

  /** Production half of the controller's off-main-thread seam. */
  public static PoolOffload offload() {
    return initialized(offload, "offload");
  }

  private static <T> T initialized(T value, String name) {
    if (value == null) {
      throw new IllegalStateException("Maestro " + name + " read before initialization");
    }
    return value;
  }

  @Override
  public void onInitializeServer() {
    config =
        MaestroConfig.load(
            FabricLoader.getInstance().getConfigDir().resolve(MaestroConfig.FILE_NAME));
    Telemetry.init(config, VERSION);

    clock = new ServerClock();
    offload = new PoolOffload(OFFLOAD_THREADS);
    // Offloaded results are published on the server thread at the tick boundary,
    // before the counter advances, so a completion counts toward the tick that
    // just ran, not the next one.
    ServerTickEvents.END_SERVER_TICK.register(
        server -> {
          offload.drain();
          clock.advance();
        });
    if (config.devMode()) {
      CommandRegistrationCallback.EVENT.register(
          (dispatcher, buildContext, selection) -> CaptureCommand.register(dispatcher));
    }
    ServerLifecycleEvents.SERVER_STOPPED.register(
        server -> {
          offload.close();
          Telemetry.shutdown();
        });

    LOG.info(
        "Maestro {} initializing: mode={} subMode={} otlp={} dev={}",
        VERSION,
        config.mode(),
        config.subMode(),
        config.otlpEndpoint(),
        config.devMode());
  }
}
