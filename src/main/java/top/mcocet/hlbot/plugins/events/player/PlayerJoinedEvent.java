package top.mcocet.hlbot.plugins.events.player;

import net.minecraft.client.multiplayer.PlayerInfo;
import top.mcocet.hlbot.plugins.event.Event;
import top.mcocet.hlbot.plugins.event.HandlerList;

/**
 * Fired when a player is added to the tab list (joins the server). The matching leave
 * notification is {@link PlayerLeftEvent}.
 */
public class PlayerJoinedEvent extends Event {
   private static final HandlerList HANDLERS = new HandlerList();

   private final PlayerInfo playerInfo;

   public PlayerJoinedEvent(final PlayerInfo playerInfo) {
      this.playerInfo = playerInfo;
   }

   public PlayerInfo getPlayerInfo() {
      return this.playerInfo;
   }

   public String getPlayerName() {
      return this.playerInfo.getProfile().name();
   }

   @Override
   public HandlerList getHandlers() {
      return HANDLERS;
   }

   public static HandlerList getHandlerList() {
      return HANDLERS;
   }
}
