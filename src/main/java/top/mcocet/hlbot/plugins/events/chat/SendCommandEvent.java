package top.mcocet.hlbot.plugins.events.chat;

import top.mcocet.hlbot.plugins.event.Cancellable;
import top.mcocet.hlbot.plugins.event.Event;
import top.mcocet.hlbot.plugins.event.HandlerList;

/**
 * Fired when the bot is about to send a server command (console {@code cmd},
 * {@code BotApi.sendCommand}). Cancelling it blocks the command from being sent.
 */
public class SendCommandEvent extends Event implements Cancellable {
   private static final HandlerList HANDLERS = new HandlerList();

   private final String command;
   private boolean cancelled;

   public SendCommandEvent(final String command) {
      this.command = command;
   }

   /** The command line without the leading slash. */
   public String getCommand() {
      return this.command;
   }

   @Override
   public boolean isCancelled() {
      return this.cancelled;
   }

   @Override
   public void setCancelled(final boolean cancelled) {
      this.cancelled = cancelled;
   }

   @Override
   public HandlerList getHandlers() {
      return HANDLERS;
   }

   public static HandlerList getHandlerList() {
      return HANDLERS;
   }
}
