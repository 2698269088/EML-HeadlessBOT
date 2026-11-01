package top.mcocet.hlbot.plugins.events.entity;

import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.LivingEntity;
import top.mcocet.hlbot.plugins.event.Event;
import top.mcocet.hlbot.plugins.event.HandlerList;

/** Fired when a mob effect is removed from a living entity before it would expire. */
public class MobEffectRemovedEvent extends Event {
   private static final HandlerList HANDLERS = new HandlerList();

   private final LivingEntity entity;
   private final Holder<MobEffect> effect;

   public MobEffectRemovedEvent(final LivingEntity entity, final Holder<MobEffect> effect) {
      this.entity = entity;
      this.effect = effect;
   }

   public LivingEntity getEntity() {
      return this.entity;
   }

   public Holder<MobEffect> getEffect() {
      return this.effect;
   }

   @Override
   public HandlerList getHandlers() {
      return HANDLERS;
   }

   public static HandlerList getHandlerList() {
      return HANDLERS;
   }
}
