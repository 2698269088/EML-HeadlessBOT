package top.mcocet.hlbot.plugins.events.player;

import top.mcocet.hlbot.plugins.event.Event;
import top.mcocet.hlbot.plugins.event.HandlerList;

/** Fired when the server updates the local player's experience bar. */
public class ExperienceChangedEvent extends Event {
   private static final HandlerList HANDLERS = new HandlerList();

   private final float progress;
   private final int total;
   private final int level;

   public ExperienceChangedEvent(final float progress, final int total, final int level) {
      this.progress = progress;
      this.total = total;
      this.level = level;
   }

   /** Progress towards the next level, 0.0 – 1.0. */
   public float getProgress() {
      return this.progress;
   }

   public int getTotal() {
      return this.total;
   }

   public int getLevel() {
      return this.level;
   }

   @Override
   public HandlerList getHandlers() {
      return HANDLERS;
   }

   public static HandlerList getHandlerList() {
      return HANDLERS;
   }
}
