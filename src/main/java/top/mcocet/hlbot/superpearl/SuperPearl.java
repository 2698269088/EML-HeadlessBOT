package top.mcocet.hlbot.superpearl;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import eml.action.BotControls;
import eml.action.BotPlayer;
import eml.action.BotWorld;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import top.mcocet.hlbot.nav.NavOutput;
import top.mcocet.hlbot.nav.Navigator;
import top.mcocet.hlbot.plugins.BotCommand;

/**
 * SuperPearl: walk to a pearl button and press it, which releases the stuck ender pearl and sends the waiting
 * player home.
 *
 * <h2>What it keeps from the original</h2>
 *
 * <ul>
 *   <li>the button's orientation comes from its block state, not from the operator (floor/ceiling/wall);</li>
 *   <li>a standing spot needs line of sight to the button, is sorted by distance to the body, and the next
 *       candidate is tried when a walk fails;</li>
 *   <li>after a press, the bot walks to a configured park point (the original's {@code return.enabled});</li>
 *   <li>a click counts as done when the button reports its pressed state, so a press that worked is never
 *       repeated - re-clicking only happens when the click never reached the button at all;</li>
 *   <li>only one action at a time, and the config is re-read on every request.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #execute} runs on the console input thread and only touches the config file and a request queue;
 * choosing the spot, pressing the button and walking all happen in {@link #tick} on the client thread, because
 * they read the level and the movement keys. That split is why a command can never be answered from a stale
 * half-applied world state, and why the state machine needs no lock of its own.
 */
public final class SuperPearl implements BotCommand {
   public static final SuperPearl INSTANCE = new SuperPearl();

   /** Vanilla's block reach is 4.5 from the eye; 4.0 leaves margin for the server's own check. */
   private static final double CLICK_REACH = 4.0;
   /** A spot closer than this (eye to button) is skipped: the body would be standing on top of the button. */
   private static final double SPOT_MIN = 1.0;
   /** Aim margin: a spot whose eye-to-button distance is this close to the reach is not worth walking to. */
   private static final double SPOT_MARGIN = 0.35;
   /** Ticks spent lining the crosshair up before the click is sent regardless. */
   private static final int AIM_TICKS = 10;
   /** How long the button is watched for its pressed state after a click (3 s: a press lasts >= 0.5 s). */
   private static final int PRESS_TICKS = 60;
   /** Clicks per button before the action is abandoned: only a click that never reached the button counts. */
   private static final int MAX_CLICKS = 3;
   /** How close to the configured park point counts as arrived. */
   private static final double PARK_RADIUS = 1.5;
   /** Ticks a freshly requested walk gets before the navigator's state is trusted. */
   private static final int REQUEST_GRACE = 2;
   /** Watchdog for a state that never ends (30 min). Not a budget: the walk has its own. */
   private static final int WATCHDOG_TICKS = 36_000;

   /** Everything {@code sp} accepts: printed by a malformed request. */
   private static final String FULL_USAGE = "sp back [name] | sp set <name> <x> <y> <z> [face|auto]"
      + " | sp list | sp del <name> | sp return [<x> <y> <z>|off] | sp on | sp off | sp status"
      + " | sp stop | sp reload";

   /** One lang key per subcommand, each holding its usage and one sentence: what {@code sp help} lists. */
   private static final String[] HELP_KEYS = {
      "superpearl.help.back", "superpearl.help.set", "superpearl.help.del", "superpearl.help.list",
      "superpearl.help.return", "superpearl.help.toggle", "superpearl.help.status", "superpearl.help.stop",
      "superpearl.help.reload", "superpearl.help.help"
   };

   /** The comment block every save writes back, so editing the file by hand never costs the explanation. */
   private static final String HEADER = """
      # SuperPearl - walk to a pearl button and press it, which releases the stuck pearl and sends the player home.
      #   enabled  master switch; false keeps the commands usable but refuses 'sp back'
      #   return   where the bot parks after pressing, as x, y, z (omit for none)
      #   buttons  name: x, y, z[, face]; face is UP/DOWN/NORTH/SOUTH/WEST/EAST, omit to read it from the block
      # 'sp set'/'sp del'/'sp return'/'sp on'/'sp off' rewrite this file and keep these comments.
      """;

