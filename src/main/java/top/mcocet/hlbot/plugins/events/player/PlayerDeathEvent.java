package top.mcocet.hlbot.plugins.events.player;

import net.minecraft.network.chat.Component;
import top.mcocet.hlbot.plugins.event.Event;
import top.mcocet.hlbot.plugins.event.HandlerList;

/**
 * Fired when the local player dies. Vanilla would show the death screen here; the bot
 * fires this instead. If the server allows it, the client auto-respawns afterwards only
 * when death-screen display is disabled — otherwise respawn explicitly from a handler.
 */
public class PlayerDeathEvent extends Event {
   private static final HandlerList HANDLERS = new HandlerList();

   private final Component deathMessage;

   public PlayerDeathEvent(final Component deathMessage) {
      this.deathMessage = deathMessage;
   }

   public Component getDeathMessage() {
      return this.deathMessage;
   }

   @Override
   public HandlerList getHandlers() {
      return HANDLERS;
   }

   public static HandlerList getHandlerList() {
      return HANDLERS;
   }
}
