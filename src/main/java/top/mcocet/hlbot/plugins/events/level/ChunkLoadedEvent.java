package top.mcocet.hlbot.plugins.events.level;

import top.mcocet.hlbot.plugins.event.Event;
import top.mcocet.hlbot.plugins.event.HandlerList;

/**
 * Fired when a chunk's block data has been received and stored in the client level.
 * Lighting may still be queued; block reads through {@code level.getBlockState} already
 * work inside the handler.
 */
public class ChunkLoadedEvent extends Event {
   private static final HandlerList HANDLERS = new HandlerList();

   private final int x;
   private final int z;

   public ChunkLoadedEvent(final int x, final int z) {
      this.x = x;
      this.z = z;
   }

   /** Chunk X coordinate (block X >> 4). */
   public int getX() {
      return this.x;
   }

   /** Chunk Z coordinate (block Z >> 4). */
   public int getZ() {
      return this.z;
   }

   @Override
   public HandlerList getHandlers() {
      return HANDLERS;
   }

   public static HandlerList getHandlerList() {
      return HANDLERS;
   }
}
