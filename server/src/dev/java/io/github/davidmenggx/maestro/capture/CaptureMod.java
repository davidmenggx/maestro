package io.github.davidmenggx.maestro.capture;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

/** Entrypoint of the development mod, which is only on the classpath of a dev server. */
public final class CaptureMod implements DedicatedServerModInitializer {

  @Override
  public void onInitializeServer() {
    CommandRegistrationCallback.EVENT.register(
        (dispatcher, buildContext, selection) -> CaptureCommand.register(dispatcher));
  }
}
