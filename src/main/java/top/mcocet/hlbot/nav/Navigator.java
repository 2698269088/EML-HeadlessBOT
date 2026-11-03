package top.mcocet.hlbot.nav;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import eml.action.BotControls;
import eml.action.BotPlayer;
import eml.action.BotWorld;

/**
 * The bot's walker, swimmer, glider and driver: plans with {@link Pathfinder}, then presses the vanilla input
 * keys through {@link BotControls} until the trip is done.
 *
 * <p>No {@code net.minecraft} type is imported here - not the client, not the player, not even the console.
 * The navigator is given a {@link BotPlayer} snapshot every tick, the primitive facades {@link BotWorld} and
 * {@link BotControls}, and a {@link NavOutput} that resolves its message keys. Everything it needs to decide
 * with is already reduced to numbers and flags, which is also what makes it callable from a plugin thread.
 *
 * <h2>Modes</h2>
 *
 * <ul>
 *   <li>{@link Mode#WALK} - the grid route from {@link Pathfinder}: walk, jump, drop, swim, climb, dig.</li>
 *   <li>{@link Mode#FLY} - an elytra trip to a point: take off, cruise at an altitude, boost with fireworks,
 *       dive onto the target. A directed glide, not terrain-aware flight planning.</li>
 *   <li>{@link Mode#RIDE} - get on the nearest boat, minecart or horse and drive to a point, then dismount.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 *
 * <p>Requests (the {@code goto} command, plugin calls) are queued and applied at the start of a tick, so the
 * caller's thread never touches the input keys; the tick itself only ever runs on the client thread. The
 * published state is volatile, so a status read from another thread cannot observe half a route.
 *
 * <h2>Why the follower looks the way it does</h2>
 *
 * <p>The execution rules are simplified ports of Baritone's movement classes (behaviour, not code): arrival
 * is the destination <em>cell</em> rather than an exact centre, a sprint may overshoot and the skipped
 * waypoints are fast-forwarded, a step up is only jumped once the body is lined up and close, sprinting
 * requires a safe landing beyond the next waypoint, and a waypoint that takes far longer than it cost is
 * re-planned with a cost penalty on the failing cell. {@code Movement.update()} also clears
 * {@code abilities.flying} every tick, which is why a walk never turns into a take-off.
 */
public final class Navigator {
   /** One bot per process, like the rest of the client: one navigator too. */
   public static final Navigator INSTANCE = new Navigator();

   /** How close the body has to be to a waypoint cell before the next one becomes the target. */
   private static final double ARRIVE_RADIUS = 0.55;
   /** Block reach for digging, kept under the server's 4.5 so a click is never rejected. */
   private static final double DIG_REACH = 3.5;
   /** Bearing similarity above which two waypoints count as one straight run (about 30 degrees). */
   private static final double STRAIGHT_RUN = 0.87;
   /** Ticks a single waypoint may take before the route is re-planned around it. */
   private static final int WALK_BUDGET = 60;
   private static final int DIG_BUDGET = 220;
   /** How often the terrain cache is dropped, so a long walk sees blocks that changed since the plan. */
   private static final int CACHE_REFRESH_TICKS = 40;
   /** Re-plans allowed per request before the walk is abandoned. */
   private static final int MAX_REPLANS = 4;
   /** Extra cost put on a cell the body failed to get through; Baritone's backoff, simplified. */
   private static final double STUCK_PENALTY = 8.0;
   /** Sprinting burns food; below this vanilla refuses it anyway. */
   private static final int MIN_FOOD_TO_SPRINT = 7;
   /** How long an airborne request waits for the ground before giving up (20 s). */
   private static final int WAIT_FOR_GROUND_TICKS = 400;
   /** Elytra: ticks allowed to get the wings open, and between fireworks. */
   private static final int TAKEOFF_TICKS = 200;
   private static final int BOOST_COOLDOWN = 40;
   /** Vehicle: search radius and ticks allowed to get on something. */
   private static final double MOUNT_RADIUS = 8.0;
   private static final int MOUNT_TICKS = 100;
   /** Trip budget for the non-grid modes (5 minutes). */
   private static final int TRAVEL_TICKS = 6000;

   /** What the navigator is currently doing. */
   public enum Mode {
      IDLE,
      /** The target is known but the body is still in the air (dropped out of creative flight): land first. */
      WAITING,
      WALK,
      FLY,
      RIDE
   }

   private enum FlyPhase {
      TAKEOFF,
      CRUISE,
      LAND
   }

   private enum RidePhase {
      APPROACH,
      DRIVE,
      DISMOUNT
   }

   private enum RequestKind {
      COMMAND,
      WALK,
      FLY,
      RIDE,
      STOP
   }

   private record Request(RequestKind kind, int x, int y, int z, boolean dig, String[] tokens) {
   }

   private BotWorld world;
   private BotControls controls;
   private NavOutput output;

   private final Deque<Request> requests = new ArrayDeque<>();
   private final Map<Long, Double> penalty = new HashMap<>();

   private volatile Mode mode = Mode.IDLE;
   private volatile String detail = "";
   private volatile List<Pathfinder.Step> path = List.of();
   private volatile int index;
   private volatile int ticks;
   private volatile int replans;
   private volatile int targetX;
   private volatile int targetY;
   private volatile int targetZ;

   private Pathfinder.Options options = Pathfinder.Options.DEFAULT;
   private int waypointTicks;
   private int stuck;
   private int escalation;
   private int waitingTicks;
   private int maxTicks;
   private long startedAt;
   private double lastX;
   private double lastZ;

   private FlyPhase flyPhase = FlyPhase.TAKEOFF;
   private double cruiseAltitude;
   /** Height the take-off climbs to before flight is cut and the wings are opened. */
   private double takeoffAltitude;
   private int boostCooldown;

   /** Smart mode: pick the means by distance first, instead of only escalating when the walk fails. */
   private boolean smart;
   private RidePhase ridePhase = RidePhase.APPROACH;
   private int mountTicks;
   private int dismountTicks;
   /** True when the trip actually needs water: boats are then the right vehicle, not whatever is nearest. */
   private boolean preferBoat;

