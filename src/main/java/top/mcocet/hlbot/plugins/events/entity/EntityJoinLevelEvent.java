package top.mcocet.hlbot.plugins.events.entity;

import net.minecraft.world.entity.Entity;
import top.mcocet.hlbot.plugins.event.Event;
import top.mcocet.hlbot.plugins.event.HandlerList;

/**
 * Fired when an entity is added to the client level (spawn packet processed). Useful for
 * mob radars, player trackers and auto-attack targeting.
 */
public class EntityJoinLevelEvent extends Event {
   private static final HandlerList HANDLERS = new HandlerList();

   private final Entity entity;

   public EntityJoinLevelEvent(final Entity entity) {
      this.entity = entity;
   }

   public Entity getEntity() {
      return this.entity;
   }

   @Override
   public HandlerList getHandlers() {
      return HANDLERS;
   }

   public static HandlerList getHandlerList() {
      return HANDLERS;
   }
}
