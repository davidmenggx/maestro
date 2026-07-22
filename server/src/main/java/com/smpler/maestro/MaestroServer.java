package com.smpler.maestro;

import net.fabricmc.api.DedicatedServerModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MaestroServer implements DedicatedServerModInitializer {

  public static final String MOD_ID = "maestro";
  public static final String VERSION = "0.1.0";

  private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

  @Override
  public void onInitializeServer() {
    LOG.info("Maestro {} initialising: offloading mob pathfinding to the compute fleet", VERSION);
  }
}
