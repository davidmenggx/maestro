package io.github.davidmenggx.maestro.load;

import net.fabricmc.loader.api.FabricLoader;

/**
 * The released mod, as a load run reads it. Only {@link InstalledMaestro} names the mod's classes,
 * and it is loaded only on a server that runs the mod, so the load mod also runs without it, as a
 * vanilla baseline.
 */
interface Maestro {

  String MOD_ID = "maestro";

  /** A server without the released mod: nothing to describe and nothing offloaded. */
  Maestro ABSENT =
      new Maestro() {
        @Override
        public Release release() {
          return null;
        }

        @Override
        public long run() {
          return 0;
        }

        @Override
        public Offloads stats() {
          return Offloads.NONE;
        }
      };

  /** The released mod if the server runs it, else {@link #ABSENT}. */
  static Maestro find() {
    return FabricLoader.getInstance().isModLoaded(MOD_ID) ? new InstalledMaestro() : ABSENT;
  }

  /** The mod's version and the config it runs, or null without the mod. */
  Release release();

  /** The active fleet's run, which names its telemetry, or 0 if none is active. */
  long run();

  /** What became of the requests the active fleet offloaded so far, or nothing if none is. */
  Offloads stats();

  /** The released mod's version and the settings of its config that a report names. */
  record Release(
      String version,
      String offload,
      String subMode,
      int nodes,
      int nodeCapacity,
      int areaCells,
      int spares,
      double traceSampleRate) {}
}
