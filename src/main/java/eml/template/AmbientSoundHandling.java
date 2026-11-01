package eml.template;

import net.minecraft.client.resources.sounds.BiomeAmbientSoundsHandler;
import net.minecraft.client.resources.sounds.UnderwaterAmbientSoundHandler;

/**
 * Donor bodies for the ambient-sound handlers that {@code LocalPlayer.tick()} ticks every tick.
 *
 * <p>Both handlers keep their own {@code SoundManager} reference, captured when {@code LocalPlayer} was
 * constructed - which is null headless, because no sound engine is ever created. Their {@code tick()}
 * methods play sounds, so the first underwater tick (or the first biome ambience) throws
 * {@code NullPointerException: ... this.soundManager is null} inside the entity tick and takes the whole
 * client down. Dropping the calls at the call site is not possible: the sound manager is a field of the
 * handler, not a {@code Minecraft} accessor.
 *
 * <p>Nothing else lives in these bodies - they only start/stop sound instances (the biome handler also
 * accumulates the "mood" value that drives cave sounds), so an empty body keeps the client correct.
 */
public final class AmbientSoundHandling {
   private AmbientSoundHandling() {
   }

   /** Body of {@code UnderwaterAmbientSoundHandler.tick()}. */
   public static void noopUnderwater(final UnderwaterAmbientSoundHandler self) {
      // Sound only: schedules a random underwater ambience every few seconds.
   }

   /** Body of {@code BiomeAmbientSoundsHandler.tick()}. */
   public static void noopBiome(final BiomeAmbientSoundsHandler self) {
      // Sound only: starts/stops biome loop sounds and accumulates moodiness. Mood stays at 0, which is
      // exactly what a client without audio should report.
   }
}