   private Navigator() {
   }

   /** Called once from the application: wires the primitive facades and the message sink. */
   public void install(final BotWorld world, final BotControls controls, final NavOutput output) {
      this.world = world;
      this.controls = controls;
      this.output = output;
   }

   // ==================== requests (any thread) ====================

   /** Applies a {@code goto ...} command line on the next tick. */
   public void enqueueCommand(final List<String> args) {
      this.enqueue(new Request(RequestKind.COMMAND, 0, 0, 0, true, args.toArray(new String[0])));
   }

   /** Walks to a cell; {@code dig} allows breaking through what is in the way. */
   public void requestWalk(final int x, final int y, final int z, final boolean dig) {
      this.enqueue(new Request(RequestKind.WALK, x, y, z, dig, new String[0]));
   }

   /** Flies to a point with the elytra. */
   public void requestFly(final int x, final int y, final int z) {
      this.enqueue(new Request(RequestKind.FLY, x, y, z, false, new String[0]));
   }

   /** Rides the nearest vehicle to a point. */
   public void requestRide(final int x, final int y, final int z) {
      this.enqueue(new Request(RequestKind.RIDE, x, y, z, false, new String[0]));
   }

   /** Ends the trip and releases the keys on the next tick. */
   public void requestStop() {
      this.enqueue(new Request(RequestKind.STOP, 0, 0, 0, false, new String[0]));
   }

   private void enqueue(final Request request) {
      synchronized (this.requests) {
         // Only the newest request matters: queueing two routes would just fight over the keys.
         this.requests.clear();
         this.requests.addLast(request);
      }
   }

   // ==================== published state (any thread) ====================

   public Mode mode() {
      return this.mode;
   }

   public boolean navigating() {
      return this.mode == Mode.WALK || this.mode == Mode.FLY || this.mode == Mode.RIDE;
   }

   public int targetX() {
      return this.targetX;
   }

   public int targetY() {
      return this.targetY;
   }

   public int targetZ() {
      return this.targetZ;
   }

   /** Steps left on a walk; zero for the travel modes, which have no grid route. */
   public int remainingSteps() {
      return this.mode == Mode.WALK ? Math.max(0, this.path.size() - this.index) : 0;
   }

   /** Language-neutral one-line description, for plugins that render their own status. */
   public String statusText() {
      String target = this.targetX + ", " + this.targetY + ", " + this.targetZ;

      return switch (this.mode) {
         case WALK -> "walk " + this.index + "/" + this.path.size() + " to " + target
            + " (" + this.ticks + " ticks, " + this.replans + " replan)";
         case FLY -> "fly to " + target + " (" + this.ticks + " ticks, " + this.flyPhase.name().toLowerCase(Locale.ROOT) + ")";
         case RIDE -> "ride to " + target + " (" + this.ticks + " ticks)";
         case WAITING -> "waiting for the ground";
         default -> "idle" + (this.detail.isEmpty() ? "" : " (" + this.detail + ")");
      };
   }

   // ==================== tick (client thread) ====================

   /** One tick of whatever the navigator is doing; {@code player} is a snapshot of this tick. */
   public void tick(final BotPlayer player, final boolean connected) {
      this.drainRequests(player, connected);

      if (this.mode == Mode.IDLE) {
         return;
      }

      try {
         this.step(player, connected);
      } catch (RuntimeException failure) {
         // This runs inside the patched tick loop: an exception here would unwind it and disconnect the
         // bot. A broken route has to end the route, not the session.
         this.stop("nav.error");
      }
   }

   private void drainRequests(final BotPlayer player, final boolean connected) {
      Request request;

      synchronized (this.requests) {
         request = this.requests.pollFirst();
      }

      if (request == null) {
         return;
      }

      if (player == null || !connected) {
         this.output.error("nav.no_world");
         return;
      }

      switch (request.kind()) {
         case STOP -> {
            this.stop("nav.stop_requested");
         }
         case WALK -> this.beginWalk(player, request.x(), request.y(), request.z(), request.dig());
         case FLY -> this.beginFly(player, request.x(), request.y(), request.z());
         case RIDE -> this.beginRide(player, request.x(), request.y(), request.z());
         case COMMAND -> this.applyCommand(request.tokens(), player);
      }
   }

   private void step(final BotPlayer player, final boolean connected) {
      if (player == null || !connected) {
         this.stop("nav.lost");
         return;
      }

      if (player.dead()) {
         this.stop("nav.died");
         return;
      }

      if (this.mode == Mode.WAITING) {
         this.waitForGround(player);
         return;
      }

      if (this.mode == Mode.WALK) {
         this.stepWalk(player);
         return;
      }

      if (++this.ticks > this.maxTicks) {
         this.stop("nav.timeout");
         return;
      }

      if (this.mode == Mode.FLY) {
         this.stepFly(player);
      } else if (this.mode == Mode.RIDE) {
         this.stepRide(player);
      }
   }

   // ==================== command parsing ====================

