package top.mcocet.hlbot.plugins.events.player;

import top.mcocet.hlbot.plugins.event.Event;
import top.mcocet.hlbot.plugins.event.HandlerList;

/**
 * Fired when the server updates the local player's health, food level and saturation
 * (same packet covers damage, healing and hunger). Useful for auto-eat or retreat logic.
 */
public class HealthChangedEvent extends Event {
   private static final HandlerList HANDLERS = new HandlerList();

   private final float health;
   private final int foodLevel;
   private final float saturation;

   public HealthChangedEvent(final float health, final int foodLevel, final float saturation) {
      this.health = health;
      this.foodLevel = foodLevel;
      this.saturation = saturation;
   }

   public float getHealth() {
      return this.health;
   }

   public int getFoodLevel() {
      return this.foodLevel;
   }

   public float getSaturation() {
      return this.saturation;
   }

   @Override
   public HandlerList getHandlers() {
      return HANDLERS;
   }

   public static HandlerList getHandlerList() {
      return HANDLERS;
   }
}
