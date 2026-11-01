package top.mcocet.hlbot.plugins.events.player;

import net.minecraft.client.multiplayer.PlayerInfo;
import top.mcocet.hlbot.plugins.event.Event;
import top.mcocet.hlbot.plugins.event.HandlerList;

/**
 * Fired when a player is removed from the tab list (left the server). The info object
 * is still fully populated when this fires; it has already been removed from the map.
 */
public class PlayerLeftEvent extends Event {
   private static final HandlerList HANDLERS = new HandlerList();

   private final PlayerInfo playerInfo;

   public PlayerLeftEvent(final PlayerInfo playerInfo) {
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
