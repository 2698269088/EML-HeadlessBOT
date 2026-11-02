package top.mcocet.hlbot.plugins;

import net.minecraft.client.Minecraft;
import net.minecraft.client.headless.Commands;
import net.minecraft.client.headless.ConsoleIO;
import net.minecraft.client.headless.HeadlessSession;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import eml.action.BotControls;
import eml.action.BotInventory;
import eml.action.BotPlayer;
import eml.action.BotWorld;
import top.mcocet.hlbot.plugins.event.Listener;

/**
 * The facade through which plugins interact with the bot. One instance is shared by all
 * plugins; grab it in {@code onEnable()} via {@link Plugin#getApi()}.
 *
 * <h2>Threading contract</h2>
 *
 * <p>Events fired by the bot arrive on the client game thread, so inside an event handler
 * you may read the level, the player and the connection directly. Everything else —
 * console commands, {@link #sendChat}, your own threads — must hop onto the client thread
 * with {@link #runOnClient(Runnable)} before touching game state.
 */
public final class BotApi {
   private final HeadlessSession session;
   private final ConsoleIO console;
   private final BotWorld world;
   private final BotControls controls;
   private final BotInventory inventory;
   private PluginManager pluginManager;

   BotApi(final HeadlessSession session, final ConsoleIO console) {
      this.session = session;
      this.console = console;
      this.world = new BotWorld(session::currentLevel);
      this.controls = new BotControls(session::clientNow);
      this.inventory = new BotInventory(session::clientNow);
   }

   /** Wiring done by {@link BotPlugins#init}; never call this from a plugin. */
   void attach(final PluginManager pluginManager) {
      this.pluginManager = pluginManager;
   }

   // ---------- identity & connection ----------

   public String username() {
      return this.session.username();
   }

   public boolean isConnected() {
      return this.session.isConnected();
   }

   /** Opens a multiplayer connection. Safe to call from any thread. */
   public void connect(final String host, final int port) {
      this.session.connect(host, port);
   }

   public void disconnect(final String reason) {
      this.session.disconnect(reason);
   }

   /** Sends a chat message to the server. Safe to call from any thread. */
   public void sendChat(final String message) {
      this.session.sendChat(message);
   }

   /** Sends a server command (without the leading slash). Safe to call from any thread. */
   public void sendCommand(final String command) {
      this.session.sendCommand(command);
   }

   // ---------- client access ----------

   /**
    * Schedules {@code task} on the client game thread. All reads and mutations of world
    * state (level, player, inventory, ...) must happen there.
    */
   public void runOnClient(final Runnable task) {
      this.session.client().execute(task);
   }

   /**
    * Non-blocking variant of {@link #client()}: the engine once it exists, otherwise {@code null}. Use it
    * from per-tick code and teardown paths, where waiting for a client that is shutting down would hang.
    */
   public Minecraft clientNow() {
      return this.session.clientNow();
   }

   /**
    * The local player, or {@code null} before login. Read it on the client thread (or inside an event
    * handler, which already runs there).
    */
   public net.minecraft.client.player.LocalPlayer player() {
      Minecraft client = this.session.clientNow();
      return client == null ? null : client.player;
   }

   /**
    * Snapshot of the local player as plain values - the type plugins should use,
    * since it needs no {@code net.minecraft} import.
    */
   public BotPlayer self() {
      net.minecraft.client.player.LocalPlayer player = this.player();
      return player == null ? null : BotPlayer.of(player);
   }

   /**
    * Cached terrain facts (flags + hardness per block). The cache survives chunk unloads, so a long path
    * does not dissolve behind the bot; invalidate positions from {@code BlockUpdatedEvent}.
    */
   public BotWorld world() {
      return this.world;
   }

   /** Vanilla input driving: keys, smooth aiming, digging and placing. */
   public BotControls controls() {
      return this.controls;
   }

   /**
    * Container and inventory control: the click protocol, stack moves and recipe-book crafting.
    * Client thread only - see the threading contract at the top of this class.
    */
   public BotInventory inventory() {
      return this.inventory;
   }

   /**
    * Inbound packets the dispatch guard skipped because a vanilla handler hit a renderer, audio or GUI
    * object the headless client does not have. Zero is the normal value; a number that keeps growing means a
    * coupling site nobody has patched yet, and the world model may be missing those updates.
    */
   public long skippedPackets() {
      return eml.hooks.BotResilience.skippedCount();
   }

   // ---------- monitoring ----------