   private void applyCommand(final String[] tokens, final BotPlayer player) {
      if (tokens.length == 0) {
         this.output.error("bot.usage", GotoCommand.FULL_USAGE);
         return;
      }

      String first = tokens[0].toLowerCase(Locale.ROOT);

      if ("stop".equals(first)) {
         this.stop("nav.stop_requested");
         return;
      }

      if ("status".equals(first)) {
         this.reportStatus();
         return;
      }

      if ("help".equals(first) || "?".equals(first)) {
         for (String key : GotoCommand.HELP_KEYS) {
            this.output.line(key);
         }
         return;
      }

      RequestKind kind = RequestKind.WALK;
      int from = 0;

      if ("fly".equals(first) || "elytra".equals(first)) {
         kind = RequestKind.FLY;
         from = 1;
      } else if ("ride".equals(first) || "boat".equals(first) || "vehicle".equals(first)) {
         kind = RequestKind.RIDE;
         from = 1;
      }

      List<String> numbers = new ArrayList<>();
      boolean dig = true;
      this.smart = false;

      for (int i = from; i < tokens.length; i++) {
         String token = tokens[i];

         if ("nodig".equalsIgnoreCase(token)) {
            dig = false;
         } else if ("dig".equalsIgnoreCase(token)) {
            dig = true;
         } else if ("smart".equalsIgnoreCase(token)) {
            this.smart = true;
         } else {
            numbers.add(token);
         }
      }

      if (numbers.size() != 2 && numbers.size() != 3) {
         this.output.error("bot.usage", GotoCommand.FULL_USAGE);
         return;
      }

      int x;
      int y;
      int z;

      try {
         x = coordinate(numbers.get(0), player.blockX());
         z = coordinate(numbers.size() == 2 ? numbers.get(1) : numbers.get(2), player.blockZ());
         y = numbers.size() == 3 ? coordinate(numbers.get(1), player.blockY()) : Integer.MIN_VALUE;
      } catch (NumberFormatException malformed) {
         this.output.error("bot.usage", GotoCommand.FULL_USAGE);
         return;
      }

      switch (kind) {
         case FLY -> this.beginFly(player, x, y == Integer.MIN_VALUE ? player.blockY() : y, z);
         case RIDE -> this.beginRide(player, x, y == Integer.MIN_VALUE ? player.blockY() : y, z);
         default -> {
            int standY = y;

            if (standY == Integer.MIN_VALUE) {
               // Two coordinates: the height has to come from the column itself, and a column outside the
               // loaded chunks has none. Fall back to the body's own level and let the planner cut off.
               standY = this.world.isChunkLoaded(x, z) ? this.surface(x, z, player.blockY()) : player.blockY();

               if (standY == Integer.MIN_VALUE) {
                  standY = player.blockY();
               }
            }

            this.beginWalk(player, x, standY, z, dig);
         }
      }
   }

   private void reportStatus() {
      switch (this.mode) {
         case WALK -> this.output.line("nav.status_running", this.index, this.path.size(),
            coord(this.targetX, this.targetY, this.targetZ), this.ticks, this.replans);
         case FLY -> this.output.line("nav.status_flying", coord(this.targetX, this.targetY, this.targetZ), this.ticks);
         case RIDE -> this.output.line("nav.status_riding", coord(this.targetX, this.targetY, this.targetZ), this.ticks);
         default -> this.output.line("nav.status_idle", this.detail.isEmpty() ? "-" : this.detail);
      }
   }

   // ==================== starting a trip ====================

   private void beginWalk(final BotPlayer player, final int x, final int y0, final int z, final boolean dig) {
      if (!player.onGround()) {
         // Creative flight, or a fall in progress: the feet are nowhere near the route, and the height in the
         // request came from mid-air. Resolve the ground once the body is actually standing on it.
         this.targetX = x;
         this.targetY = y0;
         this.targetZ = z;
         this.options = this.walkOptions(dig);
         this.resetForTrip();
         this.mode = Mode.WAITING;
         this.output.line("nav.airborne");
         return;
      }

      int y = y0;

      // A destination outside the loaded chunks has no ground to snap to: keep the requested height and let
      // the planner aim at the edge of the loaded area instead of refusing the request.
      if (this.world.isChunkLoaded(x, z) && !Pathfinder.standable(this.world, x, y, z)) {
         int adjusted = this.surface(x, z, y);

         if (adjusted == Integer.MIN_VALUE) {
            // The height may have come from an airborne body: search around the ground the bot is on.
            adjusted = this.surface(x, z, player.blockY());
         }

         if (adjusted == Integer.MIN_VALUE) {
            this.output.error("nav.no_target", coord(x, y, z));
            return;
         }

         this.output.line("nav.target_adjusted", coord(x, y, z), coord(x, adjusted, z));
         y = adjusted;
      }

      if (player.blockX() == x && player.blockY() == y && player.blockZ() == z) {
         this.output.line("nav.already_there", coord(x, y, z));
         return;
      }

      // Smart mode chooses the means by distance up front, rather than waiting for the walk to fail: the
      // elytra for a long trip, a vehicle for a medium one, walking otherwise. Each still falls back to the
      // next when it cannot start (no elytra, nothing to ride).
      if (this.smart) {
         int span = Math.abs(x - player.blockX()) + Math.abs(z - player.blockZ()) + Math.abs(y - player.blockY());

         if (span > 96 && player.elytraEquipped()) {
            this.beginFly(player, x, y, z);
            return;
         }

         if (span > 32 && this.controls.hasVehicleNearby(MOUNT_RADIUS)) {
            this.beginRide(player, x, y, z);
            return;
         }
      }

      this.options = this.walkOptions(dig);
      this.targetX = x;
      this.targetY = y;
      this.targetZ = z;
      this.resetForTrip();
      this.mode = Mode.WALK;
      this.plan(player, true);
   }

   private void beginFly(final BotPlayer player, final int x, final int y, final int z) {
      // The elytra may only be in the backpack: shift-click it into the chest slot first, then let the flight
      // phase wait for the server to confirm instead of refusing the request.
      if (!player.elytraEquipped() && !this.controls.ensureElytraEquipped()) {
         this.output.error("nav.no_elytra");
         return;
      }

      this.targetX = x;
      this.targetY = y;
      this.targetZ = z;
      this.resetForTrip();
      this.maxTicks = TRAVEL_TICKS;
      this.flyPhase = FlyPhase.TAKEOFF;
      this.cruiseAltitude = 0.0;
      this.takeoffAltitude = Math.max(player.y() + 24.0, y + 30.0);
      this.boostCooldown = 0;
      this.mode = Mode.FLY;
      this.controls.releaseAll();
   }

   private void beginRide(final BotPlayer player, final int x, final int y, final int z) {
      // The vehicle search lives in the controls facade, which is the layer that may touch entities.
      this.controls.releaseAll();

      this.targetX = x;
      this.targetY = y;
      this.targetZ = z;
      this.preferBoat = this.routeNeedsWater(player);

      if (!this.controls.hasVehicleNearby(MOUNT_RADIUS)) {
         this.output.error("nav.no_vehicle", (int) MOUNT_RADIUS);
         return;
      }

      this.resetForTrip();
      this.maxTicks = TRAVEL_TICKS;
      this.ridePhase = RidePhase.APPROACH;
      this.mode = Mode.RIDE;
      this.output.line("nav.ride_started", coord(x, y, z));
   }