   private enum Mode {
      IDLE,
      WALKING,
      CLICKING,
      /** A click was sent; the button has not shown its pressed state yet. */
      PRESSING,
      /** Pressed: walking to the configured park point. */
      PARKING
   }

   /** One configured button; {@code face} is a {@code BotControls.FACE_*} constant, or -1 to detect it. */
   public record Button(String name, int x, int y, int z, int face) {
   }

   /** A candidate standing cell, ordered by how far it is from the body when the list was built. */
   private record Spot(int x, int y, int z, double distance) {
   }

   /** The candidates, plus how many cells each filter rejected, so "no spot" can say why. */
   private record Spots(List<Spot> list, int standable, int inReach, int visible) {
   }

   /** The parsed file, swapped whole so a command never sees a half-applied edit. */
   private record Config(boolean enabled, Map<String, Button> buttons, int[] parkPoint) {
   }

   /** A request handed from the command thread to the tick; a null button means "stop". */
   private record Request(Button button, int[] parkPoint) {
   }

   private File file;
   private BotControls controls;
   private BotWorld world;
   private NavOutput output = new NavOutput() {
      @Override
      public void line(final String key, final Object... args) {
      }

      @Override
      public void error(final String key, final Object... args) {
      }
   };

   private volatile Config config = new Config(false, Map.of(), null);
   private final Deque<Request> requests = new ArrayDeque<>();

   /** Published for {@code sp status}, which is read from the console thread. */
   private volatile Mode mode = Mode.IDLE;
   private volatile String targetName = "-";
   private volatile int ticks;
   private volatile int spotIndex;
   private volatile int spotCount;

   private Button target;
   private int face = BotControls.FACE_UP;
   /** Where the crosshair goes: the middle of the button's own shape, not the middle of its block. */
   private double[] aim;
   private final List<Spot> spots = new ArrayList<>();
   private int[] parkPoint;
   private int grace;
   private int clicks;
   private int aimTicks;
   /** The button's pressed state just before the click: a button already powered shows no reaction. */
   private boolean pressedBefore;

   private SuperPearl() {
   }

   // ==================== wiring ====================

   /**
    * Called once from the application. The facades are shared with the navigator on purpose: one terrain
    * cache and one key-mapping driver for the whole client is what keeps the two from disagreeing about the
    * ground or about which key was released last.
    */
   public void install(final File gameDirectory, final BotControls controls, final BotWorld world,
                       final NavOutput output) {
      this.file = new File(gameDirectory, "superpearl.yml");
      this.controls = controls;
      this.world = world;
      this.output = output;
      this.reload();
   }

   // ==================== tick (client thread) ====================

   /** One tick of whatever the action is doing; {@code player} is a snapshot of this tick. */
   public void tick(final BotPlayer player) {
      this.drain(player);

      if (this.mode == Mode.IDLE) {
         return;
      }

      if (player == null) {
         this.fail("superpearl.stop.no_world");
         return;
      }

      if (player.dead()) {
         this.fail("superpearl.stop.died");
         return;
      }

      if (++this.ticks > WATCHDOG_TICKS) {
         this.fail("superpearl.stop.watchdog");
         return;
      }

      switch (this.mode) {
         case WALKING -> this.walking(player);
         case CLICKING -> this.clicking(player);
         case PRESSING -> this.pressing();
         case PARKING -> this.parking(player);
         default -> {
         }
      }
   }

   /** Applies at most one queued request: this is where a command becomes client-thread work. */
   private void drain(final BotPlayer player) {
      Request request;

      synchronized (this.requests) {
         request = this.requests.pollFirst();
      }

      if (request == null) {
         return;
      }

      if (request.button() == null) {
         this.reset();
         return;
      }

      if (this.mode != Mode.IDLE) {
         this.output.error("superpearl.running");
         return;
      }

      if (player == null) {
         this.output.error("superpearl.stop.no_world");
         return;
      }

      this.begin(player, request);
   }

