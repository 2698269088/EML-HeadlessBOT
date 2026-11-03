package eml.action;

import java.util.function.Supplier;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

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

   /** How far short of a target's surface a sight ray stops: close enough to prove it, far enough to be safe. */
   private static final double SHORT_SIGHT = 0.05;

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
         // Reach for the best tool the bot owns, including one still in the main inventory: a pickaxe in the
         // backpack is the difference between a two-second block and a twenty-second one.
         this.ensureToolFor(x, y, z);
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

   // ---------- flight ----------

   /**
    * Turns creative flight off. A walking bot must never be flying: in creative the jump key is also the
    * ascend key, so one step-up would turn into a take-off (Baritone clears the same flag every movement
    * tick). The server reconciles on its own - this is the client's own ability flag, exactly as vanilla
    * edits it when the player double-taps jump.
    */
   public void clearFlight() {
      LocalPlayer player = this.player();
      if (player != null && player.getAbilities().flying) {
         player.getAbilities().flying = false;
      }
   }

   // ---------- strafing and vehicles ----------

   /** Strafe left. Boats turn with these keys rather than with the look direction. */
   public void setLeft(final boolean down) {
      Minecraft client = this.clients.get();
      if (client != null) {
         set(client.options.keyLeft, down);
      }
   }

   public void setRight(final boolean down) {
      Minecraft client = this.clients.get();
      if (client != null) {
         set(client.options.keyRight, down);
      }
   }

   public void setBack(final boolean down) {
      Minecraft client = this.clients.get();
      if (client != null) {
         set(client.options.keyDown, down);
      }
   }

   /**
    * Ticks the best hotbar tool would need to break that block, or {@code -1} when it cannot be broken at
    * all. Roughly the vanilla formula (hardness, tool speed, the 5x penalty for the wrong tool folded into
    * the base), which is all a route planner needs to prefer a pickaxe-worthy detour over a dirt one.
    */
   public int digTicks(final int x, final int y, final int z) {
      Minecraft client = this.clients.get();
      LocalPlayer player = this.player();
      if (client == null || player == null || client.level == null) {
         return -1;
      }

      BlockPos pos = new BlockPos(x, y, z);
      BlockState state = client.level.getBlockState(pos);
      if (state.isAir()) {
         return 0;
      }

      float hardness = state.getDestroySpeed(client.level, pos);
      if (hardness < 0.0F) {
         return -1;
      }

      if (hardness == 0.0F) {
         return 1;
      }

      // The whole inventory, not just the hotbar: a pickaxe in the backpack is a tool the bot can reach for,
      // and a planner that pretends otherwise refuses perfectly good routes.
      float speed = 1.0F;
      boolean correct = false;

      for (int index = 0; index < 36; index++) {
         ItemStack stack = player.getInventory().getItem(index);
         float candidate = usableSpeed(stack, state);

         if (candidate > speed) {
            speed = candidate;
         }

         if (candidate > 1.0F && stack.isCorrectToolForDrops(state)) {
            correct = true;
         }
      }

      // Vanilla's wrong-tool penalty, folded in so "stone by hand" never looks cheap to the search.
      float ticks = hardness * 30.0F / speed * (correct ? 1.0F : 5.0F);
      return (int) Math.ceil(ticks);
   }

   /** Destroy speed of a stack that still has life left, or 1.0 when it is empty or about to break. */
   private static float usableSpeed(final ItemStack stack, final BlockState state) {
      if (stack.isEmpty()) {
         return 1.0F;
      }

      if (stack.isDamageableItem() && stack.getMaxDamage() - stack.getDamageValue() <= 1) {
         return 1.0F;
      }

      return Math.max(1.0F, stack.getDestroySpeed(state));
   }

   /** Right-clicks with the selected slot: a firework while gliding, a bucket, a spawn egg. */
   public boolean useItem() {
      Minecraft client = this.clients.get();
      LocalPlayer player = this.player();
      if (client == null || player == null) {
         return false;
      }

      client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
      player.swing(InteractionHand.MAIN_HAND);
      return true;
   }

   /** True when a boat, minecart or horse is within {@code radius}: the early answer for a ride request. */
   public boolean hasVehicleNearby(final double radius) {
      return this.findVehicle(radius) != null;
   }

   /**
    * Gets on the nearest boat, minecart or horse within {@code radius}; the mount itself is confirmed by the
    * server a tick or two later. Entity-free on purpose - the navigator above this layer never imports a game
    * type, so vehicles are addressed by distance rather than by handle.
    */
   public boolean mountNearest(final double radius) {
      Entity nearest = this.findVehicle(radius);
      return nearest != null && this.interact(nearest);
   }

   private @Nullable Entity findVehicle(final double radius) {
      Minecraft client = this.clients.get();
      LocalPlayer player = this.player();
      if (client == null || player == null || client.level == null) {
         return null;
      }

      Entity nearest = null;
      double best = Double.MAX_VALUE;

      for (Entity entity : client.level.getEntities(player, player.getBoundingBox().inflate(radius), BotControls::rideable)) {
         double distance = entity.distanceToSqr(player);
         if (distance < best) {
            best = distance;
            nearest = entity;
         }
      }

      return nearest;
   }

   private boolean interact(final Entity entity) {
      Minecraft client = this.clients.get();
      LocalPlayer player = this.player();
      if (client == null || player == null) {
         return false;
      }

      client.gameMode.interact(player, entity, new EntityHitResult(entity), InteractionHand.MAIN_HAND);
      return true;
   }

   /** Rideable types, kept in one place so every caller agrees on what counts. */
   private static boolean rideable(final Entity entity) {
      return entity instanceof AbstractBoat || entity instanceof AbstractMinecart || entity instanceof AbstractHorse;
   }

   // ---------- tool selection ----------

   /** Hotbar slot with the best tool for that block, or {@code -1} when the bare hand is as good as it gets. */
   public int bestToolSlot(final int x, final int y, final int z) {
      LocalPlayer player = this.player();
      BlockState state = this.stateAt(x, y, z);
      if (player == null || state == null) {
         return -1;
      }

      int best = -1;
      float score = 1.0F;

      for (int slot = 0; slot < 9; slot++) {
         float candidate = toolScore(player.getInventory().getItem(slot), state);

         if (candidate > score) {
            score = candidate;
            best = slot;
         }
      }

      return best;
   }

   /** Selects the best hotbar tool for that block; false when nothing in the hotbar beats the hand. */
   public boolean selectBestTool(final int x, final int y, final int z) {
      int slot = this.bestToolSlot(x, y, z);
      return slot >= 0 && this.selectSlot(slot);
   }

   /**
    * Puts the best tool the bot owns in hand, reaching into the main inventory when the hotbar has nothing
    * better than a hand: the winning stack is swapped down with the vanilla number-key click, which is a menu
    * action the server performs rather than an inventory edit. Returns false when a hand really is the best
    * option for that block, so a caller can decide to give up instead of digging for ten minutes.
    */
   public boolean ensureToolFor(final int x, final int y, final int z) {
      LocalPlayer player = this.player();
      BlockState state = this.stateAt(x, y, z);
      if (player == null || state == null) {
         return false;
      }

      int hotbar = -1;
      float hotbarScore = 1.0F;

      for (int slot = 0; slot < 9; slot++) {
         float candidate = toolScore(player.getInventory().getItem(slot), state);

         if (candidate > hotbarScore) {
            hotbarScore = candidate;
            hotbar = slot;
         }
      }

      int source = -1;
      float best = hotbarScore;

      for (int index = 9; index < 36; index++) {
         float candidate = toolScore(player.getInventory().getItem(index), state);

         if (candidate > best) {
            best = candidate;
            source = index;
         }
      }

      if (source < 0) {
         if (hotbar >= 0) {
            this.selectSlot(hotbar);
         }

         return hotbar >= 0;
      }

      int target = hotbar >= 0 ? hotbar : freeHotbarSlot(player);

      if (!this.swapIntoHotbar(source, target)) {
         if (hotbar >= 0) {
            this.selectSlot(hotbar);
         }

         return hotbar >= 0;
      }

      this.selectSlot(target);
      return true;
   }

   // ---------- vehicle selection ----------

   /** A rideable entity as plain data: what it is, how far away, and whether it is already in water. */
   public record VehicleChoice(String type, double distance, boolean waterborne) {
   }

   /** Rideable entities within {@code radius}, nearest first. */
   public java.util.List<VehicleChoice> vehicles(final double radius) {
      Minecraft client = this.clients.get();
      LocalPlayer player = this.player();
      java.util.List<VehicleChoice> found = new java.util.ArrayList<>();

      if (client == null || player == null || client.level == null) {
         return found;
      }

      for (Entity entity : client.level.getEntities(player, player.getBoundingBox().inflate(radius), BotControls::rideable)) {
         found.add(new VehicleChoice(entity.getClass().getSimpleName(),
            Math.sqrt(entity.distanceToSqr(player)), entity.isInWater()));
      }

      found.sort(java.util.Comparator.comparingDouble(VehicleChoice::distance));
      return found;
   }

   /**
    * Mounts the most suitable candidate rather than the nearest one: boats when the trip involves water (they
    * are the only thing that moves well there, and a horse would drown its progress), horses on land (faster
    * than a cart), carts otherwise. Distance still counts, just less than being the right kind of vehicle.
    */
   public boolean mountPreferred(final double radius, final boolean preferBoat) {
      java.util.List<VehicleChoice> candidates = this.vehicles(radius);

      if (candidates.isEmpty()) {
         return false;
      }

      String bestType = candidates.get(0).type();
      double bestScore = Double.MAX_VALUE;

      for (VehicleChoice choice : candidates) {
         String type = choice.type().toLowerCase(java.util.Locale.ROOT);
         double score = choice.distance();

         if (preferBoat) {
            score += type.contains("boat") || type.contains("raft") ? 0.0 : 6.0;
         } else {
            score += type.contains("horse") ? 0.0 : 2.0;
            score += choice.waterborne() ? 3.0 : 0.0;
         }

         if (score < bestScore) {
            bestScore = score;
            bestType = choice.type();
         }
      }

      return this.mountNearestType(radius, bestType);
   }

   private boolean mountNearestType(final double radius, final String type) {
      Minecraft client = this.clients.get();
      LocalPlayer player = this.player();
      if (client == null || player == null || client.level == null) {
         return false;
      }

      Entity best = null;
      double bestDistance = Double.MAX_VALUE;

      for (Entity entity : client.level.getEntities(player, player.getBoundingBox().inflate(radius), BotControls::rideable)) {
         if (!entity.getClass().getSimpleName().equals(type)) {
            continue;
         }

         double distance = entity.distanceToSqr(player);

         if (distance < bestDistance) {
            bestDistance = distance;
            best = entity;
         }
      }

      return best != null && this.interact(best);
   }

   /**
    * Right-clicks the given face of a block: buttons, levers, chests, anything {@code useItemOn} handles. The
    * hit point is the middle of the chosen face, which is what a player's crosshair would produce.
    */
   public boolean interactBlock(final int x, final int y, final int z, final int face) {
      int index = Math.max(0, Math.min(FACES.length - 1, face));
      Direction direction = FACES[index];
      Vec3 hit = Vec3.atCenterOf(new BlockPos(x, y, z)).add(
         direction.getStepX() * 0.5,
         direction.getStepY() * 0.5,
         direction.getStepZ() * 0.5
      );

      return this.interactBlock(x, y, z, face, hit.x, hit.y, hit.z);
   }

   /**
    * The same with an explicit hit point: what a caller who has already picked the crosshair target wants, so
    * the packet carries the point the body is actually looking at instead of the middle of a face plane.
    */
   public boolean interactBlock(final int x, final int y, final int z, final int face,
                                final double hitX, final double hitY, final double hitZ) {
      Minecraft client = this.clients.get();
      LocalPlayer player = this.player();
      if (client == null || player == null) {
         return false;
      }

      int index = Math.max(0, Math.min(FACES.length - 1, face));
      Direction direction = FACES[index];
      BlockPos pos = new BlockPos(x, y, z);
      Vec3 hit = new Vec3(hitX, hitY, hitZ);

      client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, direction, pos, false));
      player.swing(InteractionHand.MAIN_HAND);
      return true;
   }

   // ---------- buttons and sight ----------

   /**
    * Which face of the block in that cell has to be clicked to press it, as one of the {@code FACE_*}
    * constants, or {@code -1} when the block is not a button.
    *
    * <p>Floor and ceiling buttons are pressed from above and below; a wall button is pressed from the side it
    * faces. Reading this from the block state is what saves a caller from having to be told the orientation -
    * and from clicking the wrong face when it was told wrong.
    */
   public int buttonFace(final int x, final int y, final int z) {
      Minecraft client = this.clients.get();
      if (client == null || client.level == null) {
         return -1;
      }

      BlockState state = client.level.getBlockState(new BlockPos(x, y, z));
      if (!state.is(BlockTags.BUTTONS)) {
         return -1;
      }

      if (state.hasProperty(FaceAttachedHorizontalDirectionalBlock.FACE)) {
         AttachFace attach = state.getValue(FaceAttachedHorizontalDirectionalBlock.FACE);

         if (attach == AttachFace.FLOOR) {
            return FACE_UP;
         }

         if (attach == AttachFace.CEILING) {
            return FACE_DOWN;
         }
      }

      if (state.hasProperty(HorizontalDirectionalBlock.FACING)) {
         return faceOf(state.getValue(HorizontalDirectionalBlock.FACING));
      }

      return FACE_UP;
   }

   /** True when the player's own eye has a clear line to that block: nothing else is in the way. */
   public boolean canSee(final int x, final int y, final int z) {
      LocalPlayer player = this.player();
      return player != null && this.sight(player.getEyePosition(), new BlockPos(x, y, z), player);
   }

   /**
    * The same sight test from a cell the body is not standing in yet: a standing pose with its eye
    * {@code eyeHeight} above the cell's floor. This is what lets a caller reject a candidate standing spot
    * that is merely close to the target - the cell behind the wall a button is mounted on, say - instead of
    * walking there and discovering it.
    */
   public boolean canSeeFrom(final int x, final int y, final int z, final double eyeHeight,
                             final int targetX, final int targetY, final int targetZ) {
      LocalPlayer player = this.player();
      if (player == null) {
         return false;
      }

      return this.sight(new Vec3(x + 0.5, y + eyeHeight, z + 0.5),
         new BlockPos(targetX, targetY, targetZ), player);
   }

   /**
    * Where a crosshair aimed at that block lands, as the first point of the block's own outline shape on the
    * line from the eye: the centre of a full cube, the top face of a floor button, the visible side of a wall
    * button.
    *
    * <p>This is not a cosmetic detail. A button fills only a small part of its block, so aiming at the block
    * centre leaves the crosshair in the air beside it - and a sight ray that ends at the block centre never
    * touches the button at all. Returns null when there is no level, or the block is air.
    */
   public @Nullable Aim aimPoint(final int x, final int y, final int z) {
      LocalPlayer player = this.player();
      if (player == null) {
         return null;
      }

      Vec3 eye = player.getEyePosition();
      return this.aimPointFrom(eye.x, eye.y, eye.z, x, y, z);
   }

   /**
    * The same from an eye the body is not standing at yet, so a route planner can score a candidate standing
    * place with the very formula the click will be judged by when the body gets there.
    */
   public @Nullable Aim aimPointFrom(final double eyeX, final double eyeY, final double eyeZ,
                                     final int x, final int y, final int z) {
      Vec3 point = this.surfacePoint(new Vec3(eyeX, eyeY, eyeZ), new BlockPos(x, y, z));
      return point == null ? null : new Aim(point.x, point.y, point.z);
   }

   /**
    * True while that button is in its pressed state. A button stays pressed for a second or so after a click
    * the server accepted, which is the only client-side proof that the click actually reached it - the pearl
    * it releases may or may not move the body.
    */
   public boolean buttonPressed(final int x, final int y, final int z) {
      Minecraft client = this.clients.get();
      if (client == null || client.level == null) {
         return false;
      }

      BlockState state = client.level.getBlockState(new BlockPos(x, y, z));
      return state.is(BlockTags.BUTTONS) && state.hasProperty(ButtonBlock.POWERED)
         && state.getValue(ButtonBlock.POWERED);
   }

   /**
    * True when the line from that eye to the block's visible surface is unobstructed.
    *
    * <p>The ray stops just short of the surface rather than on it: a segment that ends exactly on a face is a
    * coin flip for the numeric test, and a button is mounted on a wall the ray would otherwise report. What it
    * hits on the way decides - nothing, the target itself, or anything at least as far away as the stopping
    * point all mean "visible"; anything nearer is in the way.
    */
   private boolean sight(final Vec3 eye, final BlockPos target, final LocalPlayer player) {
      Minecraft client = this.clients.get();
      if (client == null || client.level == null) {
         return false;
      }

      Vec3 surface = this.surfacePoint(eye, target);
      if (surface == null) {
         return false;
      }

      double distance = eye.distanceTo(surface);
      if (distance < SHORT_SIGHT) {
         return true;
      }

      Vec3 to = surface.add(eye.subtract(surface).scale(SHORT_SIGHT / distance));
      BlockHitResult hit = client.level.clip(new ClipContext(eye, to,
         ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
      return hit.getType() != HitResult.Type.BLOCK
         || hit.getBlockPos().equals(target)
         || hit.getLocation().distanceToSqr(eye) >= eye.distanceToSqr(to) - 1.0E-4;
   }

   /** The point of the block's outline shape a crosshair from {@code eye} lands on; null for air or no level. */
   private @Nullable Vec3 surfacePoint(final Vec3 eye, final BlockPos pos) {
      AABB box = this.shapeBox(pos);
      if (box == null) {
         return null;
      }

      Vec3 centre = box.getCenter();
      return box.clip(eye, centre).orElse(centre);
   }

   /** The block's outline shape as a box in world coordinates, or null when there is no level or it is air. */
   private @Nullable AABB shapeBox(final BlockPos pos) {
      Minecraft client = this.clients.get();
      if (client == null || client.level == null) {
         return null;
      }

      BlockState state = client.level.getBlockState(pos);
      if (state.isAir()) {
         return null;
      }

      VoxelShape shape = state.getShape(client.level, pos, CollisionContext.empty());
      return shape.isEmpty() ? new AABB(pos) : shape.bounds().move(pos);
   }

   /** A point in the world as plain numbers, so no caller outside this package has to hold a {@code Vec3}. */
   public record Aim(double x, double y, double z) {
   }

   private static int faceOf(final Direction direction) {
      return switch (direction) {
         case DOWN -> FACE_DOWN;
         case UP -> FACE_UP;
         case NORTH -> FACE_NORTH;
         case SOUTH -> FACE_SOUTH;
         case WEST -> FACE_WEST;
         case EAST -> FACE_EAST;
      };
   }

   // ---------- automatic item switching ----------

   /** A hotbar slot with placeable blocks, pulling a stack down from the backpack when the hotbar has none. */
   public int ensurePlaceableBlock() {
      LocalPlayer player = this.player();
      if (player == null) {
         return -1;
      }

      for (int slot = 0; slot < 9; slot++) {
         if (placeable(player.getInventory().getItem(slot))) {
            this.selectSlot(slot);
            return slot;
         }
      }

      for (int index = 9; index < 36; index++) {
         if (!placeable(player.getInventory().getItem(index))) {
            continue;
         }

         int target = freeHotbarSlot(player);

         if (!this.swapIntoHotbar(index, target)) {
            return -1;
         }

         this.selectSlot(target);
         return target;
      }

      return -1;
   }

   /** A hotbar slot with fireworks, pulling a stack down from the backpack when needed (elytra boost). */
   public int ensureFirework() {
      LocalPlayer player = this.player();
      if (player == null) {
         return -1;
      }

      for (int slot = 0; slot < 9; slot++) {
         if (isFirework(player.getInventory().getItem(slot))) {
            this.selectSlot(slot);
            return slot;
         }
      }

      for (int index = 9; index < 36; index++) {
         if (!isFirework(player.getInventory().getItem(index))) {
            continue;
         }

         int target = freeHotbarSlot(player);

         if (!this.swapIntoHotbar(index, target)) {
            return -1;
         }

         this.selectSlot(target);
         return target;
      }

      return -1;
   }

   /**
    * Equips an elytra that is only sitting in the inventory: the vanilla shift-click on an armour piece moves
    * it straight into the matching slot, which is one menu action instead of three cursor clicks. False when
    * no elytra is owned at all.
    */
   public boolean ensureElytraEquipped() {
      LocalPlayer player = this.player();
      if (player == null) {
         return false;
      }

      if (player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST)
         .is(net.minecraft.world.item.Items.ELYTRA)) {
         return true;
      }

      BotInventory bag = new BotInventory(this.clients);

      for (int index = 0; index < 36; index++) {
         if (!player.getInventory().getItem(index).is(net.minecraft.world.item.Items.ELYTRA)) {
            continue;
         }

         int slot = bag.playerInventorySlot(index);
         return slot >= 0 && bag.shiftClick(slot);
      }

      return false;
   }

   private static boolean placeable(final ItemStack stack) {
      return !stack.isEmpty() && stack.getItem() instanceof BlockItem block && block.getBlock() != Blocks.AIR;
   }

   private static boolean isFirework(final ItemStack stack) {
      return !stack.isEmpty() && stack.is(net.minecraft.world.item.Items.FIREWORK_ROCKET);
   }

   /**
    * Opens the elytra the way Running does: cut creative flight, then send {@code START_FALL_FLYING} directly.
    * Vanilla reaches the same state from a jump key edge, which a script cannot reliably produce (the press has
    * to land on a tick the client reacts to); the packet is the honest form of the same action.
    */
   public boolean openElytra() {
      Minecraft client = this.clients.get();
      LocalPlayer player = this.player();
      if (client == null || player == null || client.getConnection() == null) {
         return false;
      }

      player.getAbilities().flying = false;
      client.getConnection().send(new net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket(
         player, net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
      return true;
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

   /** The block at a cell, or null when there is no level yet. */
   private @Nullable BlockState stateAt(final int x, final int y, final int z) {
      Minecraft client = this.clients.get();
      if (client == null || client.level == null) {
         return null;
      }

      return client.level.getBlockState(new BlockPos(x, y, z));
   }

   /**
    * How much a stack is worth for that block: its destroy speed, with the correct tool preferred over a
    * coincidentally fast one, and a stack one hit away from breaking scored as no tool at all.
    */
   private static float toolScore(final ItemStack stack, final BlockState state) {
      float speed = usableSpeed(stack, state);

      if (speed <= 1.0F) {
         return 0.0F;
      }

      return stack.isCorrectToolForDrops(state) ? speed + 1.0F : speed;
   }

   /** First empty hotbar slot, else the last one: where a tool pulled from the backpack lands. */
   private static int freeHotbarSlot(final LocalPlayer player) {
      for (int slot = 0; slot < 9; slot++) {
         if (player.getInventory().getItem(slot).isEmpty()) {
            return slot;
         }
      }

      return 8;
   }

   /** The vanilla number-key swap: moves a player-inventory stack into a hotbar slot. */
   private boolean swapIntoHotbar(final int inventoryIndex, final int hotbarIndex) {
      BotInventory bag = new BotInventory(this.clients);
      int slot = bag.playerInventorySlot(inventoryIndex);
      return slot >= 0 && bag.swapWithHotbar(slot, hotbarIndex);
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