   /**
    * Whether the trip really needs water: the grid route is planned once and its swim steps counted. One
    * puddle is not a reason to fetch a boat - walking a shallow stream is fine - but a crossing is.
    */
   private boolean routeNeedsWater(final BotPlayer player) {
      if (!player.onGround()) {
         return player.inWater();
      }

      Pathfinder.Result result = Pathfinder.find(
         this.world, player.blockX(), player.blockY(), player.blockZ(),
         this.targetX, this.targetY, this.targetZ,
         this.walkOptions(false), null,
         (world, x, y, z) -> this.controls.digTicks(x, y, z)
      );

      if (!result.found()) {
         return player.inWater();
      }

      int swim = 0;

      for (Pathfinder.Step step : result.steps()) {
         if (step.kind() == Pathfinder.StepKind.SWIM) {
            swim++;
         }
      }

      return swim > 2;
   }

   private Pathfinder.Options walkOptions(final boolean dig) {
      Pathfinder.Options base = Pathfinder.Options.DEFAULT;
      return new Pathfinder.Options(base.maxNodes(), base.maxDrop(), base.maxWaterFall(), base.maxDigTicks(),
         dig, base.allowSwim(), base.allowClimb(), base.maxRadius(), base.maxRise());
   }

   private void resetForTrip() {
      this.controls.cancelBreak();
      this.controls.releaseAll();
      this.path = List.of();
      this.index = 0;
      this.ticks = 0;
      this.waypointTicks = 0;
      this.stuck = 0;
      this.escalation = 0;
      this.replans = 0;
      this.waitingTicks = 0;
      this.mountTicks = 0;
      this.dismountTicks = 0;
      this.penalty.clear();
      this.detail = "";
   }

   // ==================== walk ====================

   private void waitForGround(final BotPlayer player) {
      if (++this.waitingTicks > WAIT_FOR_GROUND_TICKS) {
         this.stop("nav.never_landed");
         return;
      }

      this.controls.releaseAll();

      // Creative flight is why a request can arrive airborne at all: clearing the flag lets gravity do the
      // waiting, instead of hovering until the budget runs out.
      this.controls.clearFlight();

      if (!player.onGround() && !player.inWater()) {
         return;
      }

      this.mode = Mode.IDLE;
      int adjusted = this.surface(this.targetX, this.targetZ, this.targetY);
      if (adjusted == Integer.MIN_VALUE) {
         adjusted = this.surface(this.targetX, this.targetZ, player.blockY());
      }

      if (adjusted == Integer.MIN_VALUE) {
         // Nothing to snap to (the destination is outside the loaded range, or a bottomless column): keep the
         // body's own level and let the planner aim at the edge of what is loaded instead of refusing.
         adjusted = player.blockY();
      }

      if (adjusted != this.targetY) {
         this.output.line("nav.target_adjusted", coord(this.targetX, this.targetY, this.targetZ),
            coord(this.targetX, adjusted, this.targetZ));
         this.targetY = adjusted;
      }

      this.mode = Mode.WALK;
      this.plan(player, true);
   }

   private void stepWalk(final BotPlayer player) {
      if (++this.ticks > this.maxTicks) {
         this.stop("nav.timeout");
         return;
      }

      // A step-up presses jump, and in creative that same key is the ascend key: leaving flight on turns one
      // hop into a take-off. Cleared every tick, like Baritone's movement update does.
      this.controls.clearFlight();

      // A long walk outlives the terrain: dropping the cache makes every query below read the live chunk
      // again, which is what keeps the standability checks honest (BotWorld only forgets on request).
      if (this.ticks % CACHE_REFRESH_TICKS == 0) {
         this.world.clear();
      }

      this.skipReached(player);

      if (this.index >= this.path.size()) {
         // A best-effort route ends short of the goal when the search budget ran out; carrying on from there
         // is what turns a long trip into progress instead of a failure.
         Pathfinder.Step last = this.path.isEmpty() ? null : this.path.get(this.path.size() - 1);
         boolean reachedGoal = last != null && last.x() == this.targetX && last.y() == this.targetY && last.z() == this.targetZ;

         if (!reachedGoal) {
            this.replan(player, "continue");
            return;
         }

         this.arrive();
         return;
      }

      Pathfinder.Step step = this.path.get(this.index);

      if (++this.waypointTicks > budget(step)) {
         this.replan(player, "timeout");
         return;
      }

      this.controls.releaseAll();

      if (step.kind() == Pathfinder.StepKind.DIG || step.kind() == Pathfinder.StepKind.DIG_DOWN) {
         this.dig(player, step);
         return;
      }

      // The route was planned on cached terrain, and this is the tick it is actually walked into. A block that
      // appeared since (falling gravel, another player, a chunk refresh) is dug out of the way first, the way
      // Running re-checks its positionsToBreak, instead of throwing the whole route away.
      if (!Pathfinder.standable(this.world, step.x(), step.y(), step.z())
         && step.kind() != Pathfinder.StepKind.SWIM && step.kind() != Pathfinder.StepKind.CLIMB) {
         int feet = this.world.flags(step.x(), step.y(), step.z());
         int head = this.world.flags(step.x(), step.y() + 1, step.z());
         boolean blockedFeet = (feet & BotWorld.PASSABLE) == 0
            && this.controls.digTicks(step.x(), step.y(), step.z()) >= 0;
         boolean blockedHead = (feet & BotWorld.PASSABLE) != 0 && (head & BotWorld.PASSABLE) == 0
            && this.controls.digTicks(step.x(), step.y() + 1, step.z()) >= 0;

         if (blockedFeet) {
            this.controls.aimAt(step.x() + 0.5, step.y() + 0.5, step.z() + 0.5, 45.0F);
            this.controls.breakStep(step.x(), step.y(), step.z());
            return;
         }

         if (blockedHead) {
            this.controls.aimAt(step.x() + 0.5, step.y() + 1.5, step.z() + 0.5, 45.0F);
            this.controls.breakStep(step.x(), step.y() + 1, step.z());
            return;
         }

         this.replan(player, "terrain");
         return;
      }

      // A gap under the waypoint: nothing to stand on, no water to land in. Build the floor before stepping.
      if ((step.kind() == Pathfinder.StepKind.WALK || step.kind() == Pathfinder.StepKind.STEP_UP)
         && (this.world.flags(step.x(), step.y() - 1, step.z()) & BotWorld.FLOOR) == 0
         && !this.world.isWater(step.x(), step.y() - 1, step.z())
         && this.bridge(player, step)) {
         return;
      }

      double centerX = step.x() + 0.5;
      double centerZ = step.z() + 0.5;
      double horizontal = Math.sqrt(square(centerX - player.x()) + square(centerZ - player.z()));
      double vertical = player.y() - step.y();

      if (horizontal < ARRIVE_RADIUS && vertical > -0.9 && vertical < 1.6) {
         this.advance();
         return;
      }

      boolean wet = player.inWater() || this.world.isWater(player.blockX(), player.blockY(), player.blockZ());
      boolean holding = this.world.isClimbable(player.blockX(), player.blockY(), player.blockZ())
         || step.kind() == Pathfinder.StepKind.CLIMB;

      if (wet || step.kind() == Pathfinder.StepKind.SWIM) {
         // Rise to the waypoint's level instead of walking along the bottom, and sprint: the sprint key is
         // what keeps the vanilla swim state (and its speed) alive.
         if (player.y() < step.y() + 0.6) {
            this.controls.setJump(true);
         }

         this.controls.setSprint(true);
      } else if (holding) {
         if (step.y() > player.blockY()) {
            this.controls.setJump(true);
         } else if (step.y() < player.blockY()) {
            this.controls.setSneak(true);
         }
      } else if (step.kind() == Pathfinder.StepKind.PARKOUR) {
         // Sprint into the gap and jump late: momentum is what carries the body across, and jumping early
         // means landing in the hole that was supposed to be jumped over.
         this.controls.setSprint(true);

         if (horizontal < 2.6) {
            this.controls.setJump(true);
         }
      } else if (this.readyToJump(player, step)) {
         this.controls.setJump(true);
      }

      boolean aimed = this.controls.aimAt(centerX, player.y() + player.eyeHeight(), centerZ, 32.0F);

      // A water waypoint is walked into even without a perfect aim: waiting on the bank for the crosshair to
      // line up is exactly where the bot used to stop instead of swimming.
      if (aimed || horizontal < 1.2 || this.world.isWater(step.x(), step.y(), step.z())) {
         this.controls.setForward(true);
      }

      if (aimed && !wet && step.kind() == Pathfinder.StepKind.WALK && this.safeToSprint(player)) {
         this.controls.setSprint(true);
      }

      this.detectStuck(player);
   }

