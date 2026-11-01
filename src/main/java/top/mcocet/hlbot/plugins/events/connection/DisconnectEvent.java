package top.mcocet.hlbot.plugins.events.connection;

import net.minecraft.network.chat.Component;
import top.mcocet.hlbot.plugins.event.Event;
import top.mcocet.hlbot.plugins.event.HandlerList;

/**
 * Fired when the connection was closed or lost, for any reason (kick, network error, or a
 * local {@code disconnect}). The level reference is cleared right after this event.
 */
public class DisconnectEvent extends Event {
   private static final HandlerList HANDLERS = new HandlerList();

   private final Component reason;

   public DisconnectEvent(final Component reason) {
      this.reason = reason;
   }

   public Component getReason() {
      return this.reason;
   }

   public String getReasonMessage() {
      return this.reason.getString();
   }

   @Override
   public HandlerList getHandlers() {
      return HANDLERS;
   }

   public static HandlerList getHandlerList() {
      return HANDLERS;
   }
}
