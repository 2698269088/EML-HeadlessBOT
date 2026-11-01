package top.mcocet.hlbot.plugins.events.level;

import net.minecraft.world.phys.Vec3;
import top.mcocet.hlbot.plugins.event.Event;
import top.mcocet.hlbot.plugins.event.HandlerList;

/** Fired when an explosion goes off in the client level (TNT, creeper, crystal, ...). */
public class ExplosionEvent extends Event {
   private static final HandlerList HANDLERS = new HandlerList();

   private final Vec3 center;
   private final float radius;

   public ExplosionEvent(final Vec3 center, final float radius) {
      this.center = center;
      this.radius = radius;
   }

   public Vec3 getCenter() {
      return this.center;
   }

   public float getRadius() {
      return this.radius;
   }

   @Override
   public HandlerList getHandlers() {
      return HANDLERS;
   }

   public static HandlerList getHandlerList() {
      return HANDLERS;
   }
}
