package net.minecraft.client.headless;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.profiling.jfr.Environment;
import net.minecraft.util.profiling.jfr.JvmProfiler;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameType;

/**
 * The console command system.
 *
 * <p>Commands are intentionally thin wrappers that hand work to the client thread through
 * {@link Minecraft#execute(Runnable)}; anything that touches the level, the player or the connection has to
 * run there. Bot logic registers extra commands with {@link #register} at startup.
 */
public final class Commands {
   public interface Command {
      String name();

      String usage();

      String description();

      void execute(ConsoleIO io, HeadlessSession session, List<String> args);
   }

   private static final Map<String, Command> REGISTRY = new LinkedHashMap<>();

   private Commands() {
   }

   public static void register(final Command command) {
      synchronized (REGISTRY) {
         REGISTRY.put(command.name(), command);
      }
   }

   public static boolean isRegistered(final String name) {
      synchronized (REGISTRY) {
         return REGISTRY.containsKey(name);
      }
   }

   /**
    * Registers a command written against the plugin-facing {@code top.mcocet.hlbot.plugins.BotCommand}: the
    * same name/usage/description shape plugins use, but without a plugin owning it, for commands that are
    * part of the bot itself. A throw inside {@code execute} becomes a console error instead of reaching the
    * tick loop.
    */
   public static void register(final top.mcocet.hlbot.plugins.BotCommand command) {
      register(new Command() {
         @Override
         public String name() {
            return command.name();
         }

         @Override
         public String usage() {
            return command.usage();
         }

         @Override
         public String description() {
            // A description that is a .lang key gets localised; a literal (which is what plugin commands
            // usually pass) is returned unchanged, because BotLang falls back to the key itself.
            return BotLang.get(command.description());
         }

         @Override
         public void execute(final ConsoleIO io, final HeadlessSession session, final List<String> args) {
            try {
               command.execute(args);
            } catch (RuntimeException e) {
               io.printError(BotLang.get("bot.command_failed", e));
            }
         }
      });
   }

   /** Removes a command previously added with {@link #register}; used when a plugin disables. */
   public static void unregister(final String name) {
      synchronized (REGISTRY) {
         REGISTRY.remove(name);
      }
   }

   /** Registered command names starting with the given (lowercased) prefix, for tab-completion. */
   public static List<String> complete(final String prefix) {
      List<String> matches = new ArrayList<>();
      for (String name : snapshotNames()) {
         if (name.toLowerCase(java.util.Locale.ROOT).startsWith(prefix)) {
            matches.add(name);
         }
      }

      return matches;
   }

   /** True when any registered command starts with the prefix - tells typo (red) from unfinished (blue). */
   public static boolean hasCommandStartingWith(final String prefix) {
      for (String name : snapshotNames()) {
         if (name.toLowerCase(java.util.Locale.ROOT).startsWith(prefix)) {
            return true;
         }
      }

      return false;
   }

   /** Snapshot of the command names; the registry is mutated on the client thread when plugins toggle. */
   private static List<String> snapshotNames() {
      synchronized (REGISTRY) {
         return new ArrayList<>(REGISTRY.keySet());
      }
   }

   /** @return true when the line was consumed by a command */
   public static boolean dispatch(final ConsoleIO io, final HeadlessSession session, final String line) {
      String trimmed = line.trim();
      if (trimmed.isEmpty()) {
         return false;
      }

      String[] parts = trimmed.split("\\s+");
      Command command;
      synchronized (REGISTRY) {
         command = REGISTRY.get(parts[0].toLowerCase());
      }
      if (command == null) {
         return false;
      }

      try {
         command.execute(io, session, new ArrayList<>(List.of(parts).subList(1, parts.length)));
      } catch (Exception e) {
         io.printError(BotLang.get("bot.command_failed", e));
      }

      return true;
   }

   public static List<String> helpLines() {
      List<String> lines = new ArrayList<>();
      List<Command> snapshot;
      synchronized (REGISTRY) {
         snapshot = new ArrayList<>(REGISTRY.values());
      }

      // A command may be registered under several names - quit/end/stop are one behaviour - and the list names
      // each behaviour once instead of repeating the same line for every spelling.
      java.util.Set<String> seen = new java.util.LinkedHashSet<>();

      for (Command command : snapshot) {
         String line = String.format("  %-22s %s", command.usage(), command.description());

         if (seen.add(line)) {
            lines.add(line);
         }
      }

      return lines;
   }

