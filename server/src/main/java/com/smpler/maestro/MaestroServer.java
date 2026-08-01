package com.smpler.maestro;

import com.smpler.maestro.config.MaestroConfig;
import com.smpler.maestro.obs.Telemetry;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MaestroServer implements DedicatedServerModInitializer {

  public static final String MOD_ID = "maestro";
  public static final String VERSION = "0.1.0";

  private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

  private static MaestroConfig config;

  public static MaestroConfig config() {
    if (config == null) {
      throw new IllegalStateException("Maestro config read before initialization");
    }
    return config;
  }

  @Override
  public void onInitializeServer() {
    config =
        MaestroConfig.load(
            FabricLoader.getInstance().getConfigDir().resolve(MaestroConfig.FILE_NAME));
    Telemetry.init(config, VERSION);
    ServerLifecycleEvents.SERVER_STOPPED.register(server -> Telemetry.shutdown());

    LOG.info(
        "Maestro {} initializing: mode={} subMode={} otlp={} dev={}",
        VERSION,
        config.mode(),
        config.subMode(),
        config.otlpEndpoint(),
        config.devMode());
  }
}
