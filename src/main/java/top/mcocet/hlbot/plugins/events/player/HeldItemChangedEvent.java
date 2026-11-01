package top.mcocet.hlbot.plugins.events.player;

import top.mcocet.hlbot.plugins.event.Event;
import top.mcocet.hlbot.plugins.event.HandlerList;

/**
 * Fired when the server forces the selected hotbar slot to change. The client's own
 * {@code slot} console command does not fire this — it only reports server-initiated
 * changes.
 */
public class HeldItemChangedEvent extends Event {
   private static final HandlerList HANDLERS = new HandlerList();

   private final int slot;

   public HeldItemChangedEvent(final int slot) {
      this.slot = slot;
   }

   /** The newly selected hotbar slot, 0 – 8. */
   public int getSlot() {
      return this.slot;
   }

   @Override
   public HandlerList getHandlers() {
      return HANDLERS;
   }

   public static HandlerList getHandlerList() {
      return HANDLERS;
   }
}
