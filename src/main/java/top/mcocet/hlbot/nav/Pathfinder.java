package top.mcocet.hlbot.nav;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import eml.action.BotWorld;

/**
 * Grid A* over the cells a player body can occupy, using the terrain facts {@link BotWorld} already keeps.
 *
 * <p>No {@code net.minecraft} type appears here: the search reads {@link BotWorld} flags and writes plain
 * step records, which is what lets the whole nav package be driven from a player snapshot.
 *
 * <p>Vanilla's own {@code PathNavigation} is not reusable: it belongs to the server's mob AI, needs a real
 * {@code Level} with a chunk source and answers for entity-sized hitboxes. A client only holds the chunks
 * inside its view distance, so "chunk not loaded" is treated as "not passable" - walking into unknown ground
 * is how a bot ends up falling out of the world.
 *
 * <p>The move set is every way a keyboard can get a body somewhere:
 *
 * <ul>
 *   <li>{@link StepKind#WALK} - a standable neighbour, straight or diagonal (a diagonal only when both
 *       cardinal neighbours are free, so the body never clips a corner);</li>
 *   <li>{@link StepKind#STEP_UP} - one block up, produced by holding jump;</li>
 *   <li>{@link StepKind#DROP} - walking off a ledge. Three blocks on dry ground (the deepest fall without
 *       damage), and far deeper when the landing is water;</li>
 *   <li>{@link StepKind#SWIM} - a water cell, including rising and sinking inside a water column;</li>
 *   <li>{@link StepKind#CLIMB} - up or down a ladder, vine or scaffolding column;</li>
 *   <li>{@link StepKind#DIG} - entering a cell that is still solid: the follower breaks it first. What is
 *       worth breaking is decided by {@link DigCost}, so a pickaxe turns stone into a normal move while a
 *       bare hand does not;</li>
 *   <li>{@link StepKind#DIG_DOWN} - a shaft straight down, only where there is dry, solid floor below.</li>
 * </ul>
 *
 * <p>Costs are in "steps": a walk is 1.0 (1.5 diagonally), a swim 2.0, a climb 1.6, a step up 1.9, a drop
 * grows with its height, and a dig carries its own mining time. The heuristic is octile horizontal plus
 * vertical, which keeps A* fast on open terrain.
 */
public final class Pathfinder {
   /** How the follower enters a cell, and therefore which keys it has to press. */
   public enum StepKind {
      WALK,
      STEP_UP,
      /** A sprint jump over a gap: no block under the cells in between. */
      PARKOUR,
      DROP,
      SWIM,
      CLIMB,
      DIG,
      DIG_DOWN
   }

   /** One cell of a route, in the order it has to be visited. */
   public record Step(int x, int y, int z, StepKind kind) {
   }

   /**
    * A search outcome. {@link #found()} is false when {@link #failure()} says why; {@link #partial()} marks a
    * best-effort route that ends short of the goal because the node budget ran out. Baritone keeps the node
    * closest to the goal for exactly this case, so a long trip makes progress instead of failing outright.
    */
   public record Result(List<Step> steps, int visited, long millis, String failure, boolean partial) {
      public boolean found() {
         return this.failure == null;
      }
   }

   /**
    * Ticks the best tool on hand needs for a cell, or {@code -1} when it cannot be broken at all. Supplied
    * by the caller because only the client knows the inventory.
    */
   public interface DigCost {
      int ticks(BotWorld world, int x, int y, int z);
   }

   /**
    * Search bounds. {@code maxNodes} caps the work one plan may do, {@code maxDrop} the deepest fall on dry
    * ground, {@code maxWaterFall} the deepest drop into water, {@code maxDigTicks} the slowest block worth
    * mining, and the radii keep a route from wandering off into terrain the client does not hold.
    */
   public record Options(int maxNodes, int maxDrop, int maxWaterFall, int maxDigTicks, boolean allowDig,
                         boolean allowSwim, boolean allowClimb, int maxRadius, int maxRise) {
      public static final Options DEFAULT = new Options(8000, 3, 24, 200, true, true, true, 96, 12);
   }