   public static void bootstrap() {
      register(new Help());
      register(new Status());
      register(new BotInfo());
      register(new Connect());
      register(new Disconnect());
      register(new Say());
      register(new ServerCommand());
      register(new Slot());
      register(new Jump());
      register(new Sprint());
      register(new Sneak());
      register(new Position("pos"));
      register(new Position("coords"));
      register(new Position("position"));
      register(new Swing());
      register(new Drop());
      register(new Attack());
      register(new Look());
      register(new Mine());
      register(new GameMode());
      register(new Respawn());
      register(new Msgs());
      register(new Jfr());
      register(new PluginList());
      register(new DialogCommand());
      register(new ItemCommand());
      register(new ContainerCommand());
      register(new Login());
      // One action, three spellings. 'goto stop' stays the navigator's; these three exit the bot.
      register(new Quit("quit"));
      register(new Quit("end"));
      register(new Quit("stop"));
   }

   /** Runs the action on the client thread and reports completion or failure to the console. */
   private static void onClient(final HeadlessSession session, final ConsoleIO io, final String label, final Runnable action) {
      Minecraft client = session.client();
      if (!session.isStarted()) {
         io.printError(BotLang.get("bot.client_not_running"));
         return;
      }

      client.execute(() -> {
         try {
            action.run();
         } catch (Exception e) {
            io.printError(BotLang.get("bot.action_failed", label, e));
         }
      });
   }

   private static class Help implements Command {
      @Override
      public String name() {
         return "help";
      }

      @Override
      public String usage() {
         return "help";
      }

      @Override
      public String description() {
         return BotLang.get("cmd.help.desc");
      }

      @Override
      public void execute(final ConsoleIO io, final HeadlessSession session, final List<String> args) {
         io.print(BotLang.get("help.header"));
         io.printBlock(helpLines());
         io.print(BotLang.get("help.footer"));
      }
   }

   private static class Status implements Command {
      @Override
      public String name() {
         return "status";
      }

      @Override
      public String usage() {
         return "status";
      }

      @Override
      public String description() {
         return BotLang.get("cmd.status.desc");
      }

      @Override
      public void execute(final ConsoleIO io, final HeadlessSession session, final List<String> args) {
         Minecraft client = session.client();
         List<String> lines = new ArrayList<>();
         lines.add("user       : " + client.getUser().getName() + " (" + client.getUser().getProfileId() + ")");
         lines.add("version    : " + net.minecraft.SharedConstants.getCurrentVersion().name()
            + " / protocol " + net.minecraft.SharedConstants.getProtocolVersion());
         lines.add("engine     : " + (client.isRunning() ? "running" : "stopping"));

         if (client.getConnection() != null) {
            net.minecraft.network.Connection connection = client.getConnection().getConnection();
            lines.add("connection : play (open=" + connection.isConnected()
               + ", avg pkt/s=" + String.format(java.util.Locale.ROOT, "%.1f", connection.getAverageSentPackets()) + ")");
         } else if (eml.action.BotActions.pendingConnection(client) != null) {
            lines.add("connection : handshake/login (open=" + eml.action.BotActions.pendingConnection(client).isConnected() + ")");
         } else {
            lines.add("connection : none");
         }

         lines.add("level      : " + (client.level == null ? "none" : client.level.dimension().identifier()));

         // The session knows the mode that actually applies (a command-line flag overrides bot.yml for one
         // run), and the reported value is what the client last told the server.
         int serverViewDistance = eml.hooks.BotRuntime.serverViewDistance();
         lines.add("view dist  : " + session.viewDistanceMode().describe() + " (reported "
            + client.options.renderDistance().get()
            + ", server " + (serverViewDistance <= 0 ? "n/a" : String.valueOf(serverViewDistance)) + ")");

         if (client.player != null) {
            lines.add(String.format(
               java.util.Locale.ROOT,
               "player     : (%.1f, %.1f, %.1f) health=%.1f food=%d gamemode=%s",
               client.player.getX(),
               client.player.getY(),
               client.player.getZ(),
               client.player.getHealth(),
               client.player.getFoodData().getFoodLevel(),
               client.gameMode == null ? "?" : client.gameMode.getPlayerMode().getName()
            ));
         }

         lines.add("packs      : " + eml.hooks.BotResourcePacks.seen() + " answered, policy="
            + eml.hooks.BotResourcePacks.policy().name().toLowerCase(java.util.Locale.ROOT));
         lines.add("chat log   : " + eml.hooks.BotRuntime.history().size() + " message(s)");
         io.printBlock(lines);
      }
   }

   private static class Connect implements Command {
      @Override
      public String name() {
         return "connect";
      }

      @Override
      public String usage() {
         return "connect <host[:port]>";
      }

      @Override
      public String description() {
         return BotLang.get("cmd.connect.desc");
      }

