package top.mcocet.hlbot.plugins.events.server;

import top.mcocet.hlbot.plugins.event.Event;
import top.mcocet.hlbot.plugins.event.HandlerList;

/**
 * Fired when the server signals the credits (end dragon defeated). Vanilla would roll
 * the credits here; the bot just reports it.
 */
public class WinGameEvent extends Event {
   private static final HandlerList HANDLERS = new HandlerList();

   @Override
   public HandlerList getHandlers() {
      return HANDLERS;
   }

   public static HandlerList getHandlerList() {
      return HANDLERS;
   }
}
