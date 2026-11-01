package top.mcocet.hlbot.plugins.events.connection;

import top.mcocet.hlbot.plugins.event.Event;
import top.mcocet.hlbot.plugins.event.HandlerList;

/**
 * Fired once the login handshake finished and the client entered the game: the player
 * entity exists and the first chunks are on their way. Fires on the client thread.
 */
public class LoginSuccessEvent extends Event {
   private static final HandlerList HANDLERS = new HandlerList();

   private final String host;
   private final int port;
   private final String username;

   public LoginSuccessEvent(final String host, final int port, final String username) {
      this.host = host;
      this.port = port;
      this.username = username;
   }

   public String getHost() {
      return this.host;
   }

   public int getPort() {
      return this.port;
   }

   public String getUsername() {
      return this.username;
   }

   @Override
   public HandlerList getHandlers() {
      return HANDLERS;
   }

   public static HandlerList getHandlerList() {
      return HANDLERS;
   }
}