   private static final int[][] CARDINAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
   private static final int[][] DIAGONAL = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

   private Pathfinder() {
   }

   /**
    * Plans a route from the cell {@code (sx, sy, sz)} to {@code (tx, ty, tz)}; the start may be non-standable
    * (a swimmer, a fall in progress).
    *
    * <p>{@code penalty} adds extra cost to specific cells (or is null). The follower uses it the way Baritone
    * does - a cell the body failed to get through is not banned, just made more expensive - so a re-plan can
    * route around a spot that keeps failing without falling back to "no route" for the whole walk.
    */
   public static Result find(final BotWorld world, final int sx, final int sy, final int sz,
                             final int tx, final int ty, final int tz, final Options wanted,
                             final Map<Long, Double> penalty, final DigCost digCost) {
      long started = System.nanoTime();
      Options options = wanted == null ? Options.DEFAULT : wanted;

      if (!world.isChunkLoaded(tx, tz)) {
         return new Result(List.of(), 0, 0L, "target chunk not loaded", false);
      }

      Map<Long, Double> best = new HashMap<>();
      Map<Long, Link> from = new HashMap<>();
      PriorityQueue<Frontier> open = new PriorityQueue<>((left, right) -> Double.compare(left.f, right.f));

      open.add(new Frontier(sx, sy, sz, 0.0, heuristic(sx, sy, sz, tx, ty, tz)));
      best.put(key(sx, sy, sz), 0.0);
      int visited = 0;

      // Best effort: the node closest to the goal so far, returned when the budget runs out.
      int closestX = sx;
      int closestY = sy;
      int closestZ = sz;
      double closest = heuristic(sx, sy, sz, tx, ty, tz);

      while (!open.isEmpty()) {
         Frontier node = open.poll();
         long nodeKey = key(node.x, node.y, node.z);
         Double known = best.get(nodeKey);

         // A cheaper route to this cell was found after it was queued; the stale entry is just skipped.
         if (known != null && node.g > known) {
            continue;
         }

         if (node.x == tx && node.y == ty && node.z == tz) {
            return new Result(reconstruct(from, tx, ty, tz), visited, millis(started), null, false);
         }

         double remaining = heuristic(node.x, node.y, node.z, tx, ty, tz);
         if (remaining < closest) {
            closest = remaining;
            closestX = node.x;
            closestY = node.y;
            closestZ = node.z;
         }

         if (++visited > options.maxNodes()) {
            return partial(from, closestX, closestY, closestZ, visited, started);
         }

         forEachEdge(world, node.x, node.y, node.z, options, digCost, (nx, ny, nz, kind, cost) -> {
            if (Math.abs(nx - sx) > options.maxRadius() || Math.abs(nz - sz) > options.maxRadius()) {
               return;
            }

            if (Math.abs(ny - sy) > options.maxRise()) {
               return;
            }

            long key = key(nx, ny, nz);
            double g = node.g + cost + (penalty == null ? 0.0 : penalty.getOrDefault(key, 0.0));
            Double previous = best.get(key);

            if (previous != null && previous <= g) {
               return;
            }

            best.put(key, g);
            from.put(key, new Link(node.x, node.y, node.z, kind));
            open.add(new Frontier(nx, ny, nz, g, g + heuristic(nx, ny, nz, tx, ty, tz)));
         });
      }

      return new Result(List.of(), visited, millis(started), "no route", false);
   }

   /** The best-effort route to the closest node reached, or "no route" when that is the start cell itself. */
   private static Result partial(final Map<Long, Link> from, final int x, final int y, final int z,
                                 final int visited, final long started) {
      if (from.get(key(x, y, z)) == null) {
         return new Result(List.of(), visited, millis(started), "node budget exhausted", false);
      }

      return new Result(reconstruct(from, x, y, z), visited, millis(started), null, true);
   }

   // ---------- move generation ----------

   private interface EdgeSink {
      void accept(int x, int y, int z, StepKind kind, double cost);
   }