   /**
    * The server's player list as plain snapshots, sorted by name. Empty when not in play.
    *
    * <p>Safe to call from any thread: the list belongs to the client thread, so the read is posted there and
    * answered with a bounded wait. A snapshot is returned rather than the live objects, which is why a caller
    * never holds a reference to something the client thread is still mutating.
    */
   public List<PlayerSnapshot> onlinePlayers() {
      return this.onClientThread(() -> {
         Minecraft client = this.session.clientNow();
         ClientPacketListener connection = client == null ? null : client.getConnection();

         if (connection == null) {
            return List.<PlayerSnapshot>of();
         }

         String self = client.getUser() == null ? "" : client.getUser().getName();
         List<PlayerSnapshot> snapshots = new ArrayList<>();

         for (PlayerInfo info : connection.getOnlinePlayers()) {
            if (info == null || info.getProfile() == null) {
               continue;
            }

            String name = info.getProfile().name();
            String gameMode = info.gameMode == null ? "unknown" : info.gameMode.getName();
            snapshots.add(new PlayerSnapshot(name, String.valueOf(info.getProfile().id()), info.latency, gameMode, name.equalsIgnoreCase(self)));
         }

         snapshots.sort(Comparator.comparing(PlayerSnapshot::name, String.CASE_INSENSITIVE_ORDER));
         return snapshots;
      }, List.of());
   }

   /**
    * Process and session counters as plain values. Safe to call from any thread: nothing here needs the
    * client thread except the tick and ping clocks, which are already volatile statics.
    */
   public BotMetrics metrics() {
      Runtime runtime = Runtime.getRuntime();
      long heapUsed = runtime.totalMemory() - runtime.freeMemory();
      double processCpu = -1.0;
      double systemCpu = -1.0;

      try {
         java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();

         if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            processCpu = sun.getProcessCpuLoad();
            systemCpu = sun.getCpuLoad();
         }
      } catch (Throwable unavailable) {
         // CPU load is optional: a restricted JVM may not expose com.sun.management.
      }

