package eml.hooks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.slf4j.Logger;

/**
 * Process-wide runtime state for the headless bot, and the landing point for patched vanilla code.
 *
 * <p>The ASM route never adds fields to vanilla classes: the tick pacer deadline, the respawn
 * countdown and the chat history live here as statics. That is safe because a headless bot is a
 * single-instance process (one {@link Minecraft}, one client thread); the class is documented as
 * such rather than made thread-safe. Methods reached from vanilla code already run on the client
 * thread.
 */
public final class BotRuntime {
   private static final Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
   private static final int HISTORY_LIMIT = 200;
   private static final long TICK_NANOS = 50_000_000L;

   /**
    * Shared {@code BooleanSupplier} handed to {@code Level.tick}. Donor bodies cannot build lambdas
    * (their synthetic implementation method would stay in the donor class and be unreachable after
    * the copy), so every functional-interface value used by a donor lives here as a normal object.
    */
   private static final java.util.function.BooleanSupplier ALWAYS_TRUE = () -> true;

   /** Logger for donor bodies: {@code Minecraft.LOGGER} is private, so donors go through this. */
   public static Logger log() {
      return LOGGER;
   }

   public static java.util.function.BooleanSupplier alwaysTrue() {
      return ALWAYS_TRUE;
   }

   // ---- tick pacer ----
   private static long nextTickNanos;
   private static long tickCount;
   private static long lastRateSampleNanos;
   private static long ticksSinceSample;
   private static volatile boolean shutdownRequested;

   // ---- delayed respawn ----
   private static int respawnCountdownTicks = -1;

   // ---- plugin lifecycle edge detection ----
   // The client thread notices the play connection appearing/vanishing here and drives the four-phase
   // plugin lifecycle (enable on the first world, disable on every loss). It is the single source of
   // truth for connect/disconnect, so it also catches a deliberate `disconnect` that autoReconnect skips.
   private static boolean wasInPlay;

   // ---- round-trip latency (vanilla keeps its sample in a private debug chart, so we measure our own) ----
   private static volatile long lastPingMs = -1L;
   private static long nextPingTick;

   // ---- the radius the server named in its login packet, or -1 before the first login ----
   // Vanilla stores it inside ClientPacketListener (package-private), so the login donor reports it here.
   private static volatile int serverViewDistance = -1;

   // ---- console output sink ----
   private static final Deque<ReceivedLine> HISTORY = new ArrayDeque<>();
   private static volatile Consumer<ReceivedLine> sink;

   private BotRuntime() {
   }

   /** One console-visible chat line, already decorated by the vanilla chat machinery. */
   public record ReceivedLine(boolean fromServer, String sender, Component content, long receivedAtMillis) {
   }

   // ==================== main loop support (called from patched Minecraft code) ====================

   /**
    * Headless pacing: sleep until 50ms after the previous slot instead of presenting a frame.
    *
    * <p>The deadline is anchored to the previous iteration rather than to an absolute tick schedule.
    * Absolute scheduling creates a self-sustaining limit cycle: a burst of catch-up ticks pushes the
    * target as far ahead as the following sleep lasts, so the client keeps freezing for a multiple of
    * 50ms forever (measured once as ~300ms stalls every ~300ms while moving). Relative anchoring
    * decays any burst back into 50ms slots within one iteration; {@code max(...)} re-anchors after a
    * genuine long stall so the loop never spins without sleeping.
    *
    * @return true when the sleep was interrupted and the client should stop
    */
   public static boolean tickRateLimit() {
      long now = System.nanoTime();
      if (nextTickNanos == 0L) {
         nextTickNanos = now;
      }

      long sleepNanos = nextTickNanos - now;
      if (sleepNanos > 0L) {
         try {
            Thread.sleep(sleepNanos / 1_000_000L, (int)(sleepNanos % 1_000_000L));
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            shutdownRequested = true;
            return true;
         }

         now = System.nanoTime();
      }

      nextTickNanos = Math.max(nextTickNanos + TICK_NANOS, now - TICK_NANOS);
      tickCount++;
      return shutdownRequested;
   }