   private static void forEachEdge(final BotWorld world, final int x, final int y, final int z,
                                   final Options options, final DigCost digCost, final EdgeSink sink) {
      boolean wet = world.isWater(x, y, z);
      boolean holding = world.isClimbable(x, y, z);

      for (int[] step : CARDINAL) {
         int nx = x + step[0];
         int nz = z + step[1];

         if (world.isStandable(nx, y, nz)) {
            sink.accept(nx, y, nz, StepKind.WALK, 1.0 + waterPenalty(world, nx, y, nz));
            continue;
         }

         if (options.allowSwim() && water(world, nx, y, nz)) {
            sink.accept(nx, y, nz, StepKind.SWIM, 2.0);
            continue;
         }

         if (open(world, x, y + 2, z) && world.isStandable(nx, y + 1, nz)) {
            sink.accept(nx, y + 1, nz, StepKind.STEP_UP, 1.9 + (wet ? 0.6 : 0.0));
            continue;
         }

         drop(world, nx, y, nz, options, sink);

         if (options.allowDig() && digCost != null && open(world, nx, y + 1, nz) && floor(world, nx, y - 1, nz)) {
            int ticks = digCost.ticks(world, nx, y, nz);

            if (ticks >= 0 && ticks <= options.maxDigTicks()) {
               sink.accept(nx, y, nz, StepKind.DIG, 2.0 + ticks * 0.02);
            }
         }
      }

      for (int[] step : DIAGONAL) {
         int nx = x + step[0];
         int nz = z + step[1];

         if (!world.isStandable(nx, y, nz)) {
            continue;
         }

         // No corner cutting: the body passes through both cardinal neighbours, so both must be free.
         if (!open(world, nx, y, z) || !open(world, x, y, nz)
            || !open(world, nx, y + 1, z) || !open(world, x, y + 1, nz)) {
            continue;
         }

         sink.accept(nx, y, nz, StepKind.WALK, 1.5 + waterPenalty(world, nx, y, nz));
      }

      if (options.allowSwim() && (wet || water(world, x, y + 1, z))) {
         // Swimming straight up or down inside a water column: the surface, or the bottom of a pool.
         if (water(world, x, y + 1, z)) {
            sink.accept(x, y + 1, z, StepKind.SWIM, 2.4);
         }

         if (water(world, x, y - 1, z)) {
            sink.accept(x, y - 1, z, StepKind.SWIM, 2.4);
         }
      }

      if (options.allowClimb() && (holding || world.isClimbable(x, y + 1, z))) {
         if (world.isClimbable(x, y + 1, z)) {
            sink.accept(x, y + 1, z, StepKind.CLIMB, 1.6);
         }

         if (world.isClimbable(x, y - 1, z)) {
            sink.accept(x, y - 1, z, StepKind.CLIMB, 1.6);
         }
      }

      if (options.allowDig() && digCost != null && y - 1 > world.minY()) {
         // A shaft straight down: never into water or a hazard, and only onto solid floor.
         int ticks = digCost.ticks(world, x, y - 1, z);
         boolean dry = !water(world, x, y - 1, z) && !world.isHazard(x, y - 1, z);
         boolean landing = floor(world, x, y - 2, z) && open(world, x, y - 1, z);

         if (ticks >= 0 && ticks <= options.maxDigTicks() && dry && landing) {
            sink.accept(x, y - 1, z, StepKind.DIG_DOWN, 2.5 + ticks * 0.02);
         }
      }

      for (int[] step : CARDINAL) {
         parkour(world, x, y, z, step[0], step[1], sink);
      }
   }