      return new BotMetrics(
         heapUsed,
         runtime.totalMemory(),
         runtime.maxMemory(),
         ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed(),
         ManagementFactory.getThreadMXBean().getThreadCount(),
         processCpu,
         systemCpu,
         ManagementFactory.getRuntimeMXBean().getUptime(),
         eml.hooks.BotRuntime.tickCount(),
         eml.hooks.BotRuntime.lastPingMs(),
         ManagementFactory.getClassLoadingMXBean().getLoadedClassCount(),
         eml.hooks.BotResilience.skippedCount()
      );
   }

   /**
    * Console lines logged after {@code afterSeq}, oldest first. Safe to call from any thread.
    *
    * <p>The first call installs the capture appender, so a bot whose plugins never ask pays nothing.
    *
    * @param afterSeq the last {@link ConsoleLine#seq()} the caller rendered, or {@code 0} for the whole buffer
    * @param limit    maximum lines; the newest are returned when more are pending
    */
   public List<ConsoleLine> consoleSince(final int afterSeq, final int limit) {
      eml.hooks.ConsoleCapture.install();
      return eml.hooks.ConsoleCapture.since(afterSeq, limit);
   }

   /** Highest console sequence issued so far, for a client that wants to start from "now". */
   public int consoleSequence() {
      return eml.hooks.ConsoleCapture.sequence();
   }

   /** The address of the server this bot is talking to, or an empty string before the first connect. */
   public String serverAddress() {
      String host = eml.hooks.BotRuntime.targetHost();

      if (host == null || host.isEmpty()) {
         return "";
      }

      return host + ":" + eml.hooks.BotRuntime.targetPort();
   }

   /**
    * Runs a reader on the client thread and answers with a bounded wait, falling back when the client is
    * gone or busy. The threading contract at the top of this class makes every caller do this by hand; the
    * monitoring methods above share it instead.
    */
   private <T> T onClientThread(final Supplier<T> reader, final T fallback) {
      Minecraft client = this.session.clientNow();

      if (client == null) {
         return fallback;
      }

      if (client.isSameThread()) {
         try {
            return reader.get();
         } catch (Throwable failed) {
            return fallback;
         }
      }

      CompletableFuture<T> answer = new CompletableFuture<>();
      client.execute(() -> {
         try {
            answer.complete(reader.get());
         } catch (Throwable failed) {
            answer.completeExceptionally(failed);
         }
      });

      try {
         return answer.get(2L, TimeUnit.SECONDS);
      } catch (Exception timeoutOrFailure) {
         return fallback;
      }
   }

   /** Block state at a position; air when no level is loaded. Client thread only. */
   public net.minecraft.world.level.block.state.BlockState blockAt(final int x, final int y, final int z) {
      Minecraft client = this.session.clientNow();
      if (client == null || client.level == null) {
         return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
      }

      return client.level.getBlockState(new net.minecraft.core.BlockPos(x, y, z));
   }

   /** True when the chunk covering those block coordinates has already been received. Client thread only. */
   public boolean isChunkLoaded(final int blockX, final int blockZ) {
      Minecraft client = this.session.clientNow();
      return client != null && client.level != null
         && client.level.getChunkSource().hasChunk(blockX >> 4, blockZ >> 4);
   }

   /**
    * Asks the client to report a larger view distance to the server so long-range pathfinding sees
    * further than the join-time radius. The server may still cap it; chunks stream in as they arrive.
    *
    * <p>With {@code viewDistance: auto} (the default) this is an override for the current connection: the
    * next login adopts the server's radius again. Ask for what the plan needs and re-apply it after a
    * reconnect if it still matters.
    */
   public void setViewDistance(final int chunks) {
      this.session.setViewDistance(chunks);
   }

   /** The view distance currently reported to the server. */
   public int viewDistance() {
      return this.session.viewDistance();
   }

   /** Client ticks since startup; the same clock the plugin scheduler uses. */
   public long tickCount() {
      return eml.hooks.BotRuntime.tickCount();
   }

   /**
    * The tick-driven scheduler. Pass the owning {@link Plugin} to each call so its tasks are cancelled
    * automatically on disable/unload; {@link Plugin#scheduler()} is the same instance from the plugin side.
    */
   public BotScheduler scheduler() {
      return BotScheduler.INSTANCE;
   }

   /**
    * The headless Minecraft engine. Escape hatch for advanced plugins — prefer the
    * convenience methods above, and always respect the threading contract: field reads
    * like {@code client.level} / {@code client.player} are only safe on the client
    * thread (or while an event handler is running).
    */
   public Minecraft client() {
      return this.session.client();
   }

   /** Writes a line to the bot console, prefixed with the calling context. */
   public void print(final String message) {
      this.console.print(message);
   }

   /** Writes an error line (bright red) to the bot console. */
   public void printError(final String message) {
      this.console.printError(message);
   }

   /** Writes several lines as one block (status output, route summaries, ...). */
   public void printBlock(final List<String> lines) {
      this.console.printBlock(lines);
   }

   /**
    * The bot's working directory. Keep any plugin file under here (or under
    * {@link Plugin#getDataFolder()}): the bot promises to read and write nothing outside the game
    * directory, so a plugin that reaches elsewhere breaks the cross-platform contract.
    */
   public java.io.File gameDirectory() {
      return this.session.gameDirectory();
   }

   // ---------- plugin lifecycle control (posted to the client thread) ----------

   /** Re-reads one plugin jar from disk, re-enabling it if a server is currently connected. */
   public void reloadPlugin(final String name) {
      BotPlugins.reloadPlugin(name);
   }

   /** Unloads every plugin and re-scans the whole {@code plugins/} directory. */
   public void reloadPlugins() {
      BotPlugins.reloadAll();
   }

   /** True when the client has entered a world, i.e. plugins are in the ENABLED phase. */
   public boolean isServerConnected() {
      PluginManager manager = this.pluginManager;
      return manager != null && manager.isConnected();
   }

   // ---------- extension points ----------

   /**
    * Registers a console command (typed at the bot console, same namespace as
    * {@code connect}, {@code say}, ...). Automatically removed when the plugin disables.
    */
   public void registerCommand(final Commands.Command command, final Plugin plugin) {
      this.pluginManager.registerCommand(command, plugin);
   }

   /**
    * Registers a plugin command without game types in its signature; {@link BotCommand} is the shape a
    * plugin can implement without importing {@code net.minecraft}. Fault isolation is unchanged: a throw
    * inside {@code execute} disables the owning plugin.
    */
   public void registerCommand(final BotCommand command, final Plugin plugin) {
      this.pluginManager.registerCommand(new Commands.Command() {
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
            return command.description();
         }

         @Override
         public void execute(final ConsoleIO io, final HeadlessSession session, final List<String> args) {
            command.execute(args);
         }
      }, plugin);
   }

   /** Registers all {@code @EventHandler} methods of {@code listener}. */
   public void registerEvents(final Listener listener, final Plugin plugin) {
      this.pluginManager.registerEvents(listener, plugin);
   }

   public PluginManager plugins() {
      return this.pluginManager;
   }
}
