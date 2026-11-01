package top.mcocet.hlbot.plugins.events.connection;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import top.mcocet.hlbot.plugins.event.Event;
import top.mcocet.hlbot.plugins.event.HandlerList;

/**
 * Fired when the server respawns the player: after death, when crossing dimensions, or
 * when the server forces a world reload. The new player entity has already been created
 * when this fires, so {@code client.player} is valid inside the handler.
 */
public class RespawnEvent extends Event {
   private static final HandlerList HANDLERS = new HandlerList();

   private final ResourceKey<Level> dimension;
   private final boolean keepAllData;

   public RespawnEvent(final ResourceKey<Level> dimension, final boolean keepAllData) {
      this.dimension = dimension;
      this.keepAllData = keepAllData;
   }

   /** The dimension the player is respawning into. */
   public ResourceKey<Level> getDimension() {
      return this.dimension;
   }

   /** True when the server kept entity data and attributes across the respawn. */
   public boolean isKeepAllData() {
      return this.keepAllData;
   }

   @Override
   public HandlerList getHandlers() {
      return HANDLERS;
   }

   public static HandlerList getHandlerList() {
      return HANDLERS;
   }
}