      @Override
      public void execute(final ConsoleIO io, final HeadlessSession session, final List<String> args) {
         if (args.isEmpty()) {
            io.printError(BotLang.get("bot.usage", this.usage()));
            return;
         }

         String target = args.get(0);
         String host = target;
         int port = 25565;
         int split = target.lastIndexOf(':');
         if (split > 0) {
            host = target.substring(0, split);
            port = Integer.parseInt(target.substring(split + 1));
         }

         session.connect(host, port);
      }
   }

   private static class Disconnect implements Command {
      @Override
      public String name() {
         return "disconnect";
      }

      @Override
      public String usage() {
         return "disconnect [reason]";
      }

      @Override
      public String description() {
         return BotLang.get("cmd.disconnect.desc");
      }

      @Override
      public void execute(final ConsoleIO io, final HeadlessSession session, final List<String> args) {
         session.disconnect(args.isEmpty() ? "Leaving" : String.join(" ", args));
      }
   }

   private static class Say implements Command {
      @Override
      public String name() {
         return "say";
      }

      @Override
      public String usage() {
         return "say <message>";
      }

      @Override
      public String description() {
         return BotLang.get("cmd.say.desc");
      }

      @Override
      public void execute(final ConsoleIO io, final HeadlessSession session, final List<String> args) {
         if (args.isEmpty()) {
            io.printError(BotLang.get("bot.usage", this.usage()));
            return;
         }

         String message = String.join(" ", args);
         onClient(session, io, "say", () -> {
            if (session.client().getConnection() == null) {
               io.printError(BotLang.get("bot.not_connected"));
            } else {
               session.client().getConnection().sendChat(message);
            }
         });
      }
   }

   private static class ServerCommand implements Command {
      @Override
      public String name() {
         return "cmd";
      }

      @Override
      public String usage() {
         return "cmd <command...>";
      }

      @Override
      public String description() {
         return BotLang.get("cmd.cmd.desc");
      }

      @Override
      public void execute(final ConsoleIO io, final HeadlessSession session, final List<String> args) {
         if (args.isEmpty()) {
            io.printError(BotLang.get("bot.usage", this.usage()));
            return;
         }

         String command = String.join(" ", args);
         onClient(session, io, "cmd", () -> {
            if (session.client().getConnection() == null) {
               io.printError(BotLang.get("bot.not_connected"));
            } else {
               session.client().getConnection().sendCommand(command);
            }
         });
      }
   }

   private static class Slot implements Command {
      @Override
      public String name() {
         return "slot";
      }

      @Override
      public String usage() {
         return "slot <0-8>";
      }

      @Override
      public String description() {
         return BotLang.get("cmd.slot.desc");
      }

      @Override
      public void execute(final ConsoleIO io, final HeadlessSession session, final List<String> args) {
         if (args.size() != 1) {
            io.printError(BotLang.get("bot.usage", this.usage()));
            return;
         }

         int slot = Integer.parseInt(args.get(0));
         onClient(session, io, "slot", () -> {
            eml.action.BotActions.selectSlot(session.client(), slot);
            io.print(BotLang.get("cmd.slot.selected", slot));
         });
      }
   }

   private static class Jump implements Command {
      @Override
      public String name() {
         return "jump";
      }

      @Override
      public String usage() {
         return "jump";
      }

      @Override
      public String description() {
         return BotLang.get("cmd.jump.desc");
      }

      @Override
      public void execute(final ConsoleIO io, final HeadlessSession session, final List<String> args) {
         if (!args.isEmpty()) {
            io.printError(BotLang.get("bot.usage", this.usage()));
            return;
         }

         onClient(session, io, this.name(), () -> {
            if (session.client().player == null) {
               io.printError(BotLang.get("bot.not_connected"));
               return;
            }

            eml.action.BotControls controls = new eml.action.BotControls(session::clientNow);
            controls.setJump(true);
            top.mcocet.hlbot.plugins.BotScheduler.INSTANCE.runLater(null, () -> controls.setJump(false), 1);
            io.print(BotLang.get("cmd.jump.result"));
         });
      }
   }

   private abstract static class KeyToggle implements Command {
      private final String name;
      private final String descriptionKey;
      private final String resultKey;

      KeyToggle(final String name, final String descriptionKey, final String resultKey) {
         this.name = name;
         this.descriptionKey = descriptionKey;
         this.resultKey = resultKey;
      }

      @Override
      public String name() {
         return this.name;
      }

      @Override
      public String usage() {
         return this.name + " [on|off]";
      }

      @Override
      public String description() {
         return BotLang.get(this.descriptionKey);
      }