   /** Per-tick bot work that has no field home in vanilla: lifecycle, metrics, respawn, plugin tick event. */
   public static void onClientTick(final Minecraft client) {
      connectionEdge(client);

      ticksSinceSample++;
      long now = System.nanoTime();
      if (now - lastRateSampleNanos >= 1_000_000_000L) {
         lastRateSampleNanos = now;
         // Vanilla reports FPS here; headless has none, so the real tick rate is the useful metric.
         net.minecraft.util.profiling.jfr.JvmProfiler.INSTANCE.onClientTick((int)ticksSinceSample);
         ticksSinceSample = 0;
      }

      if (respawnCountdownTicks > 0) {
         if (client.player == null) {
            respawnCountdownTicks = -1;
         } else if (--respawnCountdownTicks == 0) {
            client.player.respawn();
         }
      }

      top.mcocet.hlbot.plugins.BotPlugins.fire(top.mcocet.hlbot.plugins.events.tick.ClientTickEvent.INSTANCE);
      // Tick-driven scheduler: fires sync tasks here on the client thread and dispatches async ones to
      // the worker pool. Runs after ClientTickEvent so a plugin's per-tick logic and timers share order.
      top.mcocet.hlbot.plugins.BotScheduler.INSTANCE.pump();
      autoReconnect(client);
      pingProbe(client);
      heartbeat(client);
   }

   /**
    * Detects the play connection appearing or vanishing and moves the plugin system with it, on the
    * client thread. Going IN: enable every loaded plugin first, then fire {@link
    * top.mcocet.hlbot.plugins.events.connection.LoginSuccessEvent} so the freshly enabled listeners see
    * the join. Going OUT: fire {@link top.mcocet.hlbot.plugins.events.connection.DisconnectEvent} while
    * plugins are still enabled (so they can react), then disable them.
    */
   private static void connectionEdge(final Minecraft client) {
      boolean inPlay = client.getConnection() != null;
      if (inPlay == wasInPlay) {
         return;
      }

      wasInPlay = inPlay;

      if (inPlay) {
         // Every vanilla class the bot needs has been loaded by the time it enters play, so this is the first
         // moment a "loaded before the agent" answer is meaningful. It reports once per process.
         eml.agent.EmlAgent.auditOnce();
         // Before the plugins are enabled, so onEnable already sees the radius this connection will use.
         adoptServerViewDistance(client);
         top.mcocet.hlbot.plugins.BotPlugins.onServerConnected();
         String host = "";
         int port = 0;
         net.minecraft.network.Connection conn = client.getConnection().getConnection();
         if (conn != null && conn.getRemoteAddress() instanceof java.net.InetSocketAddress remote) {
            host = remote.getHostString();
            port = remote.getPort();
         }

         top.mcocet.hlbot.plugins.BotPlugins.fire(
            new top.mcocet.hlbot.plugins.events.connection.LoginSuccessEvent(host, port, client.getUser().getName()));
      } else {
         // A pack id is per connection; a reconnect must answer packs afresh.
         BotResourcePacks.clear();
         top.mcocet.hlbot.plugins.BotPlugins.fire(new top.mcocet.hlbot.plugins.events.connection.DisconnectEvent(
            lastDropReason == null ? Component.literal("Connection closed") : lastDropReason));
         top.mcocet.hlbot.plugins.BotPlugins.onServerDisconnected();
      }
   }

   /**
    * {@code viewDistance: auto} - take the radius the server named in its login packet and make it the
    * client's own. Vanilla caps the effective distance at the server's value anyway, but only the reported
    * one decides how much the server streams, so adopting it is what "follow the server" means in practice.
    * A fixed radius is left exactly as it was applied at startup (or by a plugin).
    */
   private static void adoptServerViewDistance(final Minecraft client) {
      net.minecraft.client.headless.BotConfig config = net.minecraft.client.headless.BotConfig.instance();
      int server = serverViewDistance;

      if (config == null || !config.viewDistance().auto() || server <= 0) {
         return;
      }

      int applied = eml.action.BotActions.applyViewDistance(client, server);
      log().info("[bot] view distance: auto, following the server ({} chunk(s))", applied);
   }

