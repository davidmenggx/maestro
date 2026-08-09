package com.smpler.maestro;

import com.smpler.maestro.bridge.PoolOffload;
import com.smpler.maestro.bridge.ServerClock;
import com.smpler.maestro.config.MaestroConfig;
import com.smpler.maestro.obs.Telemetry;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MaestroServer implements DedicatedServerModInitializer {

  public static final String MOD_ID = "maestro";
  public static final String VERSION = "0.1.0";

  /** Serialization is the only offloaded work so far, so one worker is enough. */
  private static final int OFFLOAD_THREADS = 1;

  private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

  private static MaestroConfig config;
  private static ServerClock clock;
  private static PoolOffload offload;

  public static MaestroConfig config() {
    if (config == null) {
      throw new IllegalStateException("Maestro config read before initialization");
    }
    return config;
  }

  /** Production half of the controller's time seam. */
  public static ServerClock clock() {
    return clock;
  }

  /** Production half of the controller's off-main-thread seam. */
  public static PoolOffload offload() {
    return offload;
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
    // before the counter moves, so a completion is attributed to the tick that
    // just ran rather than the one about to start.
    ServerTickEvents.END_SERVER_TICK.register(
        server -> {
          offload.drain();
          clock.advance();
        });
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
