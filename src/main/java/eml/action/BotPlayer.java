package eml.action;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Immutable snapshot of the local player, handed to plugins so they never touch {@code LocalPlayer}.
 * Take a fresh one every tick through {@code BotApi#self()}.
 *
 * <p>The fields at the end describe the travel modes the built-in navigator can use (flight, gliding, a
 * vehicle, food, elytra, fireworks), so that layer never has to import a game type to decide what it may do.
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
   int selectedSlot,
   /** Creative flight is on: the jump key ascends and the body never falls. */
   boolean flying,
   /** True while the elytra is open and the body is gliding. */
   boolean gliding,
   /** Simple name of the vehicle being ridden ({@code Boat}, {@code Minecart}, ...), or null. */
   String vehicle,
   int foodLevel,
   /** Horizontal speed in blocks per tick, as the elytra controller wants it. */
   double horizontalSpeed,
   boolean elytraEquipped,
   /** Hotbar slot holding a firework rocket, or {@code -1}. */
   int fireworkSlot
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
         player.getInventory().getSelectedSlot(),
         player.getAbilities().flying,
         player.isFallFlying(),
         player.getVehicle() == null ? null : player.getVehicle().getClass().getSimpleName(),
         player.getFoodData().getFoodLevel(),
         player.getDeltaMovement().horizontalDistance(),
         player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA),
         fireworkSlot(player)
      );
   }

   /** Feet-centre coordinates of a block, the usual aim target for walking. */
   public static double center(final int block) {
      return block + 0.5;
   }

   private static int fireworkSlot(final LocalPlayer player) {
      for (int slot = 0; slot < 9; slot++) {
         ItemStack stack = player.getInventory().getItem(slot);
         if (!stack.isEmpty() && stack.is(Items.FIREWORK_ROCKET)) {
            return slot;
         }
      }

      return -1;
   }
}
