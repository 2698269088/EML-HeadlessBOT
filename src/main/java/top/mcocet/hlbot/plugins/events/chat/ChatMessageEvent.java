package top.mcocet.hlbot.plugins.events.chat;

import net.minecraft.network.chat.Component;
import top.mcocet.hlbot.plugins.event.Cancellable;
import top.mcocet.hlbot.plugins.event.Event;
import top.mcocet.hlbot.plugins.event.HandlerList;

/**
 * Fired for every chat or game message the server sends to this client. Cancelling it
 * suppresses the console print, so plugins can implement their own chat filtering.
 *
 * <p>{@link #getKind()} and {@link #getSenderName()} are what makes the public chat and the
 * {@code /msg} inbox distinguishable: a private message arrives through vanilla's disguised-chat path,
 * where the sender's display name lives in the chat type binding rather than in the console prefix.
 */
public class ChatMessageEvent extends Event implements Cancellable {
   private static final HandlerList HANDLERS = new HandlerList();

   /** Which vanilla path the line came in on. */
   public enum Kind {
      /** Player chat and disguised player chat ({@code <Name> text}), or a plugin-formatted line. */
      PUBLIC,
      /** A {@code /msg} / {@code /tell} sent to this bot; {@link #getSenderName()} is the sender. */
      PRIVATE,
      /** System chat, commands' feedback, join/leave and death messages. */
      SYSTEM,
      /** The action bar line. */
      OVERLAY,
      /** The title / subtitle line. */
      TITLE
   }

   private final boolean fromServer;
   private final String sender;
   private final Component content;
   private final Kind kind;
   private final String senderName;
   private boolean cancelled;

   public ChatMessageEvent(final boolean fromServer, final String sender, final Component content) {
      this(fromServer, sender, content, fromServer ? Kind.SYSTEM : Kind.PUBLIC, null);
   }

   public ChatMessageEvent(final boolean fromServer, final String sender, final Component content, final Kind kind, final String senderName) {
      this.fromServer = fromServer;
      this.sender = sender;
      this.content = content;
      this.kind = kind;
      this.senderName = senderName;
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

   /** The vanilla path this line arrived on - see {@link Kind}. */
   public Kind getKind() {
      return this.kind;
   }

   /** Shorthand for {@code getKind() == Kind.PRIVATE}. */
   public boolean isPrivate() {
      return this.kind == Kind.PRIVATE;
   }

   /**
    * The player who authored the line: the profile name for signed player chat, the display name from the
    * chat type binding for disguised and private chat. Falls back to {@link #getSender()} (the console
    * prefix) when vanilla did not carry a name, so this is never {@code null}.
    */
   public String getSenderName() {
      return this.senderName == null || this.senderName.isBlank() ? this.sender : this.senderName;
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
