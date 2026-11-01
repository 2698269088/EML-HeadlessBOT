package top.mcocet.hlbot.plugins.events.level;

import top.mcocet.hlbot.plugins.event.Event;
import top.mcocet.hlbot.plugins.event.HandlerList;

/** Fired when the server tells the client to drop a chunk (out of view distance). */
public class ChunkUnloadedEvent extends Event {
   private static final HandlerList HANDLERS = new HandlerList();

   private final int x;
   private final int z;

   public ChunkUnloadedEvent(final int x, final int z) {
      this.x = x;
      this.z = z;
   }

   public int getX() {
      return this.x;
   }

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