   /**
    * A straight sprint jump over a one- or two-block gap (Baritone's {@code MovementParkour}, without the
    * placing variants): the cells jumped over must be air above a hole, and the landing has to be standable.
    */
   private static void parkour(final BotWorld world, final int x, final int y, final int z,
                               final int dx, final int dz, final EdgeSink sink) {
      for (int gap = 1; gap <= 2; gap++) {
         int landingX = x + dx * (gap + 1);
         int landingZ = z + dz * (gap + 1);

         if (!world.isStandable(landingX, y, landingZ)) {
            continue;
         }

         boolean clear = true;

         for (int i = 1; i <= gap && clear; i++) {
            int mx = x + dx * i;
            int mz = z + dz * i;

            // Air to jump through, and a hole underneath: solid ground there is a walk, not a jump.
            clear = open(world, mx, y, mz) && open(world, mx, y + 1, mz) && !floor(world, mx, y - 1, mz);
         }

         if (!clear) {
            continue;
         }

         sink.accept(landingX, y, landingZ, StepKind.PARKOUR, 2.4 + gap * 0.6);
      }
   }

   /** The first landing below {@code (x, z)}: shallow on dry ground, deep into water (no fall damage). */
   private static void drop(final BotWorld world, final int x, final int y, final int z,
                            final Options options, final EdgeSink sink) {
      for (int depth = 1; depth <= options.maxWaterFall(); depth++) {
         if (!open(world, x, y - depth + 1, z)) {
            return;
         }

         if (water(world, x, y - depth, z)) {
            sink.accept(x, y - depth, z, StepKind.DROP, 1.0 + 0.15 * depth);
            return;
         }

         if (depth > options.maxDrop()) {
            return;
         }

         if (world.isStandable(x, y - depth, z)) {
            sink.accept(x, y - depth, z, StepKind.DROP, 1.0 + 0.6 * depth);
            return;
         }
      }
   }

   // ---------- terrain predicates ----------

   /** A body can occupy that cell: not solid, not a hazard, and the chunk is loaded. */
   private static boolean open(final BotWorld world, final int x, final int y, final int z) {
      return (world.flags(x, y, z) & BotWorld.PASSABLE) != 0;
   }

   private static boolean floor(final BotWorld world, final int x, final int y, final int z) {
      return (world.flags(x, y, z) & BotWorld.FLOOR) != 0;
   }

   /** Water the body can swim through: water, and the chunk is loaded. */
   private static boolean water(final BotWorld world, final int x, final int y, final int z) {
      return world.isWater(x, y, z) && world.isChunkLoaded(x, z);
   }

   /** Standable for a body: feet and head free, a floor under it. Public: the navigator resolves targets with it. */
   public static boolean standable(final BotWorld world, final int x, final int y, final int z) {
      return world.isStandable(x, y, z);
   }

   private static double waterPenalty(final BotWorld world, final int x, final int y, final int z) {
      return world.isWater(x, y, z) ? 1.5 : 0.0;
   }

   private static double heuristic(final int x, final int y, final int z, final int tx, final int ty, final int tz) {
      double dx = Math.abs(x - tx);
      double dz = Math.abs(z - tz);
      return Math.max(dx, dz) + 0.41 * Math.min(dx, dz) + Math.abs(y - ty);
   }

   // ---------- bookkeeping ----------

   /** Packs a cell into a long key: 25 bits per horizontal axis and 12 vertically is plenty for a route. */
   private static long key(final int x, final int y, final int z) {
      return ((long) (x & 0x1FFFFFF) << 38) | ((long) (z & 0x1FFFFFF) << 13) | (y & 0xFFF);
   }

   private static List<Step> reconstruct(final Map<Long, Link> from, final int tx, final int ty, final int tz) {
      List<Step> steps = new ArrayList<>();
      int x = tx;
      int y = ty;
      int z = tz;

      while (true) {
         Link link = from.get(key(x, y, z));
         if (link == null) {
            break;
         }

         steps.add(new Step(x, y, z, link.kind));
         x = link.x;
         y = link.y;
         z = link.z;
      }

      Collections.reverse(steps);
      return steps;
   }

   private static long millis(final long startNanos) {
      return (System.nanoTime() - startNanos) / 1_000_000L;
   }

   private record Frontier(int x, int y, int z, double g, double f) {
   }

   private record Link(int x, int y, int z, StepKind kind) {
   }
}
