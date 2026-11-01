package top.mcocet.hlbot.plugins.events.chat;

import top.mcocet.hlbot.plugins.event.Cancellable;
import top.mcocet.hlbot.plugins.event.Event;
import top.mcocet.hlbot.plugins.event.HandlerList;

/**
 * Fired when the bot itself is about to send a chat message (console {@code say},
 * {@code BotApi.sendChat}, or any internal caller). Cancelling it blocks the message
 * from being sent, so plugins can filter or rewrite outgoing chat.
 */
public class SendChatEvent extends Event implements Cancellable {
   private static final HandlerList HANDLERS = new HandlerList();

   private final String message;
   private boolean cancelled;

   public SendChatEvent(final String message) {
      this.message = message;
   }

   public String getMessage() {
      return this.message;
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