      @Override
      public void execute(final ConsoleIO io, final HeadlessSession session, final List<String> args) {
         Boolean enabled;
         if (args.isEmpty()) {
            enabled = true;
         } else if (args.size() == 1 && "on".equalsIgnoreCase(args.get(0))) {
            enabled = true;
         } else if (args.size() == 1 && "off".equalsIgnoreCase(args.get(0))) {
            enabled = false;
         } else {
            enabled = null;
         }

         if (enabled == null) {
            io.printError(BotLang.get("bot.usage", this.usage()));
            return;
         }

         boolean down = enabled;
         onClient(session, io, this.name(), () -> {
            if (session.client().player == null) {
               io.printError(BotLang.get("bot.not_connected"));
               return;
            }

            this.setKey(new eml.action.BotControls(session::clientNow), down);
            io.print(BotLang.get(this.resultKey, down));
         });
      }

      abstract void setKey(eml.action.BotControls controls, boolean down);
   }

   private static final class Sprint extends KeyToggle {
      Sprint() {
         super("sprint", "cmd.sprint.desc", "cmd.sprint.result");
      }

      @Override
      void setKey(final eml.action.BotControls controls, final boolean down) {
         controls.setSprint(down);
      }
   }

   private static final class Sneak extends KeyToggle {
      Sneak() {
         super("sneak", "cmd.sneak.desc", "cmd.sneak.result");
      }

      @Override
      void setKey(final eml.action.BotControls controls, final boolean down) {
         controls.setSneak(down);
      }
   }

   private static class Position implements Command {
      private final String name;

      Position(final String name) {
         this.name = name;
      }

      @Override
      public String name() {
         return this.name;
      }

      @Override
      public String usage() {
         return "pos | coords | position";
      }

      @Override
      public String description() {
         return BotLang.get("cmd.pos.desc");
      }

      @Override
      public void execute(final ConsoleIO io, final HeadlessSession session, final List<String> args) {
         if (!args.isEmpty()) {
            io.printError(BotLang.get("bot.usage", this.usage()));
            return;
         }

         onClient(session, io, this.name(), () -> {
            Minecraft client = session.client();
            if (client.player == null) {
               io.printError(BotLang.get("bot.not_connected"));
               return;
            }

            io.print(BotLang.get("cmd.pos.result",
               String.format(java.util.Locale.ROOT, "%.2f", client.player.getX()),
               String.format(java.util.Locale.ROOT, "%.2f", client.player.getY()),
               String.format(java.util.Locale.ROOT, "%.2f", client.player.getZ()),
               client.level == null ? "?" : String.valueOf(client.level.dimension().identifier()),
               String.format(java.util.Locale.ROOT, "%.1f", client.player.getYRot()),
               String.format(java.util.Locale.ROOT, "%.1f", client.player.getXRot())));
         });
      }
   }

   private static class Swing implements Command {
      @Override
      public String name() {
         return "swing";
      }

      @Override
      public String usage() {
         return "swing [off]";
      }

      @Override
      public String description() {
         return BotLang.get("cmd.swing.desc");
      }

      @Override
      public void execute(final ConsoleIO io, final HeadlessSession session, final List<String> args) {
         InteractionHand hand = args.contains("off") ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
         onClient(session, io, "swing", () -> eml.action.BotActions.swing(session.client(), hand));
      }
   }

   private static class Drop implements Command {
      @Override
      public String name() {
         return "drop";
      }

      @Override
      public String usage() {
         return "drop [all]";
      }

      @Override
      public String description() {
         return BotLang.get("cmd.drop.desc");
      }

      @Override
      public void execute(final ConsoleIO io, final HeadlessSession session, final List<String> args) {
         boolean entire = args.contains("all");
         onClient(session, io, "drop", () -> io.print(BotLang.get("cmd.drop.result", eml.action.BotActions.dropItem(session.client(), entire))));
      }
   }

   private static class Attack implements Command {
      @Override
      public String name() {
         return "attack";
      }

      @Override
      public String usage() {
         return "attack [stop]";
      }

      @Override
      public String description() {
         return BotLang.get("cmd.attack.desc");
      }

      @Override
      public void execute(final ConsoleIO io, final HeadlessSession session, final List<String> args) {
         if (args.contains("stop")) {
            onClient(session, io, "attack", () -> eml.action.BotActions.continueAttack(session.client(), false));
         } else {
            onClient(session, io, "attack", () -> io.print(BotLang.get("cmd.attack.result", eml.action.BotActions.startAttack(session.client()))));
         }
      }
   }

   private static class Look implements Command {
      @Override
      public String name() {
         return "look";
      }

      @Override
      public String usage() {
         return "look <yaw> <pitch>";
      }

      @Override
      public String description() {
         return BotLang.get("cmd.look.desc");
      }

