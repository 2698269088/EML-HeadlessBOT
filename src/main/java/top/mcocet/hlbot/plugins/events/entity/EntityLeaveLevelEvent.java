package top.mcocet.hlbot.plugins.events.entity;

import net.minecraft.world.entity.Entity;
import top.mcocet.hlbot.plugins.event.Event;
import top.mcocet.hlbot.plugins.event.HandlerList;

/**
 * Fired right before an entity is removed from the client level (despawn, death or chunk
 * unload). The entity reference is still valid and fully populated when this fires.
 */
public class EntityLeaveLevelEvent extends Event {
   private static final HandlerList HANDLERS = new HandlerList();

   private final Entity entity;

   public EntityLeaveLevelEvent(final Entity entity) {
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
