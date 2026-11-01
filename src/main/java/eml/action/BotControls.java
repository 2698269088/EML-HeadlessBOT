package eml.action;

import java.util.function.Supplier;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Vanilla-input driving for plugins: the same {@code KeyMapping} flags a keyboard sets, the same
 * {@code Entity#setYRot} a mouse would change, and the same dig/place calls the player's own click handlers
 * make. Nothing here crafts packets, writes velocity or teleports, so the server only ever sees an ordinary
 * player - which is also why a plugin can be written without importing a single {@code net.minecraft} type.
 *
 * <p>One rule to keep in mind: {@code Minecraft.tick()} fires {@code ClientTickEvent} <em>before</em> the
 * player is ticked, and {@code LocalPlayer.aiStep()} reads {@code KeyMapping} flags inside that later tick.
 * Setting a key here therefore takes effect in the same tick, while rotation set here is what the movement
 * packets of that same tick carry.
 */
public final class BotControls {
   public static final int FACE_DOWN = 0;
   public static final int FACE_UP = 1;
   public static final int FACE_NORTH = 2;
   public static final int FACE_SOUTH = 3;
   public static final int FACE_WEST = 4;
   public static final int FACE_EAST = 5;

   private static final Direction[] FACES = {
      Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
   };

   private final Supplier<Minecraft> clients;
   private BlockPos breaking;
   private boolean breakStarted;

   public BotControls(final Supplier<Minecraft> clients) {
      this.clients = clients;
   }

   // ---------- keys ----------

   /** Releases every key this class can press; call it before each tick's decisions. */
   public void releaseAll() {
      Minecraft client = this.clients.get();
      if (client == null) {
         return;
      }

      set(client.options.keyUp, false);
      set(client.options.keyDown, false);
      set(client.options.keyLeft, false);
      set(client.options.keyRight, false);
      set(client.options.keyJump, false);
      set(client.options.keySprint, false);
      set(client.options.keyShift, false);
   }

   public void setForward(final boolean down) {
      Minecraft client = this.clients.get();
      if (client != null) {
         set(client.options.keyUp, down);
      }
   }

   public void setJump(final boolean down) {
      Minecraft client = this.clients.get();
      if (client != null) {
         set(client.options.keyJump, down);
      }
   }

   public void setSprint(final boolean down) {
      Minecraft client = this.clients.get();
      if (client != null) {
         set(client.options.keySprint, down);
      }
   }

   /** Sneak: descends ladders and stops the bot at ledges. */
   public void setSneak(final boolean down) {
      Minecraft client = this.clients.get();
      if (client != null) {
         set(client.options.keyShift, down);
      }
   }

   public void swing() {
      LocalPlayer player = this.player();
      if (player != null) {
         player.swing(InteractionHand.MAIN_HAND);
      }
   }

   // ---------- looking ----------

   /**
    * Turns toward a point at most {@code maxTurnDegrees} this call and reports whether the crosshair is
    * close enough to act. Called once per tick, this produces the same smooth rotation a player makes.
    */
   public boolean aimAt(final double x, final double y, final double z, final float maxTurnDegrees) {
      LocalPlayer player = this.player();
      if (player == null) {
         return false;
      }

      double dx = x - player.getX();
      double dy = y - (player.getY() + player.getEyeHeight());
      double dz = z - player.getZ();
      float wantedYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
      float wantedPitch = (float) (-Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));

      float yawDiff = Mth.wrapDegrees(wantedYaw - player.getYRot());
      float pitchDiff = wantedPitch - player.getXRot();
      player.setYRot(player.getYRot() + Mth.clamp(yawDiff, -maxTurnDegrees, maxTurnDegrees));
      player.setXRot(player.getXRot() + Mth.clamp(pitchDiff, -maxTurnDegrees, maxTurnDegrees));

      return Math.abs(Mth.wrapDegrees(wantedYaw - player.getYRot())) < 4.0F
         && Math.abs(wantedPitch - player.getXRot()) < 4.0F;
   }

   // ---------- digging ----------

   /**
    * Advances one tick of digging on that block: selects the best hotbar tool once, starts the dig, then
    * keeps it going. Returns true when the block is gone (air or replaceable), so the caller only has to
    * watch a timeout.
    */
   public boolean breakStep(final int x, final int y, final int z) {
      Minecraft client = this.clients.get();
      LocalPlayer player = this.player();
      if (client == null || player == null || client.level == null) {
         return false;
      }

      BlockPos pos = new BlockPos(x, y, z);
      BlockState state = client.level.getBlockState(pos);
      if (!state.blocksMotion() && !state.liquid()) {
         this.cancelBreak();
         return true;
      }

      if (this.breaking != null && !this.breaking.equals(pos)) {
         this.cancelBreak();
      }

      Direction face = facing(player, pos);
      if (!this.breakStarted) {
         this.selectTool(client, player, state);
         client.gameMode.startDestroyBlock(pos, face);
         this.breaking = pos;
         this.breakStarted = true;
      } else {
         client.gameMode.continueDestroyBlock(pos, face);
      }

      player.swing(InteractionHand.MAIN_HAND);
      return false;
   }

   /** Stops the current dig (step finished, path cancelled, or the target changed). */
   public void cancelBreak() {
      Minecraft client = this.clients.get();
      if (client != null && this.breakStarted) {
         client.gameMode.stopDestroyBlock();
      }

      this.breaking = null;
      this.breakStarted = false;
   }

   // ---------- placing ----------

   /** Live block test at a position (bypasses any cache): true when it would stop a body. */
   public boolean isSolidAt(final int x, final int y, final int z) {
      Minecraft client = this.clients.get();
      return client != null && client.level != null
         && client.level.getBlockState(new BlockPos(x, y, z)).blocksMotion();
   }

   /** Live block test: air or replaceable, i.e. free to walk into or to place into. */
   public boolean isOpenAt(final int x, final int y, final int z) {
      Minecraft client = this.clients.get();
      if (client == null || client.level == null) {
         return false;
      }

      BlockState state = client.level.getBlockState(new BlockPos(x, y, z));
      return !state.blocksMotion() && !state.liquid();
   }

   /** First hotbar slot holding a solid, placeable block; {@code -1} when the bot cannot bridge at all. */
   public int findPlaceableSlot() {
      LocalPlayer player = this.player();
      if (player == null) {
         return -1;
      }

      for (int slot = 0; slot < 9; slot++) {
         ItemStack stack = player.getInventory().getItem(slot);
         if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)) {
            continue;
         }

         Block block = item.getBlock();
         if (block == Blocks.AIR) {
            continue;
         }

         BlockState state = block.defaultBlockState();
         if (state.blocksMotion() && !state.canBeReplaced()) {
            return slot;
         }
      }

      return -1;
   }

   public boolean selectSlot(final int slot) {
      LocalPlayer player = this.player();
      if (player == null || slot < 0 || slot > 8) {
         return false;
      }

      player.getInventory().setSelectedSlot(slot);
      return true;
   }

   /**
    * Places one block into (x, y, z) by clicking the given face of a neighbouring support block. The caller
    * supplies where and against what; this method only picks the item, aims the packet and reports whether
    * the client's own prediction shows a block there afterwards.
    */
   public boolean place(final int x, final int y, final int z,
                        final int supportX, final int supportY, final int supportZ, final int face) {
      Minecraft client = this.clients.get();
      LocalPlayer player = this.player();
      if (client == null || player == null || client.level == null) {
         return false;
      }

      BlockPos target = new BlockPos(x, y, z);
      BlockState existing = client.level.getBlockState(target);
      if (existing.blocksMotion()) {
         return true;
      }

      if (!existing.isAir() && !existing.canBeReplaced()
         && !existing.getFluidState().is(FluidTags.WATER)) {
         return false;
      }

      int slot = this.findPlaceableSlot();
      if (slot < 0) {
         return false;
      }

      if (player.getInventory().getSelectedSlot() != slot) {
         player.getInventory().setSelectedSlot(slot);
      }

      Direction direction = FACES[Math.max(0, Math.min(FACES.length - 1, face))];
      BlockPos support = new BlockPos(supportX, supportY, supportZ);
      Vec3 hit = Vec3.atCenterOf(support).add(
         direction.getStepX() * 0.5,
         direction.getStepY() * 0.5,
         direction.getStepZ() * 0.5
      );

      client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, direction, support, false));
      player.swing(InteractionHand.MAIN_HAND);
      return client.level.getBlockState(target).blocksMotion();
   }

   // ---------- internals ----------

   private LocalPlayer player() {
      Minecraft client = this.clients.get();
      return client == null ? null : client.player;
   }

   private static void set(final KeyMapping mapping, final boolean down) {
      if (mapping != null && mapping.isDown() != down) {
         mapping.setDown(down);
      }
   }

   private void selectTool(final Minecraft client, final LocalPlayer player, final BlockState state) {
      float best = 1.0F;
      int bestSlot = -1;

      for (int slot = 0; slot < 9; slot++) {
         ItemStack stack = player.getInventory().getItem(slot);
         if (stack.isEmpty()) {
            continue;
         }

         float speed = stack.getDestroySpeed(state);
         if (speed > best) {
            best = speed;
            bestSlot = slot;
         }
      }

      if (bestSlot >= 0) {
         player.getInventory().setSelectedSlot(bestSlot);
      }
   }

   /** The face of {@code pos} the player's eye is on: what a click would hit first. */
   private static Direction facing(final LocalPlayer player, final BlockPos pos) {
      double dx = pos.getX() + 0.5 - player.getX();
      double dy = pos.getY() + 0.5 - (player.getY() + player.getEyeHeight());
      double dz = pos.getZ() + 0.5 - player.getZ();
      double ax = Math.abs(dx);
      double ay = Math.abs(dy);
      double az = Math.abs(dz);

      if (ax >= ay && ax >= az) {
         return dx > 0.0 ? Direction.WEST : Direction.EAST;
      }

      if (ay >= az) {
         return dy > 0.0 ? Direction.DOWN : Direction.UP;
      }

      return dz > 0.0 ? Direction.NORTH : Direction.SOUTH;
   }
}
