package top.mcocet.hlbot.plugins.events.server;

import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import top.mcocet.hlbot.plugins.event.Event;
import top.mcocet.hlbot.plugins.event.HandlerList;

/**
 * Fired for every boss-bar packet. Boss bars are not rendered headless, but many
 * servers encode state in them (raid waves, booster timers, event progress), so the raw
 * packet is handed to plugins for parsing.
 */
public class BossBarEvent extends Event {
   private static final HandlerList HANDLERS = new HandlerList();

   private final ClientboundBossEventPacket packet;

   public BossBarEvent(final ClientboundBossEventPacket packet) {
      this.packet = packet;
   }

   public ClientboundBossEventPacket getPacket() {
      return this.packet;
   }

   @Override
   public HandlerList getHandlers() {
      return HANDLERS;
   }

   public static HandlerList getHandlerList() {
      return HANDLERS;
   }
}
