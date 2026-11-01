package top.mcocet.hlbot.plugins.events.tick;

import top.mcocet.hlbot.plugins.event.Event;
import top.mcocet.hlbot.plugins.event.HandlerList;

/**
 * Fired once per client tick (20 times per second) at the end of the connection's tick,
 * while the bot is in game. Runs on the client thread — the canonical place for
 * periodic bot logic (scanners, timers, keep-alive tasks).
 *
 * <p>Keep handlers cheap; anything heavier than a few milliseconds will slow down the
 * whole game loop. Offload real work to your own thread and hop back with
 * {@code BotApi.runOnClient} when you need to touch the world again.
 */
public class ClientTickEvent extends Event {
   private static final HandlerList HANDLERS = new HandlerList();

   public static final ClientTickEvent INSTANCE = new ClientTickEvent();

   private ClientTickEvent() {
   }

   @Override
   public HandlerList getHandlers() {
      return HANDLERS;
   }

   public static HandlerList getHandlerList() {
      return HANDLERS;
   }
}