   /**
    * Bridges a gap: when the waypoint has no floor under it, a block is placed against the ground below the
    * feet (the "ninja bridge" - the nearest solid face) and the body only steps on once the block is there.
    * Returns false when the bot carries nothing placeable, so the caller falls through to its normal failure
    * handling instead of sneaking forever.
    */
   private boolean bridge(final BotPlayer player, final Pathfinder.Step step) {
      int slot = this.controls.ensurePlaceableBlock();

      if (slot < 0) {
         return false;
      }

      int supportX = player.blockX();
      int supportY = player.blockY() - 1;
      int supportZ = player.blockZ();
      int dx = step.x() - supportX;
      int dz = step.z() - supportZ;

      int face = Math.abs(dx) >= Math.abs(dz)
         ? (dx > 0 ? BotControls.FACE_EAST : BotControls.FACE_WEST)
         : (dz > 0 ? BotControls.FACE_SOUTH : BotControls.FACE_NORTH);

      // Sneak while placing: the body must not walk off the ledge it is building from.
      this.controls.setSneak(true);
      this.controls.selectSlot(slot);
      this.controls.place(step.x(), step.y() - 1, step.z(), supportX, supportY, supportZ, face);
      return true;
   }

   private void dig(final BotPlayer player, final Pathfinder.Step step) {
      double dx = step.x() + 0.5 - player.x();
      double dz = step.z() + 0.5 - player.z();
      double horizontal = Math.sqrt(dx * dx + dz * dz);

      if (step.kind() == Pathfinder.StepKind.DIG && horizontal > DIG_REACH) {
         // Out of reach: close in on the cell first, without touching the dig.
         boolean aimed = this.controls.aimAt(step.x() + 0.5, player.y() + player.eyeHeight(), step.z() + 0.5, 32.0F);
         if (aimed || horizontal < 1.5) {
            this.controls.setForward(true);
         }

         return;
      }

      this.controls.aimAt(step.x() + 0.5, step.y() + 0.5, step.z() + 0.5, 45.0F);

      if (this.controls.breakStep(step.x(), step.y(), step.z())) {
         this.controls.cancelBreak();
         this.advance();
      }
   }

   /**
    * The goal clamped into loaded terrain: when the destination is outside what the client holds, the far edge
    * of the loaded area along the way becomes the goal instead, and the follower re-plans from there when it
    * arrives. That is what makes a multi-thousand-block {@code goto} work at all (Baritone's cut-off paths).
    */
   private int[] reachableGoal(final BotPlayer player) {
      int spanX = this.targetX - player.blockX();
      int spanZ = this.targetZ - player.blockZ();
      int span = Math.max(Math.abs(spanX), Math.abs(spanZ));

      if (span == 0 || this.world.isChunkLoaded(this.targetX, this.targetZ)) {
         return new int[]{this.targetX, this.targetY, this.targetZ};
      }

      int bestX = player.blockX();
      int bestZ = player.blockZ();

      for (int step = 8; step <= span; step += 8) {
         int x = player.blockX() + (int) Math.round((double) spanX * step / span);
         int z = player.blockZ() + (int) Math.round((double) spanZ * step / span);

         if (!this.world.isChunkLoaded(x, z)) {
            break;
         }

         bestX = x;
         bestZ = z;
      }

      int y = this.surface(bestX, bestZ, player.blockY());

      return new int[]{bestX, y == Integer.MIN_VALUE ? player.blockY() : y, bestZ};
   }

