package eml.template;

import net.minecraft.util.Util;

/**
 * Headless readiness rule for the client-side level load.
 *
 * <p>{@code LevelLoadTracker.WaitingForPlayerChunk.isReady()} is the last gate before the client tells the
 * server it has loaded. Vanilla waits until the renderer has compiled and can see the section around the
 * player - a question that has no answer without a renderer, and the tracker stores the one we never built.
 *
 * <p>Answering "ready" is not a shortcut: the same method already returns true for every branch that is not
 * the renderer query (player outside build height, spectator, dead) and for its own timeout, whose log line
 * literally says "letting the player into the world anyway". There is simply nothing left for a headless
 * client to wait for, and refusing would hang the join forever.
 */
public final class LevelLoadReadiness {
   private LevelLoadReadiness() {
   }

   /** {@code LevelLoadTracker.WaitingForPlayerChunk.isReady()}. */
   public static boolean isReady(final net.minecraft.client.multiplayer.LevelLoadTracker.WaitingForPlayerChunk self) {
      if (Util.getMillis() > self.timeoutAfter()) {
         // Kept so a stuck load is still visible in the log; vanilla reports the same condition.
         eml.hooks.BotRuntime.log().warn("Timed out while waiting for the client to load chunks, letting the player into the world anyway");
      }

      return true;
   }
}
