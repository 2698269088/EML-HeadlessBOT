package top.mcocet.hlbot.nav;

import java.util.Locale;

/**
 * The plugin-facing trip API: walking, elytra travel and vehicle riding, in plain primitives.
 *
 * <p>Every call only queues a request; {@link Navigator} applies it on the client thread at the start of the
 * next tick. That is what makes this class safe to call from an event handler, a scheduled task, an async
 * worker or a plugin's own thread - nothing here touches the world, the input keys or a game type.
 *
 * <p>Obtained from {@code BotApi#navigation()} ({@code api.navigation().walk(100, 64, 200)}), one instance
 * shared by all plugins, because a bot can only be one place at a time: a new request replaces the trip in
 * progress rather than stacking behind it.
 */
public final class BotNavigation {
   /** Walks to a cell, breaking through what is in the way. */
   public void walk(final int x, final int y, final int z) {
      this.walk(x, y, z, true);
   }

   /** Walks to a cell; with {@code dig} false the route goes around blocks instead of through them. */
   public void walk(final int x, final int y, final int z, final boolean dig) {
      Navigator.INSTANCE.requestWalk(x, y, z, dig);
   }

   /** Flies to a point with the elytra (needs one in the chest slot; fireworks make it sustainable). */
   public void fly(final int x, final int y, final int z) {
      Navigator.INSTANCE.requestFly(x, y, z);
   }

   /** Rides the nearest boat, minecart or horse to a point, then dismounts. */
   public void ride(final int x, final int y, final int z) {
      Navigator.INSTANCE.requestRide(x, y, z);
   }

   /** Ends the trip and releases the keys on the next tick. */
   public void stop() {
      Navigator.INSTANCE.requestStop();
   }

   /** True while a trip is in progress. */
   public boolean navigating() {
      return Navigator.INSTANCE.navigating();
   }

   /** {@code walk}, {@code fly}, {@code ride}, {@code waiting} or {@code idle}. */
   public String mode() {
      return Navigator.INSTANCE.mode().name().toLowerCase(Locale.ROOT);
   }

   /** Language-neutral one-liner: what the trip is doing, where it is going, and how far along it is. */
   public String status() {
      return Navigator.INSTANCE.statusText();
   }

   public int targetX() {
      return Navigator.INSTANCE.targetX();
   }

   public int targetY() {
      return Navigator.INSTANCE.targetY();
   }

   public int targetZ() {
      return Navigator.INSTANCE.targetZ();
   }

   /** Waypoints left on a walk; zero for the travel modes, which have no grid route. */
   public int remainingSteps() {
      return Navigator.INSTANCE.remainingSteps();
   }
}