   private void plan(final BotPlayer player, final boolean report) {
      this.world.clear();

      int[] goal = this.reachableGoal(player);
      Pathfinder.Result result = Pathfinder.find(
         this.world,
         player.blockX(), player.blockY(), player.blockZ(),
         goal[0], goal[1], goal[2],
         this.options,
         this.penalty,
         (world, x, y, z) -> this.controls.digTicks(x, y, z)
      );

      if (!result.found()) {
         // Smart escalation: use whatever else can still get there, in order - elytra, vehicle, then the
         // straight tunnel. The bot and its surroundings are all resources, not just the grid route.
         if (player.elytraEquipped() && Math.abs(this.targetY - player.blockY()) + Math.abs(this.targetX - player.blockX()) > 32) {
            this.beginFly(player, this.targetX, this.targetY, this.targetZ);
            return;
         }

         if (this.controls.hasVehicleNearby(MOUNT_RADIUS) && Math.abs(this.targetX - player.blockX()) + Math.abs(this.targetZ - player.blockZ()) > 24) {
            this.beginRide(player, this.targetX, this.targetY, this.targetZ);
            return;
         }

         // Last resort, the way Running does it: A* found nothing, so dig a straight tunnel toward the target
         // instead of giving up. Only blocks the tool can actually break are accepted, and only inside chunks
         // the client holds.
         List<Pathfinder.Step> tunnel = this.tunnel(player);

         if (!tunnel.isEmpty()) {
            this.path = tunnel;
            this.index = 0;
            this.ticks = 0;
            this.waypointTicks = 0;
            this.stuck = 0;
            this.lastX = player.x();
            this.lastZ = player.z();
            this.maxTicks = Math.max(240, tunnel.size() * 60);
            this.startedAt = System.currentTimeMillis();
            this.detail = "tunnel";
            this.mode = Mode.WALK;

            if (report) {
               this.output.line("nav.started", coord(this.targetX, this.targetY, this.targetZ),
                  tunnel.size(), result.visited(), result.millis());
            }

            return;
         }

         this.path = List.of();
         this.index = 0;
         this.mode = Mode.IDLE;
         this.detail = result.failure();

         if (report) {
            this.output.error("nav.no_path", coord(this.targetX, this.targetY, this.targetZ),
               result.visited(), result.failure());
         }

         return;
      }

      this.path = result.steps();
      this.index = 0;
      this.ticks = 0;
      this.waypointTicks = 0;
      this.stuck = 0;
      this.lastX = player.x();
      this.lastZ = player.z();
      this.maxTicks = Math.max(240, this.path.size() * 30);
      this.startedAt = System.currentTimeMillis();
      this.detail = "";
      this.mode = Mode.WALK;

      if (report) {
         this.output.line("nav.started", coord(this.targetX, this.targetY, this.targetZ),
            this.path.size(), result.visited(), result.millis());
      }
   }

   /**
    * A straight tunnel toward the target, used only when A* finds no route at all: the horizontal axis with
    * the greater distance is dug cell by cell at the current level. Unbreakable cells and unloaded chunks end
    * it - a fallback that digs forever is worse than a clean failure.
    */
   private List<Pathfinder.Step> tunnel(final BotPlayer player) {
      List<Pathfinder.Step> steps = new ArrayList<>();
      int x = player.blockX();
      int z = player.blockZ();
      int y = player.blockY();
      int guard = 0;

      while ((x != this.targetX || z != this.targetZ) && guard++ < 48) {
         if (Math.abs(this.targetX - x) >= Math.abs(this.targetZ - z)) {
            x += Integer.signum(this.targetX - x);
         } else {
            z += Integer.signum(this.targetZ - z);
         }

         if (!this.world.isChunkLoaded(x, z) || this.controls.digTicks(x, y, z) < 0) {
            break;
         }

         steps.add(new Pathfinder.Step(x, y, z, Pathfinder.StepKind.DIG));
      }

      return steps;
   }

   private void replan(final BotPlayer player, final String reason) {
      this.replans++;

      if (this.replans > MAX_REPLANS) {
         this.stop("nav.replan_failed");
         return;
      }

      this.penalty.merge(key(player.blockX(), player.blockY(), player.blockZ()), STUCK_PENALTY, Double::sum);
      if (this.penalty.size() > 64) {
         this.penalty.clear();
      }

      // Same cut-off goal as the first plan: a stuck leg of a long trip must continue in the next leg, not end
      // the whole trip with "no route from here any more".
      int[] goal = this.reachableGoal(player);
      Pathfinder.Result result = Pathfinder.find(
         this.world,
         player.blockX(), player.blockY(), player.blockZ(),
         goal[0], goal[1], goal[2],
         this.options,
         this.penalty,
         (world, x, y, z) -> this.controls.digTicks(x, y, z)
      );

      if (!result.found()) {
         // Same last resort as the first plan: dig a straight tunnel rather than ending the trip. That is the
         // "no route -> make one" behaviour, and it is why a failed re-plan no longer stops the bot.
         List<Pathfinder.Step> tunnel = this.tunnel(player);

         if (!tunnel.isEmpty()) {
            this.path = tunnel;
            this.index = 0;
            this.waypointTicks = 0;
            this.stuck = 0;
            this.detail = "tunnel";
            this.mode = Mode.WALK;
            this.output.line("nav.replanned", reason, this.replans, tunnel.size());
            return;
         }

         this.stop("nav.replan_failed");
         return;
      }

      this.path = result.steps();
      this.index = 0;
      this.waypointTicks = 0;
      this.stuck = 0;
      this.escalation = 0;
      this.output.line("nav.replanned", reason, this.replans, this.path.size());
   }

   // ==================== elytra ====================

