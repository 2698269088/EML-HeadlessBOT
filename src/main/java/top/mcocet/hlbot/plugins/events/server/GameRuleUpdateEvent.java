package top.mcocet.hlbot.plugins.events.server;

import top.mcocet.hlbot.plugins.event.Event;
import top.mcocet.hlbot.plugins.event.HandlerList;

/**
 * Fired when the server publishes its gamerule values (once during the configuration
 * phase and on change afterwards). The payload is the raw values object from the
 * decompiled packet — inspect it reflectively or cast as needed.
 */
public class GameRuleUpdateEvent extends Event {
   private static final HandlerList HANDLERS = new HandlerList();

   private final Object values;

   public GameRuleUpdateEvent(final Object values) {
      this.values = values;
   }

   public Object getValues() {
      return this.values;
   }

   @Override
   public HandlerList getHandlers() {
      return HANDLERS;
   }

   public static HandlerList getHandlerList() {
      return HANDLERS;
   }
}
