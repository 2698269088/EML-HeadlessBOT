package eml.action;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Terrain facts for plugins, as primitive flag bits - no Minecraft types cross the plugin boundary.
 *
 * <p>The cache is the whole point, not an optimisation: a headless client only holds the chunks inside its
 * view distance, so a long trip unloads the ground behind the bot and a fresh {@code getBlockState} there
 * returns air. Remembering what was observed keeps a plan valid across that boundary; the plugin invalidates
 * a position when it sees a block update, and the cache is dropped when the level changes.
 *
 * <p>Everything here is client-thread state (the level is not thread-safe); read it from
 * {@code ClientTickEvent}/{@code BotCommand}, never from {@code runAsync}.
 */
public final class BotWorld {
   /** Returned by {@link #flags} when the chunk has never been observed: treat it as impassable. */
   public static final int UNKNOWN = 0;

   /** No collision and not dangerous: a body can occupy this cell. */
   public static final int PASSABLE = 1;
   /** A full, sturdy top face: something can stand on this cell. */
   public static final int FLOOR = 1 << 1;
   /** Fire, lava, cactus, powder snow, cobweb... never route through it. */
   public static final int HAZARD = 1 << 2;
   /** {@link #hardness} is non-negative, so the bot may dig it (the threshold is the caller's choice). */
   public static final int BREAKABLE = 1 << 3;
   /** Ladder, vine, scaffolding: climbable with jump/sneak. */
   public static final int CLIMBABLE = 1 << 4;
   /** Water (passable and swimmable). */
   public static final int WATER = 1 << 5;
   /** Collides with the player. */
   public static final int SOLID = 1 << 6;
   /** Air, grass, snow layer...: a block can be placed into it. */
   public static final int REPLACEABLE = 1 << 7;

   private static final int CACHE_LIMIT = 300_000;
   private static final Info UNKNOWN_INFO = new Info(UNKNOWN, -1.0F);

   private record Info(int flags, float hardness) {
   }

   private final Supplier<ClientLevel> levels;
   private final Map<Long, Info> cache = new HashMap<>();
   private ClientLevel level;

   public BotWorld(final Supplier<ClientLevel> levels) {
      this.levels = levels;
   }

   public int flags(final int x, final int y, final int z) {
      return this.info(x, y, z).flags();
   }

   /** Block hardness as vanilla sees it; {@code -1} means unbreakable or unknown. */
   public float hardness(final int x, final int y, final int z) {
      return this.info(x, y, z).hardness();
   }

   public boolean isOpen(final int x, final int y, final int z) {
      int flags = this.flags(x, y, z);
      return flags != UNKNOWN && (flags & (PASSABLE | REPLACEABLE)) != 0;
   }

   public boolean isSolid(final int x, final int y, final int z) {
      return (this.flags(x, y, z) & SOLID) != 0;
   }

   public boolean isChunkLoaded(final int blockX, final int blockZ) {
      ClientLevel current = this.current();
      return current != null && current.getChunkSource().hasChunk(blockX >> 4, blockZ >> 4);
   }

   public int minY() {
      ClientLevel current = this.current();
      return current == null ? -64 : current.getMinY();
   }

   public int maxY() {
      ClientLevel current = this.current();
      return current == null ? 320 : current.getMaxY();
   }

   /** Drops one cached position (block update); the next query re-reads the live chunk. */
   public void forget(final int x, final int y, final int z) {
      this.cache.remove(BlockPos.asLong(x, y, z));
   }

   /** A change at (x,y,z) also affects the floor above it and the support below it. */
   public void forgetAround(final int x, final int y, final int z) {
      this.forget(x, y, z);
      this.forget(x, y - 1, z);
      this.forget(x, y + 1, z);
   }

   public void clear() {
      this.cache.clear();
   }

   public int cachedCount() {
      return this.cache.size();
   }

   /** True when that cell holds water; waterlogged blocks count, because vanilla's fluid state decides. */
   public boolean isWater(final int x, final int y, final int z) {
      return (this.flags(x, y, z) & WATER) != 0;
   }