   private void stepFly(final BotPlayer player) {
      if (!player.elytraEquipped()) {
         this.stop("nav.no_elytra");
         return;
      }

      if (!player.gliding()) {
         // Two ways to open the wings, switchable because anticheats differ:
         //   -Deml.elytra.direct=true  send START_FALL_FLYING directly (deterministic, but some servers flag it)
         //   default                   pulse the jump key and let vanilla do it, so nothing but an ordinary
         //                             player input ever reaches the server
         boolean direct = Boolean.parseBoolean(System.getProperty("eml.elytra.direct", "false"));

         if (player.onGround()) {
            this.controls.setJump(true);
         } else if (direct) {
            this.controls.openElytra();
         } else {
            // An edge, not a held key: vanilla only reacts to a fresh press.
            this.controls.setJump((this.ticks % 6) < 3);
         }

         if (this.ticks % 8 == 0) {
            this.controls.setForward(true);
         }

         if (this.ticks > TAKEOFF_TICKS) {
            this.stop("nav.elytra_failed");
         }

         return;
      }

      if (this.flyPhase == FlyPhase.TAKEOFF) {
         this.flyPhase = FlyPhase.CRUISE;
         this.cruiseAltitude = Math.max(player.y() + 25.0, this.targetY + 30.0);
         this.output.line("nav.fly_started", coord(this.targetX, this.targetY, this.targetZ));
      }

      if (player.onGround() || player.inWater()) {
         this.arrive();
         return;
      }

      double horizontal = Math.sqrt(square(this.targetX + 0.5 - player.x()) + square(this.targetZ + 0.5 - player.z()));
      if (this.flyPhase != FlyPhase.LAND && horizontal < 45.0) {
         this.flyPhase = FlyPhase.LAND;
      }

      double aimY;

      if (this.flyPhase == FlyPhase.LAND) {
         aimY = this.targetY + 1.0;
      } else if (player.y() < this.cruiseAltitude - 6.0) {
         aimY = player.y() + 40.0;
      } else if (player.y() > this.cruiseAltitude + 12.0) {
         aimY = player.y() - 30.0;
      } else {
         aimY = player.y() - 4.0;
      }

      this.controls.aimAt(this.targetX + 0.5, aimY, this.targetZ + 0.5, 25.0F);
      this.controls.setSprint(true);

      // A firework is the only thing that keeps an elytra flying in survival - including one that still sits
      // in the backpack, which is why the slot comes from the controls facade rather than the snapshot.
      if (this.boostCooldown-- <= 0 && (player.horizontalSpeed() < 0.9 || player.y() < this.cruiseAltitude - 12.0)) {
         int slot = this.controls.ensureFirework();

         if (slot >= 0) {
            this.controls.useItem();
            this.boostCooldown = BOOST_COOLDOWN;
         }
      }
   }

   // ==================== vehicles ====================

   private void stepRide(final BotPlayer player) {
      if (!player.riding()) {
         this.ridePhase = RidePhase.APPROACH;

         // Walk toward the destination and try to get on the most suitable rideable thing on the way: a boat
         // when the route needs water, a horse on land, a cart for everything else.
         if (this.controls.mountPreferred(MOUNT_RADIUS, this.preferBoat)) {
            this.mountTicks = 0;
         } else if (++this.mountTicks > MOUNT_TICKS) {
            this.stop("nav.mount_failed");
            return;
         }

         boolean aimed = this.controls.aimAt(this.targetX + 0.5, player.y() + player.eyeHeight(), this.targetZ + 0.5, 32.0F);
         if (aimed) {
            this.controls.setForward(true);
         }

         return;
      }

      this.ridePhase = RidePhase.DRIVE;
      this.mountTicks = 0;

      double horizontal = Math.sqrt(square(this.targetX + 0.5 - player.x()) + square(this.targetZ + 0.5 - player.z()));
      if (horizontal < 3.0) {
         this.ridePhase = RidePhase.DISMOUNT;
         // Sneak is the vanilla dismount.
         this.controls.setSneak(true);

         if (++this.dismountTicks > 20) {
            this.arrive();
         }

         return;
      }

      this.controls.aimAt(this.targetX + 0.5, player.y() + player.eyeHeight(), this.targetZ + 0.5, 32.0F);
      this.controls.setForward(true);
      this.controls.setSprint(true);

      if (player.vehicle() != null && player.vehicle().toLowerCase(Locale.ROOT).contains("boat")) {
         // A boat turns with the strafe keys, not with the look direction.
         float wanted = (float) (Math.toDegrees(Math.atan2(this.targetZ + 0.5 - player.z(), this.targetX + 0.5 - player.x())) - 90.0);
         float error = wrapDegrees(wanted - player.yaw());
         this.controls.setRight(error > 6.0F);
         this.controls.setLeft(error < -6.0F);
      }
   }

   // ==================== progress helpers ====================

   private void advance() {
      this.index++;
      this.waypointTicks = 0;
      this.stuck = 0;
      this.escalation = 0;
   }

   /**
    * Skips waypoints the body is already standing in. Sprinting overshoots, and so does a jump that clears
    * two cells; without this the follower would walk back to a cell it has passed.
    */
   private void skipReached(final BotPlayer player) {
      for (int ahead = Math.min(this.path.size() - 1, this.index + 3); ahead >= this.index; ahead--) {
         Pathfinder.Step candidate = this.path.get(ahead);

         if (candidate.x() == player.blockX() && candidate.z() == player.blockZ()
            && Math.abs(candidate.y() - player.blockY()) <= 1) {
            this.index = ahead + 1;
            this.waypointTicks = 0;
            return;
         }
      }
   }

