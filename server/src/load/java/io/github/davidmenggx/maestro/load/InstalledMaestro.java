package io.github.davidmenggx.maestro.load;

import io.github.davidmenggx.maestro.MaestroServer;
import io.github.davidmenggx.maestro.bridge.Fleet;
import io.github.davidmenggx.maestro.bridge.OffloadStats;
import io.github.davidmenggx.maestro.config.MaestroConfig;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;

/** The released mod on a server that runs it. */
final class InstalledMaestro implements Maestro {

  @Override
  public Release release() {
    // The config the running mod uses, which the file may no longer match.
    MaestroConfig config = MaestroServer.config();
    String version =
        FabricLoader.getInstance()
            .getModContainer(MOD_ID)
            .orElseThrow()
            .getMetadata()
            .getVersion()
            .getFriendlyString();
    return new Release(
        version,
        config.offload().name().toLowerCase(Locale.ROOT),
        config.subMode().name().toLowerCase(Locale.ROOT),
        config.nodes().size(),
        config.nodeCapacity(),
        config.areaCells(),
        config.spares(),
        config.traceSampleRate());
  }

  @Override
  public long run() {
    Fleet fleet = Fleet.active();
    return fleet == null ? 0 : fleet.run();
  }

  @Override
  public Offloads stats() {
    Fleet fleet = Fleet.active();
    if (fleet == null) {
      return Offloads.NONE;
    }
    OffloadStats stats = fleet.stats();
    return new Offloads(stats.counts(), stats.ticksLate(), fleet.measuredRates());
  }
}
