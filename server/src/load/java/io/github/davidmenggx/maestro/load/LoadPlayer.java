package io.github.davidmenggx.maestro.load;

import com.mojang.authlib.GameProfile;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;

/**
 * A player with no client, which nothing can hurt. Added to its level, it counts among the level's
 * players and loads the chunks around it as a player would, but not in the server's player list. A
 * hunted one is in survival mode, so hostile mobs hunt it; mobs do not target the others, in
 * creative mode.
 *
 * <p>{@link FakePlayer#get} keeps one instance per profile, and a removed entity cannot be added
 * again, so each run makes its own.
 */
final class LoadPlayer extends FakePlayer {

  private LoadPlayer(ServerLevel level, GameProfile profile) {
    super(level, profile);
  }

  /** Player {@code index} of a run, standing at the bottom centre of {@code x, y, z}. */
  static LoadPlayer at(ServerLevel level, int index, int x, int y, int z, boolean hunted) {
    String name = "maestro_load_" + index;
    UUID id = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    LoadPlayer player = new LoadPlayer(level, new GameProfile(id, name));
    player.snapTo(x + 0.5, y, z + 0.5, 0, 0);
    player.gameMode.changeGameModeForPlayer(hunted ? GameType.SURVIVAL : GameType.CREATIVE);
    return player;
  }

  /** Moves to {@code x, y, z}, and the chunks it loads move with it. */
  void walkTo(double x, double y, double z) {
    snapTo(x, y, z, getYRot(), getXRot());
    // As a client's move packet does, which costs a pass over every tracked entity.
    level().getChunkSource().move(this);
  }
}