      @Override
      public void execute(final ConsoleIO io, final HeadlessSession session, final List<String> args) {
         if (args.size() != 2) {
            io.printError(BotLang.get("bot.usage", this.usage()));
            return;
         }

         float yaw = Float.parseFloat(args.get(0));
         float pitch = Float.parseFloat(args.get(1));
         onClient(session, io, "look", () -> {
            if (session.client().player == null) {
               io.print("no player yet");
            } else {
               eml.action.BotActions.look(session.client(), yaw, pitch);
            }
         });
      }
   }

   private static class Mine implements Command {
      @Override
      public String name() {
         return "mine";
      }

      @Override
      public String usage() {
         return "mine <x> <y> <z>";
      }

      @Override
      public String description() {
         return BotLang.get("cmd.mine.desc");
      }

      @Override
      public void execute(final ConsoleIO io, final HeadlessSession session, final List<String> args) {
         if (args.size() != 3) {
            io.printError(BotLang.get("bot.usage", this.usage()));
            return;
         }

         BlockPos pos = new BlockPos(Integer.parseInt(args.get(0)), Integer.parseInt(args.get(1)), Integer.parseInt(args.get(2)));
         onClient(session, io, "mine", () -> io.print("mining=" + eml.action.BotActions.destroyBlock(session.client(), pos)));
      }
   }

   private static class GameMode implements Command {
      @Override
      public String name() {
         return "gamemode";
      }

      @Override
      public String usage() {
         return "gamemode <survival|creative|adventure|spectator>";
      }

      @Override
      public String description() {
         return BotLang.get("cmd.gamemode.desc");
      }

      @Override
      public void execute(final ConsoleIO io, final HeadlessSession session, final List<String> args) {
         if (args.isEmpty()) {
            io.printError(BotLang.get("bot.usage", this.usage()));
            return;
         }

         GameType mode = switch (args.get(0).toLowerCase(java.util.Locale.ROOT)) {
            case "creative", "1", "c" -> GameType.CREATIVE;
            case "adventure", "2", "a" -> GameType.ADVENTURE;
            case "spectator", "3", "sp" -> GameType.SPECTATOR;
            default -> GameType.SURVIVAL;
         };
         onClient(session, io, "gamemode", () -> {
            eml.action.BotActions.setLocalGameMode(session.client(), mode);
            io.print(BotLang.get("cmd.gamemode.result", mode.getName()));
         });
      }
   }

   /** Manual respawn for deaths that left the bot on the (headless) death screen. */
   private static class Respawn implements Command {
      @Override
      public String name() {
         return "respawn";
      }

      @Override
      public String usage() {
         return "respawn";
      }

      @Override
      public String description() {
         return BotLang.get("cmd.respawn.desc");
      }

      @Override
      public void execute(final ConsoleIO io, final HeadlessSession session, final List<String> args) {
         onClient(session, io, "respawn", () -> {
            net.minecraft.client.player.LocalPlayer player = session.client().player;
            if (player == null || session.client().getConnection() == null) {
               io.printError(BotLang.get("bot.not_connected"));
            } else if (!player.isDeadOrDying()) {
               io.printError(BotLang.get("bot.respawn.not_dead"));
            } else {
               player.respawn();
               io.print(BotLang.get("bot.respawn.sent"));
            }
         });
      }
   }

   private static class Msgs implements Command {
      @Override
      public String name() {
         return "msgs";
      }

      @Override
      public String usage() {
         return "msgs [count]";
      }

      @Override
      public String description() {
         return BotLang.get("cmd.msgs.desc");
      }

      @Override
      public void execute(final ConsoleIO io, final HeadlessSession session, final List<String> args) {
         int count = args.isEmpty() ? 10 : Integer.parseInt(args.get(0));
         List<eml.hooks.BotRuntime.ReceivedLine> history = eml.hooks.BotRuntime.history();
         int from = Math.max(0, history.size() - count);
         List<String> lines = new ArrayList<>();
         for (int i = from; i < history.size(); i++) {
            lines.add("  [" + java.time.Instant.ofEpochMilli(history.get(i).receivedAtMillis()) + "] " + AnsiComponents.render(history.get(i).content()));
         }

         io.printBlock(lines);
      }
   }

   private static class BotInfo implements Command {
      @Override
      public String name() {
         return "botinfo";
      }

      @Override
      public String usage() {
         return "botinfo";
      }

      @Override
      public String description() {
         return BotLang.get("cmd.botinfo.desc");
      }

