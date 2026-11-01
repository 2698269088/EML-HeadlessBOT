package top.mcocet.hlbot.plugins.events.entity;

import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import top.mcocet.hlbot.plugins.event.Event;
import top.mcocet.hlbot.plugins.event.HandlerList;

/**
 * Fired when a mob effect is applied to a living entity (potion, beacon, conduit, ...).
 * Also fires when an existing effect is re-applied with a new duration or amplifier —
 * check {@link #isUpgrade()} to tell the two apart.
 */
public class MobEffectAddedEvent extends Event {
   private static final HandlerList HANDLERS = new HandlerList();

   private final LivingEntity entity;
   private final MobEffectInstance effectInstance;
   private final boolean upgrade;

   public MobEffectAddedEvent(final LivingEntity entity, final MobEffectInstance effectInstance, final boolean upgrade) {
      this.entity = entity;
      this.effectInstance = effectInstance;
      this.upgrade = upgrade;
   }

   public LivingEntity getEntity() {
      return this.entity;
   }

   public MobEffectInstance getEffectInstance() {
      return this.effectInstance;
   }

   /** True when the entity already had this effect and it is being refreshed/upgraded. */
   public boolean isUpgrade() {
      return this.upgrade;
   }

   @Override
   public HandlerList getHandlers() {
      return HANDLERS;
   }

   public static HandlerList getHandlerList() {
      return HANDLERS;
   }
}
