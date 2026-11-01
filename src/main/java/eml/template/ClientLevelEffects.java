package eml.template;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * Donors for {@code ClientLevel} effects whose arguments cannot be salvaged statement by statement.
 *
 * <p>{@code ClientLevel.tick} and {@code playSound} reach the camera as an ARGUMENT of a sound call whose
 * receiver ({@code soundManager}) is also null, so those are handled by removing the whole statement from
 * the outermost null value (see {@code EmlPatches}). Particles are different: {@code doAddParticle} stores
 * the camera in a local that the crash-report lambda reads later, so no instruction range is removable
 * without leaving that local uninitialised - the method's only remaining purpose is handing work to the
 * particle engine, which no headless client has.
 */
public final class ClientLevelEffects {
   private ClientLevelEffects() {
   }

   /**
    * {@code ClientLevel.doAddParticle(ParticleOptions, boolean, boolean, double...)}: everything after the
    * particle-engine lookup is rendering. Keeping the method instead of deleting its call sites also avoids
    * the vanilla crash report it builds around the camera position.
    */
   public static void noopAddParticle(
      final ClientLevel self,
      final ParticleOptions options,
      final boolean force,
      final boolean removeExisting,
      final double x,
      final double y,
      final double z,
      final double xSpeed,
      final double ySpeed,
      final double zSpeed
   ) {
      // intentionally empty: no particle engine exists to receive this
   }

   /**
    * {@code ClientLevel.playSound(double, double, double, SoundEvent, SoundSource, float, float, boolean, long)}:
    * looks up the camera to position the sound and hands it to the sound manager. Neutralised here as well,
    * because the sound-manager statement removal covers the call site but the method is also invoked from
    * entity and block-event paths that pass their own camera-less arguments.
    */
   public static void noopPlaySound(
      final ClientLevel self,
      final double x,
      final double y,
      final double z,
      final SoundEvent event,
      final SoundSource source,
      final float volume,
      final float pitch,
      final boolean distanceDelay,
      final long seed
   ) {
      // intentionally empty: no audio device, and the server never depends on client playback
   }

   /**
    * The five public sound entry points of {@code ClientLevel}. They are neutralised as whole methods rather
    * than statement by statement: their only remaining work is handing a {@code SoundInstance} to
    * {@code Minecraft.getSoundManager()}, and a statement-level rule here kept silently matching nothing
    * (the actual reads live in overloads whose shapes depend on inlining), which is exactly the failure mode
    * you do not want in a hot packet path.
    */
   public static void noopSeededSound(
      final ClientLevel self,
      final net.minecraft.world.entity.Entity except,
      final double x,
      final double y,
      final double z,
      final net.minecraft.core.Holder<SoundEvent> sound,
      final SoundSource source,
      final float volume,
      final float pitch,
      final long seed
   ) {
      // intentionally empty
   }

   public static void noopSeededSoundAtEntity(
      final ClientLevel self,
      final net.minecraft.world.entity.Entity sourceEntity,
      final net.minecraft.world.entity.Entity except,
      final net.minecraft.core.Holder<SoundEvent> sound,
      final SoundSource source,
      final float volume,
      final float pitch,
      final long seed
   ) {
      // intentionally empty
   }

   public static void noopLocalSoundAtEntity(
      final ClientLevel self,
      final net.minecraft.world.entity.Entity sourceEntity,
      final SoundEvent sound,
      final SoundSource source,
      final float volume,
      final float pitch
   ) {
      // intentionally empty
   }

   public static void noopLocalSoundPositioned(
      final ClientLevel self,
      final double x,
      final double y,
      final double z,
      final SoundEvent sound,
      final SoundSource source,
      final float volume,
      final float pitch,
      final boolean distanceDelay
   ) {
      // intentionally empty
   }

   public static void noopPlayerSound(
      final ClientLevel self,
      final SoundEvent sound,
      final SoundSource source,
      final float volume,
      final float pitch
   ) {
      // intentionally empty
   }

   /**
    * {@code ClientLevel.onBlockEntityAdded(BlockEntity)}: asks the block-entity dispatcher for a renderer and
    * files the entity under {@code globallyRenderedBlockEntities} if that renderer draws off-screen. The
    * whole method is renderer bookkeeping - the set is only ever read by the level renderer - so there is
    * nothing to preserve, and it runs for every chest/furnace/door whose chunk arrives after joining.
    */
   public static void noopOnBlockEntityAdded(final ClientLevel self, final net.minecraft.world.level.block.entity.BlockEntity blockEntity) {
      // intentionally empty: no renderer to register with
   }
}