   private void begin(final BotPlayer player, final Request request) {
      Button button = request.button();

      if (!this.world.isChunkLoaded(button.x(), button.z())) {
         this.output.error("superpearl.unloaded", button.name(), button.x(), button.z());
         return;
      }

      int detected = this.controls.buttonFace(button.x(), button.y(), button.z());

      if (detected < 0) {
         this.output.error("superpearl.not_button", button.name(), button.x(), button.y(), button.z());
         return;
      }

      this.target = button;
      this.face = button.face() >= 0 ? button.face() : detected;
      this.aim = null;

      Spots candidates = this.standingSpots(player, button);

      if (candidates.list().isEmpty()) {
         this.reset();
         this.output.error("superpearl.no_spot", button.name(), button.x(), button.y(), button.z());
         this.output.line("superpearl.no_spot_detail",
            candidates.standable(), candidates.inReach(), candidates.visible());
         return;
      }

      this.targetName = button.name();
      this.parkPoint = request.parkPoint();
      this.spots.clear();
      this.spots.addAll(candidates.list());
      this.spotIndex = 0;
      this.spotCount = candidates.list().size();
      this.clicks = 0;
      this.ticks = 0;
      this.mode = Mode.WALKING;
      this.walk();
   }

   private void walking(final BotPlayer player) {
      if (this.clickable(player)) {
         this.stopNavigator();
         this.mode = Mode.CLICKING;
         this.aimTicks = 0;
         return;
      }

      if (this.grace > 0) {
         this.grace--;
         return;
      }

      if (Navigator.INSTANCE.mode() != Navigator.Mode.IDLE) {
         return;
      }

      // The walk ended without a clickable spot. The next best candidate gets its turn, exactly like the
      // original tried every candidate in order before giving up.
      if (++this.spotIndex < this.spots.size()) {
         this.walk();
         return;
      }

      this.fail("superpearl.stop.no_spot_reached", this.target == null ? "-" : this.target.name());
   }

   private void clicking(final BotPlayer player) {
      this.controls.releaseAll();
      this.controls.setSneak(false);

      if (!this.clickable(player)) {
         // Knocked back, or the floor moved: walk to the spot again instead of clicking at nothing.
         this.mode = Mode.WALKING;
         this.walk();
         return;
      }

      // The crosshair goes where the button actually is. Aiming at the block centre instead leaves the body
      // staring at empty space next to a floor button (it only fills the bottom eighth of its block).
      boolean aimed = this.controls.aimAt(this.aim[0], this.aim[1], this.aim[2], 45.0F);

      if (!aimed && this.aimTicks++ < AIM_TICKS) {
         return;
      }

      this.pressedBefore = this.controls.buttonPressed(this.target.x(), this.target.y(), this.target.z());
      this.controls.interactBlock(this.target.x(), this.target.y(), this.target.z(), this.face,
         this.aim[0], this.aim[1], this.aim[2]);
      this.clicks++;
      this.ticks = 0;
      this.output.line("superpearl.clicked", this.target.name(),
         this.target.x(), this.target.y(), this.target.z());

      // A button that has been powered by redstone all along shows no reaction to the click, so there is
      // nothing to confirm and nothing to retry: go straight on.
      if (this.pressedBefore) {
         this.output.line("superpearl.press_unobservable");
         this.afterPress();
         return;
      }

      this.mode = Mode.PRESSING;
   }

   /**
    * Waits for the button to report its pressed state, which is the client's only proof that the click reached
    * the server. Re-clicking is legitimate here - the click may have been rejected for reach or face - and
    * nowhere else: once the button reacts, pressing it again helps nobody.
    */
   private void pressing() {
      if (this.controls.buttonPressed(this.target.x(), this.target.y(), this.target.z())) {
         this.output.line("superpearl.pressed", this.target.name());
         this.afterPress();
         return;
      }

      if (this.ticks <= PRESS_TICKS) {
         return;
      }

      if (this.clicks < MAX_CLICKS) {
         this.mode = Mode.CLICKING;
         this.aimTicks = 0;
         this.ticks = 0;
         this.output.line("superpearl.retry_click", this.clicks, MAX_CLICKS);
         return;
      }

      this.fail("superpearl.stop.never_fired", this.target == null ? "-" : this.target.name());
   }

