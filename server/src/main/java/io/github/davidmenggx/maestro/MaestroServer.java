package io.github.davidmenggx.maestro;

import io.github.davidmenggx.maestro.bridge.Fleet;
import io.github.davidmenggx.maestro.bridge.ServerClock;
import io.github.davidmenggx.maestro.config.MaestroConfig;
import io.github.davidmenggx.maestro.obs.Telemetry;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MaestroServer implements DedicatedServerModInitializer {

  public static final String MOD_ID = "maestro";

  private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

  @Override
  public void onInitializeServer() {
    FabricLoader loader = FabricLoader.getInstance();
    String version =
        loader.getModContainer(MOD_ID).orElseThrow().getMetadata().getVersion().getFriendlyString();
    MaestroConfig config =
        MaestroConfig.load(loader.getConfigDir().resolve(MaestroConfig.FILE_NAME));
    Telemetry.init(config, version);

    ServerClock clock = new ServerClock();
    ServerLevelEvents.LOAD.register(
        (server, level) -> {
          if (level.dimension() == Level.OVERWORLD && !config.nodes().isEmpty()) {
            Fleet.start(level, config, clock);
          }
        });
    ServerTickEvents.START_SERVER_TICK.register(
        server -> {
          Fleet fleet = Fleet.active();
          if (fleet != null) {
            fleet.tickStarted();
          }
        });
    // Handed over before the counter advances, so it counts toward the tick that just ran.
    ServerTickEvents.END_SERVER_TICK.register(
        server -> {
          Fleet fleet = Fleet.active();
          if (fleet != null) {
            fleet.tickEnding();
          }
          clock.advance();
        });
    ServerChunkEvents.CHUNK_LOAD.register(
        (level, chunk, generated) -> {
          Fleet fleet = Fleet.active();
          if (fleet != null) {
            fleet.chunkLoaded(level, chunk);
          }
        });
    ServerChunkEvents.CHUNK_UNLOAD.register(
        (level, chunk) -> {
          Fleet fleet = Fleet.active();
          if (fleet != null) {
            fleet.chunkUnloaded(level, chunk);
          }
        });
    ServerEntityEvents.ENTITY_LOAD.register(
        (entity, level) -> {
          Fleet fleet = Fleet.active();
          if (fleet != null) {
            fleet.entityLoaded(entity, level);
          }
        });
    ServerEntityEvents.ENTITY_UNLOAD.register(
        (entity, level) -> {
          Fleet fleet = Fleet.active();
          if (fleet != null) {
            fleet.entityUnloaded(entity, level);
          }
        });
    ServerLifecycleEvents.SERVER_STOPPING.register(
        server -> {
          Fleet fleet = Fleet.active();
          if (fleet != null) {
            fleet.close();
          }
        });
    ServerLifecycleEvents.SERVER_STOPPED.register(server -> Telemetry.shutdown());

    LOG.info(
        "Maestro {} initializing: subMode={} offload={} nodes={} otlp={}",
        version,
        config.subMode(),
        config.offload(),
        config.nodes().size(),
        config.otlpEndpoint());
  }
}