      @Override
      public void execute(final ConsoleIO io, final HeadlessSession session, final List<String> args) {
         Minecraft client = session.client();
         List<String> lines = new ArrayList<>();

         lines.add("player     : " + client.getUser().getName() + " (" + client.getUser().getProfileId() + ")");

         Runtime runtime = Runtime.getRuntime();
         long used = runtime.totalMemory() - runtime.freeMemory();
         lines.add("memory     : " + formatBytes(used) + " used / " + formatBytes(runtime.totalMemory()) + " committed / " + formatBytes(runtime.maxMemory()) + " max");
         lines.addAll(memoryDetail(client));
         lines.add("cpu        : " + cpuInfo());

         File gameDir = session.gameDirectory();
         long size = directorySize(gameDir);
         lines.add("disk       : " + (size < 0L ? "?" : formatBytes(size)) + " in " + gameDir.getAbsolutePath());

         lines.addAll(serverInfo(client));
         // Undiscovered renderer coupling is the one failure mode this client cannot enumerate away, so the
         // count of packets the dispatch guard skipped belongs next to the memory and connection numbers.
         lines.addAll(eml.hooks.BotResilience.summary());
         io.printBlock(lines);
      }

      /**
       * Breaks the footprint into the parts that have DIFFERENT fixes, because "used heap" alone cannot say
       * whether the cost is live world data, uncollected garbage, or loaded classes:
       * <ul>
       *   <li>live heap (after a full GC) close to "used"  -> real data: chunks, entities, packet queues</li>
       *   <li>live heap much smaller than "used"            -> retention/GC policy: cap the heap, pick a
       *       collector; the growth is just garbage the JVM has not been asked to return</li>
       *   <li>RSS far above live heap with many classes     -> class volume (metaspace), not heap data</li>
       * </ul>
       * The explicit GC costs one pause (tens of ms here) and is worth it on a diagnostic command.
       */
      private static List<String> memoryDetail(final Minecraft client) {
         List<String> lines = new ArrayList<>();
         java.lang.management.MemoryMXBean memory = java.lang.management.ManagementFactory.getMemoryMXBean();

         System.gc();
         long live = memory.getHeapMemoryUsage().getUsed();
         lines.add("live heap  : " + formatBytes(live) + " (after full GC)");

         Runtime runtime = Runtime.getRuntime();
         lines.add("process    : " + formatBytes(processCommittedVirtual()) + " committed virtual / " + formatBytes(runtime.totalMemory()) + " heap committed");
      
         long metaspace = 0L;
         for (java.lang.management.MemoryPoolMXBean pool : java.lang.management.ManagementFactory.getMemoryPoolMXBeans()) {
            // MemoryType only distinguishes HEAP from NON_HEAP, so the metaspace pool is matched by name.
            if ("Metaspace".equals(pool.getName()) && pool.getUsage() != null) {
               metaspace += pool.getUsage().getUsed();
            }
         }
      
         lines.add("classes    : " + java.lang.management.ManagementFactory.getClassLoadingMXBean().getLoadedClassCount()
            + " loaded, " + formatBytes(metaspace) + " metaspace, " + Thread.activeCount() + " threads");
      
         net.minecraft.client.multiplayer.ClientLevel level = client.level;
         if (level != null) {
            int chunks = level.getChunkSource() instanceof net.minecraft.client.multiplayer.ClientChunkCache cache
               ? cache.getLoadedChunksCount()
               : -1;
            lines.add("world      : " + chunks + " chunks, " + level.getEntityCount() + " entities, sim="
               + level.getServerSimulationDistance() + ", requested view distance " + client.options.renderDistance().get());
         }
      
         return lines;
      }
      
      private static long processCommittedVirtual() {
         java.lang.management.OperatingSystemMXBean bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
         return bean instanceof com.sun.management.OperatingSystemMXBean sun ? sun.getCommittedVirtualMemorySize() : -1L;
      }

      private static List<String> serverInfo(final Minecraft client) {
         List<String> lines = new ArrayList<>();
         net.minecraft.client.multiplayer.ClientPacketListener listener = client.getConnection();
         if (listener == null) {
            net.minecraft.network.Connection pending = eml.action.BotActions.pendingConnection(client);
            lines.add("server     : " + (pending != null && pending.getRemoteAddress() != null ? "handshaking with " + pending.getRemoteAddress() : "not connected"));
            return lines;
         }

         net.minecraft.client.multiplayer.ServerData serverData = listener.getServerData();
         lines.add("server     : " + (serverData != null ? serverData.ip : "?") + " (" + listener.getConnection().getRemoteAddress() + ")");
         String brand = listener.serverBrand();
         if (brand != null) {
            lines.add("brand      : " + brand);
         }

         long ping = eml.hooks.BotRuntime.lastPingMs();
         lines.add("ping       : " + (ping < 0L ? "n/a" : ping + " ms")
            + ", encrypted=" + listener.getConnection().isEncrypted());
         lines.add("players    : " + listener.getOnlinePlayers().size() + " online");
         lines.add(String.format(
            java.util.Locale.ROOT,
            "packets    : up %.1f/s, down %.1f/s",
            listener.getConnection().getAverageSentPackets(),
            listener.getConnection().getAverageReceivedPackets()
         ));
         return lines;
      }