   /**
    * The press did its job, so the job is over: the pearl belongs to the player, and it is the player who is
    * carried home. All that is left is to walk the bot back to its spot - or to finish right here.
    */
   private void afterPress() {
      if (this.parkPoint == null) {
         this.finish("superpearl.pressed_done", this.target == null ? "-" : this.target.name());
         return;
      }

      this.mode = Mode.PARKING;
      this.ticks = 0;
      this.grace = REQUEST_GRACE;
      Navigator.INSTANCE.requestWalk(this.parkPoint[0], this.parkPoint[1], this.parkPoint[2], false);
      this.output.line("superpearl.parking", this.parkPoint[0], this.parkPoint[1], this.parkPoint[2]);
   }

   private void parking(final BotPlayer player) {
      if (this.grace > 0) {
         this.grace--;
         return;
      }

      double dx = player.x() - (this.parkPoint[0] + 0.5);
      double dy = player.y() - this.parkPoint[1];
      double dz = player.z() - (this.parkPoint[2] + 0.5);

      if (Math.sqrt(dx * dx + dy * dy + dz * dz) <= PARK_RADIUS) {
         this.finish("superpearl.parked", this.parkPoint[0], this.parkPoint[1], this.parkPoint[2]);
         return;
      }

      if (Navigator.INSTANCE.mode() == Navigator.Mode.IDLE) {
         this.fail("superpearl.stop.park_failed",
            this.parkPoint[0], this.parkPoint[1], this.parkPoint[2]);
      }
   }

   // ==================== the action ====================

   private void walk() {
      Spot spot = this.spots.get(this.spotIndex);
      this.grace = REQUEST_GRACE;
      Navigator.INSTANCE.requestWalk(spot.x(), spot.y(), spot.z(), false);
      this.output.line("superpearl.walking", this.target.name(), spot.x(), spot.y(), spot.z(),
         this.spotIndex + 1, this.spots.size());
   }

   /** Inside reach of the button and looking at it: the two things a click needs. Leaves {@code aim} fresh. */
   private boolean clickable(final BotPlayer player) {
      if (this.target == null) {
         return false;
      }

      BotControls.Aim point = this.controls.aimPoint(this.target.x(), this.target.y(), this.target.z());

      if (point == null) {
         return false;
      }

      this.aim = new double[]{point.x(), point.y(), point.z()};
      double distance = eyeDistance(player.x(), player.y() + player.eyeHeight(), player.z(), this.aim);
      return distance <= CLICK_REACH && this.controls.canSee(this.target.x(), this.target.y(), this.target.z());
   }

