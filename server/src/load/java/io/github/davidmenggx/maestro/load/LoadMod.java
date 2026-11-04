package io.github.davidmenggx.maestro.load;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.Identifier;

/**
 * Entrypoint of the load mod, for experiments only. It runs beside the released mod, or without it
 * as a vanilla baseline.
 */
public final class LoadMod implements DedicatedServerModInitializer {

  /** Runs before every other mod's listeners. */
  private static final Identifier FIRST = Identifier.fromNamespaceAndPath("maestro-load", "first");

  /** Runs after every other mod's listeners. */
  private static final Identifier LAST = Identifier.fromNamespaceAndPath("maestro-load", "last");

  @Override
  public void onInitializeServer() {
    CommandRegistrationCallback.EVENT.register(
        (dispatcher, buildContext, selection) -> LoadCommand.register(dispatcher));
    // A measured tick spans every other mod's tick work, the fleet's included.
    ServerTickEvents.START_SERVER_TICK.addPhaseOrdering(FIRST, Event.DEFAULT_PHASE);
    ServerTickEvents.START_SERVER_TICK.register(FIRST, server -> LoadCommand.tickStarted());
    // Before the fleet's hand-over, so what the run changes is handed over in the same tick.
    ServerTickEvents.END_SERVER_TICK.addPhaseOrdering(FIRST, Event.DEFAULT_PHASE);
    ServerTickEvents.END_SERVER_TICK.register(FIRST, server -> LoadCommand.tickEnding());
    ServerTickEvents.END_SERVER_TICK.addPhaseOrdering(Event.DEFAULT_PHASE, LAST);
    ServerTickEvents.END_SERVER_TICK.register(LAST, server -> LoadCommand.tickEnded());
    // Before the fleet closes, so the report can read it.
    ServerLifecycleEvents.SERVER_STOPPING.addPhaseOrdering(FIRST, Event.DEFAULT_PHASE);
    ServerLifecycleEvents.SERVER_STOPPING.register(FIRST, server -> LoadCommand.serverStopping());
  }
}
