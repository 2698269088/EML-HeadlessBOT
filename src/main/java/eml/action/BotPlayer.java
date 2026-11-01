package eml.action;

import net.minecraft.client.player.LocalPlayer;

/**
 * Immutable snapshot of the local player, handed to plugins so they never touch {@code LocalPlayer}.
 * Take a fresh one every tick through {@code BotApi#self()}.
 */
public record BotPlayer(
   double x,
   double y,
   double z,
   int blockX,
   int blockY,
   int blockZ,
   float yaw,
   float pitch,
   float eyeHeight,
   boolean onGround,
   boolean inWater,
   boolean inLava,
   boolean dead,
   boolean riding,
   int selectedSlot
) {
   public static BotPlayer of(final LocalPlayer player) {
      return new BotPlayer(
         player.getX(),
         player.getY(),
         player.getZ(),
         player.getBlockX(),
         player.getBlockY(),
         player.getBlockZ(),
         player.getYRot(),
         player.getXRot(),
         player.getEyeHeight(),
         player.onGround(),
         player.isInWater(),
         player.isInLava(),
         player.isDeadOrDying(),
         player.isPassenger(),
         player.getInventory().getSelectedSlot()
      );
   }

   /** Feet-centre coordinates of a block, the usual aim target for walking. */
   public static double center(final int block) {
      return block + 0.5;
   }
}