   /**
    * Candidate standing cells around the button, nearest to the body first: a standable cell, within reach of
    * the button without standing in it, and with a clear line to it. The sight test is the part the first
    * port was missing - without it the nearest cell is often the one behind the wall the button hangs on.
    */
   private Spots standingSpots(final BotPlayer player, final Button button) {
      List<Spot> found = new ArrayList<>();
      int standable = 0;
      int inReach = 0;
      int visible = 0;

      for (int dy = -2; dy <= 2; dy++) {
         for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
               int x = button.x() + dx;
               int y = button.y() + dy;
               int z = button.z() + dz;

               if (!this.world.isStandable(x, y, z)) {
                  continue;
               }

               standable++;

               // The reach of that cell is measured exactly the way the click will be, from that cell's eye to
               // the point on the button the crosshair would land on: any other yardstick picks spots the body
               // then cannot press from.
               BotControls.Aim point = this.controls.aimPointFrom(x + 0.5, y + player.eyeHeight(), z + 0.5,
                  button.x(), button.y(), button.z());

               if (point == null) {
                  continue;
               }

               double[] aim = {point.x(), point.y(), point.z()};
               double reach = eyeDistance(x + 0.5, y + player.eyeHeight(), z + 0.5, aim);

               if (reach < SPOT_MIN || reach > CLICK_REACH - SPOT_MARGIN) {
                  continue;
               }

               inReach++;

               if (!this.controls.canSeeFrom(x, y, z, player.eyeHeight(), button.x(), button.y(), button.z())) {
                  continue;
               }

               visible++;
               double dxb = x + 0.5 - player.x();
               double dyb = y + 0.5 - player.y();
               double dzb = z + 0.5 - player.z();
               found.add(new Spot(x, y, z, Math.sqrt(dxb * dxb + dyb * dyb + dzb * dzb)));
            }
         }
      }

      found.sort(Comparator.comparingDouble(Spot::distance));
      return new Spots(found, standable, inReach, visible);
   }

   private static double eyeDistance(final double eyeX, final double eyeY, final double eyeZ,
                                     final double[] point) {
      double dx = eyeX - point[0];
      double dy = eyeY - point[1];
      double dz = eyeZ - point[2];
      return Math.sqrt(dx * dx + dy * dy + dz * dz);
   }

   private void stopNavigator() {
      // Only a walk of our own: asking the navigator to stop while it is idle would print its own "manual
      // stop" line for nothing, and stopping a 'goto' the user started is not our business.
      if (Navigator.INSTANCE.mode() != Navigator.Mode.IDLE) {
         Navigator.INSTANCE.requestStop();
      }
   }

   private void finish(final String key, final Object... args) {
      this.output.line(key, args);
      this.reset();
   }
   private void fail(final String key, final Object... args) {
      this.reset();
      this.output.error(key, args);
   }

   /** Ends the action: the walk it started is cancelled, the keys are released, the published state is idle. */
   private void reset() {
      // Only a phase that could own the navigator may stop it: a failed request made before any walk started
      // must not cancel a 'goto' the user is running.
      if (this.mode != Mode.IDLE) {
         this.stopNavigator();
      }

      if (this.controls != null) {
         this.controls.releaseAll();
      }

      this.mode = Mode.IDLE;
      this.face = BotControls.FACE_UP;
      this.target = null;
      this.aim = null;
      this.targetName = "-";
      this.parkPoint = null;
      this.spots.clear();
      this.spotIndex = 0;
      this.spotCount = 0;
      this.ticks = 0;
      this.clicks = 0;
      this.aimTicks = 0;
      this.grace = 0;
      this.pressedBefore = false;
   }

   // ==================== command (console thread) ====================

   @Override
   public String name() {
      return "sp";
   }

   /** One line in {@code help}; the whole command surface lives behind {@code sp help} and {@link #FULL_USAGE}. */
   @Override
   public String usage() {
      return "sp back [name] | sp help";
   }

   /** A {@code .lang} key; the console adapter localises command descriptions. */
   @Override
   public String description() {
      return "superpearl.desc";
   }

   @Override
   public void execute(final List<String> args) {
      if (args.isEmpty()) {
         this.output.error("bot.usage", FULL_USAGE);
         return;
      }

      switch (args.get(0).toLowerCase(Locale.ROOT)) {
         case "back" -> this.back(args.size() > 1 ? args.get(1) : null);
         case "set" -> this.set(args);
         case "list" -> this.list();
         case "del", "remove" -> this.delete(args.size() > 1 ? args.get(1) : null);
         case "return", "returnpoint" -> this.setReturn(args);
         case "on", "enable" -> this.setEnabled(true);
         case "off", "disable" -> this.setEnabled(false);
         case "status" -> this.status();
         case "stop" -> this.stop();
         case "reload" -> this.reloadCommand();
         case "help", "?" -> this.printHelp();
         default -> this.output.error("bot.usage", FULL_USAGE);
      }
   }

   /** One line per subcommand, the way the console's own {@code help} lists commands. */
   private void printHelp() {
      for (String key : HELP_KEYS) {
         this.output.line(key);
      }
   }

   private void back(final String requested) {
      // The original re-read its file on every request, so a coordinate fix never needed a restart.
      this.reload();

      Config snapshot = this.config;

      if (!snapshot.enabled()) {
         this.output.error("superpearl.disabled");
         return;
      }

      if (snapshot.buttons().isEmpty()) {
         this.output.error("superpearl.list_empty");
         return;
      }

      String name = requested != null ? requested : defaultName(snapshot);

      if (name == null) {
         this.output.error("superpearl.choose_button", String.join(", ", snapshot.buttons().keySet()));
         return;
      }

      Button button = snapshot.buttons().get(name);

      if (button == null) {
         this.output.error("superpearl.unknown_button", name);
         return;
      }

      if (this.mode != Mode.IDLE) {
         this.output.error("superpearl.running");
         return;
      }

      synchronized (this.requests) {
         this.requests.clear();
         this.requests.addLast(new Request(button, snapshot.parkPoint()));
      }

      this.output.line("superpearl.requested", name);
   }

   /** The button an unqualified {@code sp back} means: the only one, or the one named {@code base}. */
   private static String defaultName(final Config snapshot) {
      if (snapshot.buttons().size() == 1) {
         return snapshot.buttons().keySet().iterator().next();
      }

      return snapshot.buttons().containsKey("base") ? "base" : null;
   }

   private void set(final List<String> args) {
      if (args.size() < 5) {
         this.output.error("bot.usage", FULL_USAGE);
         return;
      }

      String name = args.get(1);
      int x;
      int y;
      int z;

      try {
         x = Integer.parseInt(args.get(2));
         y = Integer.parseInt(args.get(3));
         z = Integer.parseInt(args.get(4));
      } catch (NumberFormatException malformed) {
         this.output.error("bot.usage", FULL_USAGE);
         return;
      }

      int explicit = -1;

      if (args.size() > 5 && !"auto".equalsIgnoreCase(args.get(5))) {
         explicit = faceOf(args.get(5));

         if (explicit < 0) {
            this.output.error("superpearl.bad_face", args.get(5));
            return;
         }
      }

      Config now = this.config;
      Map<String, Button> buttons = new LinkedHashMap<>(now.buttons());
      buttons.put(name, new Button(name, x, y, z, explicit));
      this.update(buttons, now.parkPoint(), now.enabled());

      this.output.line("superpearl.set", name, x, y, z, faceText(explicit));
   }

   private void delete(final String name) {
      if (name == null) {
         this.output.error("bot.usage", FULL_USAGE);
         return;
      }

      Config now = this.config;
      Map<String, Button> buttons = new LinkedHashMap<>(now.buttons());

      if (buttons.remove(name) == null) {
         this.output.error("superpearl.missing", name);
         return;
      }

      this.update(buttons, now.parkPoint(), now.enabled());
      this.output.line("superpearl.removed", name);
   }

   private void setReturn(final List<String> args) {
      Config now = this.config;

      if (args.size() == 1) {
         if (now.parkPoint() == null) {
            this.output.line("superpearl.return_none");
         } else {
            this.output.line("superpearl.return_line",
               now.parkPoint()[0], now.parkPoint()[1], now.parkPoint()[2]);
         }

         return;
      }

      if (args.size() == 2 && ("off".equalsIgnoreCase(args.get(1)) || "none".equalsIgnoreCase(args.get(1)))) {
         this.update(new LinkedHashMap<>(now.buttons()), null, now.enabled());
         this.output.line("superpearl.return_cleared");
         return;
      }

      if (args.size() != 4) {
         this.output.error("bot.usage", FULL_USAGE);
         return;
      }

      try {
         int[] point = {
            Integer.parseInt(args.get(1)), Integer.parseInt(args.get(2)), Integer.parseInt(args.get(3))
         };
         this.update(new LinkedHashMap<>(now.buttons()), point, now.enabled());
         this.output.line("superpearl.return_set", point[0], point[1], point[2]);
      } catch (NumberFormatException malformed) {
         this.output.error("bot.usage", FULL_USAGE);
      }
   }

   private void setEnabled(final boolean enabled) {
      Config now = this.config;
      this.update(new LinkedHashMap<>(now.buttons()), now.parkPoint(), enabled);
      this.output.line(enabled ? "superpearl.enabled" : "superpearl.disabled_now");
   }

   private void list() {
      Config now = this.config;

      if (now.parkPoint() == null) {
         this.output.line("superpearl.return_none");
      } else {
         this.output.line("superpearl.return_line",
            now.parkPoint()[0], now.parkPoint()[1], now.parkPoint()[2]);
      }

      if (now.buttons().isEmpty()) {
         this.output.line("superpearl.list_empty");
         return;
      }

      for (Button button : now.buttons().values()) {
         this.output.line("superpearl.list_line", button.name(),
            button.x(), button.y(), button.z(), faceText(button.face()));
      }
   }

   private void status() {
      Config now = this.config;
      String point = now.parkPoint() == null ? "-"
         : now.parkPoint()[0] + ", " + now.parkPoint()[1] + ", " + now.parkPoint()[2];

      this.output.line("superpearl.status", now.enabled(),
         this.mode.name().toLowerCase(Locale.ROOT), this.targetName,
         this.spotCount == 0 ? 0 : this.spotIndex + 1, this.spotCount,
         this.ticks, now.buttons().size(), point);
   }

   private void stop() {
      synchronized (this.requests) {
         this.requests.clear();
         this.requests.addLast(new Request(null, null));
      }

      // Also stop the walk from here: if the tick that would drain the request never comes (no client, no
      // world), the navigator must still be told this trip is over.
      if (this.mode != Mode.IDLE) {
         this.stopNavigator();
      }

      this.output.line("superpearl.stop.user");
   }

   private void reloadCommand() {
      this.reload();
      this.output.line("superpearl.reloaded", this.config.buttons().size(), this.config.enabled());
   }

   // ==================== config ====================

   /** Reads {@code superpearl.yml} into a fresh snapshot; malformed entries are reported and skipped. */
   private void reload() {
      if (this.file == null) {
         return;
      }

      try {
         if (!this.file.isFile()) {
            Map<String, Object> seeded = new LinkedHashMap<>();
            seeded.put("enabled", false);
            seeded.put("buttons", new LinkedHashMap<String, Object>());
            Files.writeString(this.file.toPath(), HEADER + dump(seeded), StandardCharsets.UTF_8);
         }

         Object parsed;

         try (InputStream stream = Files.newInputStream(this.file.toPath())) {
            parsed = new Yaml().load(stream);
         }

         this.config = parse(parsed);
      } catch (Exception failed) {
         this.output.error("superpearl.read_failed", String.valueOf(failed));
      }
   }

   private Config parse(final Object parsed) {
      if (!(parsed instanceof Map<?, ?> root)) {
         return new Config(false, Map.of(), null);
      }

      Object rawEnabled = root.get("enabled");
      boolean enabled = rawEnabled instanceof Boolean flag
         ? flag
         : Boolean.parseBoolean(String.valueOf(rawEnabled).trim());
      int[] point = parsePoint(root.get("return"));

      Map<String, Button> buttons = new LinkedHashMap<>();

      if (root.get("buttons") instanceof Map<?, ?> saved) {
         for (Map.Entry<?, ?> entry : saved.entrySet()) {
            String name = String.valueOf(entry.getKey());
            Button button = parseButton(name, entry.getValue());

            if (button == null) {
               this.output.error("superpearl.bad_entry", name);
               continue;
            }

            buttons.put(name, button);
         }
      }

      return new Config(enabled, buttons, point);
   }

   /** {@code x, y, z[, face]} — the face omitted means "read it from the block". */
   private static Button parseButton(final String name, final Object value) {
      if (value instanceof List<?> list) {
         if (list.size() < 3) {
            return null;
         }

         try {
            return new Button(name, number(list.get(0)), number(list.get(1)), number(list.get(2)),
               list.size() > 3 ? faceOf(String.valueOf(list.get(3))) : -1);
         } catch (RuntimeException malformed) {
            return null;
         }
      }

      if (value == null) {
         return null;
      }

      String[] parts = String.valueOf(value).trim().split(",");

      if (parts.length < 3) {
         return null;
      }

      try {
         int x = Integer.parseInt(parts[0].trim());
         int y = Integer.parseInt(parts[1].trim());
         int z = Integer.parseInt(parts[2].trim());

         if (parts.length < 4) {
            return new Button(name, x, y, z, -1);
         }

         String token = parts[3].trim();

         if ("auto".equalsIgnoreCase(token)) {
            return new Button(name, x, y, z, -1);
         }

         int face = faceOf(token);
         return face < 0 ? null : new Button(name, x, y, z, face);
      } catch (NumberFormatException malformed) {
         return null;
      }
   }

   private static int number(final Object value) {
      if (value instanceof Number number) {
         return number.intValue();
      }

      return Integer.parseInt(String.valueOf(value).trim());
   }

   private static int[] parsePoint(final Object value) {
      if (value == null) {
         return null;
      }

      if (value instanceof List<?> list) {
         if (list.size() < 3) {
            return null;
         }

         try {
            return new int[]{number(list.get(0)), number(list.get(1)), number(list.get(2))};
         } catch (RuntimeException malformed) {
            return null;
         }
      }

      String text = String.valueOf(value).trim();

      if (text.isEmpty() || "none".equalsIgnoreCase(text)) {
         return null;
      }

      String[] parts = text.split(",");

      if (parts.length != 3) {
         return null;
      }

      try {
         return new int[]{
            Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim())
         };
      } catch (NumberFormatException malformed) {
         return null;
      }
   }

   private void update(final Map<String, Button> buttons, final int[] point, final boolean enabled) {
      this.config = new Config(enabled, buttons, point);
      this.save();
   }

   private void save() {
      if (this.file == null) {
         return;
      }

      Map<String, Object> root = new LinkedHashMap<>();
      root.put("enabled", this.config.enabled());

      if (this.config.parkPoint() != null) {
         int[] point = this.config.parkPoint();
         root.put("return", point[0] + ", " + point[1] + ", " + point[2]);
      }

      Map<String, Object> buttons = new LinkedHashMap<>();
      for (Button button : this.config.buttons().values()) {
         buttons.put(button.name(), button.x() + ", " + button.y() + ", " + button.z()
            + (button.face() < 0 ? "" : ", " + faceName(button.face())));
      }

      root.put("buttons", buttons);

      try {
         Files.writeString(this.file.toPath(), HEADER + dump(root), StandardCharsets.UTF_8);
      } catch (Exception failed) {
         this.output.error("superpearl.write_failed", String.valueOf(failed));
      }
   }

   /**
    * Block style, so a hand-edited buttons list stays one entry per line. SnakeYAML quotes the coordinate
    * strings (they contain commas), which is exactly what keeps them strings on the way back in.
    */
   private static String dump(final Map<String, Object> root) {
      DumperOptions options = new DumperOptions();
      options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
      options.setIndent(2);
      return new Yaml(options).dump(root);
   }

   /** {@code -1} for an explicit face, and the token {@code AUTO} when it comes from the block. */
   private static String faceText(final int face) {
      return face < 0 ? "AUTO" : faceName(face);
   }

   private static int faceOf(final String name) {
      return switch (name.trim().toUpperCase(Locale.ROOT)) {
         case "DOWN" -> BotControls.FACE_DOWN;
         case "UP" -> BotControls.FACE_UP;
         case "NORTH" -> BotControls.FACE_NORTH;
         case "SOUTH" -> BotControls.FACE_SOUTH;
         case "WEST" -> BotControls.FACE_WEST;
         case "EAST" -> BotControls.FACE_EAST;
         default -> -1;
      };
   }

   private static String faceName(final int face) {
      return switch (face) {
         case BotControls.FACE_DOWN -> "DOWN";
         case BotControls.FACE_UP -> "UP";
         case BotControls.FACE_NORTH -> "NORTH";
         case BotControls.FACE_SOUTH -> "SOUTH";
         case BotControls.FACE_WEST -> "WEST";
         case BotControls.FACE_EAST -> "EAST";
         default -> "AUTO";
      };
   }
}