   // ==================== connection keeping ====================
   //
   // The bot wants to be connected; that intent is recorded by HeadlessSession, never inferred. When the
   // play connection disappears on its own, the first tick that notices it announces a DisconnectEvent once
   // (so plugins see exactly one event per drop, on the client thread, never on a Netty thread) and the same
   // state machine then retries on the schedule from bot.yml.

   private static volatile boolean wantsConnection;
   private static volatile boolean announcedDrop;
   private static volatile String targetHost = "";
   private static volatile int targetPort = 25565;
   private static volatile int reconnectCountdown = -1;
   private static volatile int reconnectAttempts;
   private static volatile Runnable reconnectHandler;
   private static volatile net.minecraft.network.chat.Component lastDropReason;

   /** Records where to go back to, and arms the drop detection for this connection. */
   public static void markConnectionIntent(final String host, final int port) {
      targetHost = host;
      targetPort = port;
      wantsConnection = true;
      announcedDrop = false;
      reconnectCountdown = -1;
   }

   /** A deliberate 'disconnect' or shutdown: retries stop until the next explicit connect. */
   public static void markUserDisconnect() {
      wantsConnection = false;
      announcedDrop = false;
      reconnectCountdown = -1;
      reconnectAttempts = 0;
   }

   public static void setReconnectHandler(final Runnable handler) {
      reconnectHandler = handler;
   }

   public static String targetHost() {
      return targetHost;
   }

   public static int targetPort() {
      return targetPort;
   }

   /** Filled in by the patched connection hooks, which see the real reason text. */
   public static void noteDropReason(final net.minecraft.network.chat.Component reason) {
      if (reason != null) {
         lastDropReason = reason;
      }
   }

   private static void autoReconnect(final Minecraft client) {
      if (!wantsConnection || reconnectHandler == null) {
         return;
      }

      boolean online = client.getConnection() != null || eml.action.BotActions.pendingConnection(client) != null;

      if (online) {
         announcedDrop = false;
         reconnectAttempts = 0;
         reconnectCountdown = -1;
         lastDropReason = null;
         return;
      }

      net.minecraft.client.headless.BotConfig config = net.minecraft.client.headless.BotConfig.instance();

      if (!announcedDrop) {
         announcedDrop = true;
         // The DisconnectEvent itself is now fired by connectionEdge (which also covers a deliberate
         // disconnect where wantsConnection is already false); this path only schedules the retry.

         if (config == null || !config.autoReconnect()) {
            wantsConnection = false;
            return;
         }

         reconnectCountdown = Math.max(1, config.reconnectDelaySeconds()) * 20;
         log().info("[bot] connection lost; retrying in {}s", config.reconnectDelaySeconds());
         return;
      }

      if (reconnectCountdown < 0) {
         return;
      }

      if (--reconnectCountdown > 0) {
         return;
      }

      int limit = config == null ? 0 : config.reconnectAttempts();

      if (limit > 0 && ++reconnectAttempts > limit) {
         log().warn("[bot] giving up after {} reconnect attempts", limit);
         wantsConnection = false;
         reconnectCountdown = -1;
         return;
      }

      reconnectCountdown = -1;
      final String host = targetHost;
      final int port = targetPort;
      final Runnable handler = reconnectHandler;
      // The handler re-enters the connect path, which posts to this same thread: run it after the tick.
      client.execute(handler::run);
      log().info("[bot] reconnecting to {}:{}", host, port);
   }

   /**
    * The debug overlay, normally owned by {@code Gui}. {@code ClientPacketListener}'s constructor asks
    * {@code Minecraft.getDebugOverlay()} for it, so a headless client needs an instance even though it never
    * draws one: the class is a plain registry of toggles and chart entries whose constructor touches nothing
    * graphical. Created lazily because it needs the client, which does not exist yet at patch time.
    */
   public static net.minecraft.client.gui.components.DebugScreenOverlay debugOverlay(final net.minecraft.client.Minecraft client) {
      if (debugOverlay == null) {
         debugOverlay = new net.minecraft.client.gui.components.DebugScreenOverlay(client);
      }

      return debugOverlay;
   }