   /**
    * Whether the jump key belongs down this tick.
    *
    * <p>This is vanilla's {@code LocalPlayer.updateAutoJump} reduced to the grid the navigator already works
    * on: look at the cell the body is walking into, and hop when it blocks at foot level but has room above
    * it - a one-block step, not a wall - provided the body itself has head room. The old rule asked for a
    * perfect line-up against the waypoint centre first, which is exactly how the bot ended up pressed against
    * a step, looking down, never jumping.
    */
   private boolean readyToJump(final BotPlayer player, final Pathfinder.Step step) {
      if (step.kind() == Pathfinder.StepKind.PARKOUR) {
         // A gap jump has its own timing: sprint, then hop at the edge. Handled by the caller.
         return false;
      }

      int ax = Integer.signum(step.x() - player.blockX());
      int az = Integer.signum(step.z() - player.blockZ());

      if (ax == 0 && az == 0) {
         return false;
      }

      if (ax != 0 && az != 0) {
         // Diagonal approach: hop along the dominant axis, the way a walking body would.
         if (Math.abs(step.x() + 0.5 - player.x()) >= Math.abs(step.z() + 0.5 - player.z())) {
            az = 0;
         } else {
            ax = 0;
         }
      }

      int aheadX = player.blockX() + ax;
      int aheadZ = player.blockZ() + az;
      boolean blocked = (this.world.flags(aheadX, player.blockY(), aheadZ) & BotWorld.PASSABLE) == 0;
      boolean roomOnTop = (this.world.flags(aheadX, player.blockY() + 1, aheadZ) & BotWorld.PASSABLE) != 0;
      boolean headRoom = (this.world.flags(player.blockX(), player.blockY() + 2, player.blockZ()) & BotWorld.PASSABLE) != 0;

      return blocked && roomOnTop && headRoom;
   }

   /** Traverse's sprint rule: straight ahead, fed, and a floor one waypoint beyond the next. */
   private boolean safeToSprint(final BotPlayer player) {
      if (player.foodLevel() <= MIN_FOOD_TO_SPRINT || this.index + 1 >= this.path.size()) {
         return false;
      }

      Pathfinder.Step here = this.path.get(this.index);
      Pathfinder.Step next = this.path.get(this.index + 1);

      if (next.kind() != Pathfinder.StepKind.WALK || next.y() != here.y()) {
         return false;
      }

      if (this.index + 2 < this.path.size()) {
         Pathfinder.Step beyond = this.path.get(this.index + 2);

         if ((this.world.flags(beyond.x(), beyond.y() - 1, beyond.z()) & BotWorld.FLOOR) == 0) {
            return false;
         }
      }

      double hereX = here.x() + 0.5 - player.x();
      double hereZ = here.z() + 0.5 - player.z();
      double nextX = next.x() + 0.5 - player.x();
      double nextZ = next.z() + 0.5 - player.z();
      double lengths = Math.sqrt(hereX * hereX + hereZ * hereZ) * Math.sqrt(nextX * nextX + nextZ * nextZ);

      return lengths > 0.0 && (hereX * nextX + hereZ * nextZ) / lengths > STRAIGHT_RUN;
   }

   private void detectStuck(final BotPlayer player) {
      if (this.ticks % 10 != 0) {
         return;
      }

      double moved = Math.abs(player.x() - this.lastX) + Math.abs(player.z() - this.lastZ);
      this.lastX = player.x();
      this.lastZ = player.z();

      if (moved > 0.15) {
         this.stuck = 0;
         return;
      }

      if (++this.stuck < 3) {
         return;
      }

      this.stuck = 0;
      this.escalation++;

      // Hop, and keep hopping: one tick of the key is not enough when the body is wedged against a block, and
      // re-planning on the first failed attempt only reproduces the same route.
      this.controls.setJump(true);

      if (this.escalation > 6) {
         this.replan(player, "stuck");
      }
   }

   private void arrive() {
      this.controls.cancelBreak();
      this.controls.releaseAll();
      this.mode = Mode.IDLE;
      this.detail = "arrived";

      if (this.output != null) {
         long seconds = Math.max(0L, (System.currentTimeMillis() - this.startedAt) / 1000L);
         this.output.line("nav.arrived", coord(this.targetX, this.targetY, this.targetZ), seconds, this.path.size());
      }
   }

   /** Ends the trip, releasing every key on the way out. {@code messageKey} is a complete sentence. */
   private void stop(final String messageKey) {
      if (this.controls != null) {
         this.controls.cancelBreak();
         this.controls.releaseAll();
      }

      this.mode = Mode.IDLE;
      this.path = List.of();
      this.index = 0;
      // The published detail is a neutral word ("timeout", "stuck"), so a status read from another thread
      // does not have to know anything about localisation.
      this.detail = messageKey.substring(messageKey.lastIndexOf('.') + 1);

      if (this.output != null) {
         this.output.line(messageKey);
      }
   }

   // ==================== helpers ====================

   /** Nearest standable cell at that column, upwards first and then downwards from {@code fromY}. */
   private int surface(final int x, final int z, final int fromY) {
      if (!this.world.isChunkLoaded(x, z)) {
         return Integer.MIN_VALUE;
      }

      for (int offset = 0; offset <= 12; offset++) {
         if (Pathfinder.standable(this.world, x, fromY + offset, z)) {
            return fromY + offset;
         }

         if (Pathfinder.standable(this.world, x, fromY - offset, z)) {
            return fromY - offset;
         }
      }

      return Integer.MIN_VALUE;
   }

   private static int budget(final Pathfinder.Step step) {
      return step.kind() == Pathfinder.StepKind.DIG || step.kind() == Pathfinder.StepKind.DIG_DOWN ? DIG_BUDGET : WALK_BUDGET;
   }

   /** Absolute coordinates, with {@code ~} and {@code ~n} resolved against {@code origin}. */
   private static int coordinate(final String token, final int origin) {
      String text = token.trim();

      if (text.startsWith("~")) {
         return text.length() == 1 ? origin : origin + Integer.parseInt(text.substring(1));
      }

      return Integer.parseInt(text);
   }

   private static long key(final int x, final int y, final int z) {
      return ((long) (x & 0x1FFFFFF) << 38) | ((long) (z & 0x1FFFFFF) << 13) | (y & 0xFFF);
   }

   private static double square(final double value) {
      return value * value;
   }

   /** Minecraft's yaw wraparound, without importing the game's maths helper. */
   private static float wrapDegrees(final float value) {
      float wrapped = value % 360.0F;

      if (wrapped >= 180.0F) {
         wrapped -= 360.0F;
      }

      if (wrapped < -180.0F) {
         wrapped += 360.0F;
      }

      return wrapped;
   }

   private static String coord(final int x, final int y, final int z) {
      return x + ", " + y + ", " + z;
   }
}
