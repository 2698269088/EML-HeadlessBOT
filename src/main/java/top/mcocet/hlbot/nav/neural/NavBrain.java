package top.mcocet.hlbot.nav.neural;

import java.io.File;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * How the next leg of a trip should be travelled, chosen by a tiny bundled network instead of a fixed stack
 * of ifs. One decision covers both the means of travel and the planning style: a normal A* walk, a wider
 * search for long open ground, a dig-friendly plan, swimming through a wet corridor, taking the elytra,
 * riding a nearby vehicle, a straight tunnel when planning keeps failing, or a clean stop.
 *
 * <p>The features are observations the navigator can take directly: distance to the real target, whether it
 * is outside the loaded chunks, the terrain around the body, the road ahead (a corridor sampled toward the
 * target), what the bot carries (elytra) and what stands nearby (a vehicle). Training is behavioural cloning
 * of the hand-written expert in {@link NavModelTrainer}: the network generalises a policy we can state
 * precisely, it does not learn from reward.
 */
public final class NavBrain {
   public enum Decision {
      NORMAL,
      WIDE,
      DIG,
      SWIM,
      FLY,
      RIDE,
      TUNNEL,
      STOP
   }

   /**
    * @param horizontalDistance horizontal blocks to the REAL target (not this leg's cut-off goal)
    * @param verticalDistance   absolute height difference to the target
    * @param targetUnloaded     the target column is outside the loaded chunks
    * @param replans            re-plans so far in this trip
    * @param openness           fraction of standable cells around the body (0..1)
    * @param hazardRatio        fraction of hazardous cells around the body (0..1)
    * @param waterRatio         fraction of water cells around the body (0..1)
    * @param planFailures       consecutive failed plans in this trip
    * @param digAllowed         the request permits breaking blocks
    * @param lastPathLength     steps in the previous leg's route, 0 before the first plan
    * @param elytraEquipped     an elytra is in the chest slot
    * @param vehicleNearby      a boat, minecart or horse is close enough to mount
    * @param corridorOpenness   standable fraction of the corridor toward the target (0..1)
    * @param corridorWater      water fraction of that corridor (0..1)
    * @param corridorHazard     hazardous fraction of that corridor (0..1)
    */
   public record Situation(
      double horizontalDistance,
      double verticalDistance,
      boolean targetUnloaded,
      int replans,
      double openness,
      double hazardRatio,
      double waterRatio,
      int planFailures,
      boolean digAllowed,
      int lastPathLength,
      boolean elytraEquipped,
      boolean vehicleNearby,
      double corridorOpenness,
      double corridorWater,
      double corridorHazard
   ) {
   }

   private final TinyNet net;

   private NavBrain(final TinyNet net) {
      this.net = net;
   }

   /** The bundled model, or null when it cannot be loaded. */
   public static NavBrain load() {
      TinyNet net = TinyNet.load("/models/navigation.net");
      return net == null ? null : new NavBrain(net);
   }

   /**
    * A model trained from live experience wins over the bundled one: {@code goto neural train} writes it
    * next to bot.yml, and deleting that file is the rollback to the bundled default.
    */
   public static NavBrain load(final File customModel) {
      if (customModel != null && customModel.isFile()) {
         try (Reader reader = Files.newBufferedReader(customModel.toPath(), StandardCharsets.UTF_8)) {
            return new NavBrain(TinyNet.read(reader));
         } catch (Exception ignored) {
            // A malformed custom model falls back to the bundled one rather than disabling the brain.
         }
      }

      return load();
   }

   public Decision decide(final Situation situation) {
      TinyNet.Prediction prediction = this.net.predict(features(situation));
      return switch (prediction.label()) {
         case "wide" -> Decision.WIDE;
         case "dig" -> Decision.DIG;
         case "swim" -> Decision.SWIM;
         case "fly" -> Decision.FLY;
         case "ride" -> Decision.RIDE;
         case "tunnel" -> Decision.TUNNEL;
         case "stop" -> Decision.STOP;
         default -> Decision.NORMAL;
      };
   }

   /** The exact vector the network sees; also what the experience log stores for retraining. */
   public static double[] features(final Situation s) {
      return new double[] {
         Math.min(1.0, s.horizontalDistance() / 128.0),
         Math.min(1.0, Math.abs(s.verticalDistance()) / 32.0),
         s.targetUnloaded() ? 1.0 : 0.0,
         Math.min(1.0, s.replans() / 4.0),
         clamp(s.openness()),
         Math.min(1.0, s.hazardRatio() * 10.0),
         Math.min(1.0, s.waterRatio() * 3.0),
         Math.min(1.0, s.planFailures() / 3.0),
         s.digAllowed() ? 1.0 : 0.0,
         Math.min(1.0, s.lastPathLength() / 96.0),
         s.elytraEquipped() ? 1.0 : 0.0,
         s.vehicleNearby() ? 1.0 : 0.0,
         clamp(s.corridorOpenness()),
         Math.min(1.0, s.corridorWater() * 3.0),
         Math.min(1.0, s.corridorHazard() * 10.0)
      };
   }

   private static double clamp(final double value) {
      return Math.max(0.0, Math.min(1.0, value));
   }
}