   private static net.minecraft.client.gui.components.DebugScreenOverlay debugOverlay;

   /**
    * Periodic proof of life, off unless {@code debug.ticks} is on. When a login stalls, the difference
    * between "the game thread stopped" and "the game thread is running with no connection" is decided here,
    * and neither shows up in any other log line.
    */
   private static void heartbeat(final Minecraft client) {
      if (!eml.util.Debug.ticks() || tickCount % 20 != 0) {
         return;
      }

      try {
         log().info(
            "[debug] tick={} tps={} connection={} pending={} level={} player={}",
            tickCount,
            ticksSinceSample,
            client.getConnection() == null ? "none" : "in play",
            eml.action.BotActions.pendingConnection(client) == null ? "none" : "opening",
            client.level == null ? "none" : String.valueOf(client.level.dimension()),
            client.player == null ? "none" : client.player.getName().getString()
         );
      } catch (Throwable unavailable) {
         // Diagnostics never break the tick they observe.
      }
   }

   /**
    * One latency probe per second. The vanilla packet echoes our timestamp back, so the pong gives a
    * genuine round-trip time ({@link #recordPingMs}); probes are also accepted from whatever else pings.
    */
   private static void pingProbe(final Minecraft client) {
      if (tickCount < nextPingTick) {
         return;
      }

      nextPingTick = tickCount + 20L;
      net.minecraft.client.multiplayer.ClientPacketListener connection = client.getConnection();
      if (connection == null) {
         return;
      }

      connection.send(new net.minecraft.network.protocol.ping.ServerboundPingRequestPacket(Util.getMillis()));
   }

   /** Records a pong round trip; {@code ClientboundPongResponsePacket} carries the request timestamp. */
   public static void recordPingMs(final long millis) {
      if (millis >= 0L && millis <= 60_000L) {
         lastPingMs = millis;
      }
   }

   public static long lastPingMs() {
      return lastPingMs;
   }

   /** Called from the patched {@code ClientPacketListener.handleLogin}: the radius the server allows. */
   public static void noteServerViewDistance(final int chunks) {
      serverViewDistance = chunks;
   }

   /** The server's own view distance from the last login packet, or -1 before the first login. */
   public static int serverViewDistance() {
      return serverViewDistance;
   }

   public static long tickCount() {
      return tickCount;
   }

   /** Clears the pacer schedule and any pending shutdown request; called when the client thread starts. */
   public static void resetPacer() {
      nextTickNanos = 0L;
      lastRateSampleNanos = System.nanoTime();
      shutdownRequested = false;
   }

   public static void requestShutdown() {
      shutdownRequested = true;
   }

   public static boolean shutdownRequested() {
      return shutdownRequested;
   }

   // ==================== respawn ====================

   /** Schedules an automatic respawn; the vanilla death screen waits 20 ticks before allowing one. */
   public static void scheduleRespawn(final int delayTicks) {
      respawnCountdownTicks = delayTicks;
   }

   public static boolean respawnScheduled() {
      return respawnCountdownTicks > 0;
   }

   // ==================== chat ====================

   /** Installs the console printer; lines received before that are logged and kept in history. */
   public static void setSink(final Consumer<ReceivedLine> consumer) {
      sink = consumer;
   }

   public static void onChatReceived(final boolean fromServer, final String sender, final Component content) {
      ReceivedLine line = new ReceivedLine(fromServer, sender, content, System.currentTimeMillis());
      synchronized (HISTORY) {
         HISTORY.addLast(line);
         while (HISTORY.size() > HISTORY_LIMIT) {
            HISTORY.removeFirst();
         }
      }

      Consumer<ReceivedLine> current = sink;
      if (current != null) {
         current.accept(line);
      } else {
         LOGGER.info("[chat/{}] {}", sender, content.getString());
      }
   }

   public static List<ReceivedLine> history() {
      synchronized (HISTORY) {
         return new ArrayList<>(HISTORY);
      }
   }
}