   /** Ladder, vine, scaffolding: a body can hold on and move vertically. */
   public boolean isClimbable(final int x, final int y, final int z) {
      return (this.flags(x, y, z) & CLIMBABLE) != 0;
   }

   /** Lava, fire, cactus, cobweb, ...: never route through it. */
   public boolean isHazard(final int x, final int y, final int z) {
      return (this.flags(x, y, z) & HAZARD) != 0;
   }

   /** Feet and head free with a floor underneath: the cell a body can stand in. Unknown ground counts as no. */
   public boolean isStandable(final int x, final int y, final int z) {
      return (this.flags(x, y, z) & PASSABLE) != 0
         && (this.flags(x, y + 1, z) & PASSABLE) != 0
         && (this.flags(x, y - 1, z) & FLOOR) != 0;
   }

   private Info info(final int x, final int y, final int z) {
      ClientLevel current = this.current();
      if (current == null) {
         return UNKNOWN_INFO;
      }

      long key = BlockPos.asLong(x, y, z);
      Info cached = this.cache.get(key);
      if (cached != null) {
         return cached;
      }

      if (!current.getChunkSource().hasChunk(x >> 4, z >> 4)) {
         return UNKNOWN_INFO;
      }

      Info computed = compute(current, x, y, z);
      if (this.cache.size() >= CACHE_LIMIT) {
         // A single trip rarely needs more; dropping it costs one re-read per position, never correctness.
         this.cache.clear();
      }

      this.cache.put(key, computed);
      return computed;
   }

   private ClientLevel current() {
      ClientLevel now = this.levels.get();
      if (now != this.level) {
         this.level = now;
         this.cache.clear();
      }

      return now;
   }

   private static Info compute(final ClientLevel level, final int x, final int y, final int z) {
      BlockPos pos = new BlockPos(x, y, z);
      BlockState state = level.getBlockState(pos);
      boolean solid = state.blocksMotion();
      boolean liquid = state.liquid();
      boolean hazard = hazard(state);

      int flags = 0;
      if (!solid && !hazard) {
         flags |= PASSABLE;
      }

      if (solid) {
         flags |= SOLID;
      }

      if (solid && !hazard && standableTop(level, pos, state)) {
         flags |= FLOOR;
      }

      if (hazard) {
         flags |= HAZARD;
      }

      if (state.getFluidState().is(FluidTags.WATER)) {
         flags |= WATER;
      }

      if (state.is(BlockTags.CLIMBABLE)) {
         flags |= CLIMBABLE;
      }

      if (state.isAir() || state.canBeReplaced()) {
         flags |= REPLACEABLE;
      }

      float hardness = state.getDestroySpeed(level, pos);
      if (!state.isAir() && !liquid && !hazard && hardness >= 0.0F) {
         flags |= BREAKABLE;
      }

      return new Info(flags, hardness);
   }

   /**
    * A "floor" is anything the player can stand on: a full top face, or any collision box at least half a
    * block tall (slabs, stairs, fences). Without the second half the bot would refuse every staircase.
    */
   private static boolean standableTop(final ClientLevel level, final BlockPos pos, final BlockState state) {
      if (state.isFaceSturdy(level, pos, Direction.UP)) {
         return true;
      }

      return state.getCollisionShape(level, pos).max(Direction.Axis.Y) >= 0.5;
   }

   /** Blocks a traveller must never enter or stand in; liquids are classified separately. */
   public static boolean hazard(final BlockState state) {
      Block block = state.getBlock();
      if (block == Blocks.LAVA || block == Blocks.FIRE || block == Blocks.SOUL_FIRE
         || block == Blocks.MAGMA_BLOCK || block == Blocks.CACTUS || block == Blocks.POWDER_SNOW
         || block == Blocks.COBWEB || block == Blocks.SWEET_BERRY_BUSH
         || block == Blocks.CAMPFIRE || block == Blocks.SOUL_CAMPFIRE
         || block == Blocks.LAVA_CAULDRON || block == Blocks.POWDER_SNOW_CAULDRON) {
         return true;
      }

      return state.getFluidState().is(FluidTags.LAVA);
   }
}
