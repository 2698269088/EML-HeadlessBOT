package top.mcocet.hlbot.plugins.events.chat;

import net.minecraft.network.chat.Component;
import top.mcocet.hlbot.plugins.event.Cancellable;
import top.mcocet.hlbot.plugins.event.Event;
import top.mcocet.hlbot.plugins.event.HandlerList;

/**
 * Fired for every chat or game message the server sends to this client. Cancelling it
 * suppresses the console print, so plugins can implement their own chat filtering.
 */
public class ChatMessageEvent extends Event implements Cancellable {
   private static final HandlerList HANDLERS = new HandlerList();

   private final boolean fromServer;
   private final String sender;
   private final Component content;
   private boolean cancelled;

   public ChatMessageEvent(final boolean fromServer, final String sender, final Component content) {
      this.fromServer = fromServer;
      this.sender = sender;
      this.content = content;
   }

   /** True for server/system messages, false for player chat. */
   public boolean isFromServer() {
      return this.fromServer;
   }

   /** {@code "server"}, {@code "player"}, {@code "overlay"} or {@code "title"} — matches the console prefix. */
   public String getSender() {
      return this.sender;
   }

   /** The raw message component, styles and click events included. */
   public Component getContent() {
      return this.content;
   }

   /** Plain-text rendering of {@link #getContent()}. */
   public String getMessage() {
      return this.content.getString();
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