      private static String cpuInfo() {
         int cores = Runtime.getRuntime().availableProcessors();
         java.lang.management.OperatingSystemMXBean bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
         if (bean instanceof com.sun.management.OperatingSystemMXBean sun) {
            double process = sun.getProcessCpuLoad();
            double system = sun.getCpuLoad();
            return (process < 0.0 ? "?" : String.format(java.util.Locale.ROOT, "%.1f%%", process * 100.0))
               + " process, " + (system < 0.0 ? "?" : String.format(java.util.Locale.ROOT, "%.1f%%", system * 100.0))
               + " system, " + cores + " cores";
         }

         return cores + " cores (load n/a)";
      }

      private static long directorySize(final File dir) {
         if (!dir.isDirectory()) {
            return -1L;
         }

         try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.walk(dir.toPath())) {
            return stream.filter(java.nio.file.Files::isRegularFile).mapToLong(path -> path.toFile().length()).sum();
         } catch (Exception e) {
            return -1L;
         }
      }

      private static String formatBytes(final long bytes) {
         if (bytes < 1024L) {
            return bytes + " B";
         }

         String[] units = {"KB", "MB", "GB", "TB"};
         double value = bytes;
         int unit = -1;
         do {
            value /= 1024.0;
            unit++;
         } while (value >= 1024.0 && unit < units.length - 1);

         return String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unit]);
      }
   }

   /** JFR profiling: wraps the vanilla JvmProfiler pipeline (flightrecorder-config.jfc, dumps to debug/*.jfr). */
   private static class Jfr implements Command {
      @Override
      public String name() {
         return "jfr";
      }

      @Override
      public String usage() {
         return "jfr <start|stop>";
      }

      @Override
      public String description() {
         return BotLang.get("cmd.jfr.desc");
      }

      @Override
      public void execute(final ConsoleIO io, final HeadlessSession session, final List<String> args) {
         if (args.size() != 1) {
            io.printError(BotLang.get("bot.usage", this.usage()));
            return;
         }

         switch (args.get(0).toLowerCase(java.util.Locale.ROOT)) {
            case "start" -> {
               if (!JvmProfiler.INSTANCE.isAvailable()) {
                  io.printError(BotLang.get("bot.jfr.unavailable"));
               } else if (JvmProfiler.INSTANCE.isRunning()) {
                  io.printError(BotLang.get("bot.jfr.already_running"));
               } else if (JvmProfiler.INSTANCE.start(Environment.CLIENT)) {
                  io.print(BotLang.get("bot.jfr.started"));
               } else {
                  io.printError(BotLang.get("bot.jfr.start_failed"));
               }
            }
            case "stop" -> {
               if (!JvmProfiler.INSTANCE.isRunning()) {
                  io.printError(BotLang.get("bot.jfr.not_running"));
               } else {
                  java.nio.file.Path report = JvmProfiler.INSTANCE.stop();
                  io.print(BotLang.get("bot.jfr.stopped", report.toAbsolutePath()));
               }
            }
            default -> io.printError(BotLang.get("bot.usage", this.usage()));
         }
      }
   }

   /**
    * {@code plugins} and its subcommands. Listing is synchronous; the lifecycle verbs hand off to
    * {@link top.mcocet.hlbot.plugins.BotPlugins}, which runs them on the client thread (so a plugin's
    * {@code onEnable}/{@code onDisable} may touch the world), hence the "requested" acknowledgements.
    */
   private static class PluginList implements Command {
      @Override
      public String name() {
         return "plugins";
      }

      @Override
      public String usage() {
         return "plugins [reload|enable|disable|load|unload ...]";
      }

      @Override
      public String description() {
         return BotLang.get("cmd.plugins.desc");
      }

      @Override
      public void execute(final ConsoleIO io, final HeadlessSession session, final List<String> args) {
         top.mcocet.hlbot.plugins.PluginManager manager = top.mcocet.hlbot.plugins.BotPlugins.manager();
         if (manager == null) {
            io.print(BotLang.get("bot.no_plugins"));
            return;
         }

         if (args.isEmpty()) {
            list(io, manager);
            return;
         }

         String action = args.get(0).toLowerCase(java.util.Locale.ROOT);
         List<String> rest = args.subList(1, args.size());
         switch (action) {
            case "reload" -> {
               if (rest.isEmpty()) {
                  io.print("[plugins] reloading all...");
                  top.mcocet.hlbot.plugins.BotPlugins.reloadAll();
               } else {
                  io.print("[plugins] reloading " + rest.get(0) + "...");
                  top.mcocet.hlbot.plugins.BotPlugins.reloadPlugin(rest.get(0));
               }
            }
            case "enable" -> {
               if (require(io, rest)) {
                  io.print("[plugins] enabling " + rest.get(0) + "...");
                  top.mcocet.hlbot.plugins.BotPlugins.enablePlugin(rest.get(0));
               }
            }
            case "disable" -> {
               if (require(io, rest)) {
                  io.print("[plugins] disabling " + rest.get(0) + "...");
                  top.mcocet.hlbot.plugins.BotPlugins.disablePlugin(rest.get(0));
               }
            }
            case "unload" -> {
               if (require(io, rest)) {
                  io.print("[plugins] unloading " + rest.get(0) + "...");
                  top.mcocet.hlbot.plugins.BotPlugins.unloadPlugin(rest.get(0));
               }
            }
            case "load" -> {
               if (require(io, rest)) {
                  File jar = new File(session.gameDirectory(), "plugins" + File.separator + rest.get(0));
                  if (!jar.isFile()) {
                     io.printError("[plugins] no such jar: " + jar.getPath());
                     return;
                  }

                  io.print("[plugins] loading " + jar.getName() + "...");
                  top.mcocet.hlbot.plugins.BotPlugins.loadPlugin(jar);
               }
            }
            default -> io.printError(BotLang.get("bot.usage", this.usage()));
         }
      }

      private static void list(final ConsoleIO io, final top.mcocet.hlbot.plugins.PluginManager manager) {
         if (manager.getPlugins().isEmpty()) {
            io.print(BotLang.get("bot.no_plugins"));
            return;
         }

         List<String> lines = new ArrayList<>();
         int faulted = 0;
         for (top.mcocet.hlbot.plugins.Plugin plugin : manager.getPlugins()) {
            if (manager.fault(plugin.getName()) != null) {
               faulted++;
            }
         }

         lines.add("[plugins] " + manager.getPlugins().size() + " loaded, " + faulted + " failed, connected=" + manager.isConnected());
         for (top.mcocet.hlbot.plugins.Plugin plugin : manager.getPlugins()) {
            top.mcocet.hlbot.plugins.PluginManager.Fault fault = manager.fault(plugin.getName());
            lines.add("  " + plugin.getDescription().getFullName() + " [" + plugin.getState() + "]"
               + (fault == null ? "" : " failed " + fault.summary()));
         }

         if (faulted > 0) {
            lines.add("  use 'plugins enable <name>' to clear a failure and retry");
         }

         io.printBlock(lines);
      }

      private static boolean require(final ConsoleIO io, final List<String> rest) {
         if (rest.isEmpty()) {
            io.printError("[plugins] a plugin name is required");
            return false;
         }

         return true;
      }
   }

   /**
    * Microsoft account login (device-code flow). The flow runs on a background thread so the console stays
    * usable while the user approves the sign-in in a browser. The result is saved to {@code session.yml} and
    * takes effect on the next start; the running client cannot swap its identity mid-session.
    */
   private static class Login implements Command {
      @Override
      public String name() {
         return "login";
      }

      @Override
      public String usage() {
         return "login";
      }

      @Override
      public String description() {
         return BotLang.get("cmd.login.desc");
      }

      @Override
      public void execute(final ConsoleIO io, final HeadlessSession session, final List<String> args) {
         if (!args.isEmpty()) {
            io.printError(BotLang.get("bot.usage", this.usage()));
            return;
         }

         Thread worker = new Thread(() -> {
            try {
               MicrosoftSession account = MicrosoftAuth.deviceCodeLogin(io, new File(session.gameDirectory(), "session.yml"));
               io.print(BotLang.get("bot.mslogin.restart_required", account.name()));
            } catch (MicrosoftAuthException e) {
               io.printError(BotLang.get("bot.mslogin.failed", e.getMessage()));
            }
         }, "Microsoft login");
         worker.setDaemon(true);
         worker.start();
      }
   }

   /**
    * Ends the session. Registered three times - {@code quit}, {@code end} and {@code stop} - because that is
    * how people type it. Navigation keeps its own {@code goto stop}, so a bare {@code stop} only ever means
    * "exit the bot".
    */
   private static class Quit implements Command {
      private final String name;

      private Quit(final String name) {
         this.name = name;
      }

      @Override
      public String name() {
         return this.name;
      }

      @Override
      public String usage() {
         return "quit | end | stop";
      }

      @Override
      public String description() {
         return BotLang.get("cmd.quit.desc");
      }

      @Override
      public void execute(final ConsoleIO io, final HeadlessSession session, final List<String> args) {
         session.shutdown();
      }
   }
}
