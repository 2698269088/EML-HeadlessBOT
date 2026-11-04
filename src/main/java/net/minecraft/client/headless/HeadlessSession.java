package net.minecraft.client.headless;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import eml.action.BotActions;
import eml.hooks.BotRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.main.GameConfig;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.EventLoopGroupHolder;
import org.slf4j.Logger;

/**
 * Owns the headless {@link Minecraft} instance and its thread.
 *
 * <p>ASM-route notes: the engine is built with the vanilla {@code GameConfig} constructor (the patched
 * constructor drops window/GL/sound/GUI, see {@code eml.template.MinecraftLifecycle}), console chat
 * output arrives through {@link BotRuntime#setSink} rather than a bridge method added to vanilla code,
 * and the pending login connection is read/written via {@link BotActions} because vanilla keeps that
 * field private.
 */
public final class HeadlessSession {
   private static final Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

   private final File gameDirectory;
   private final ConsoleIO io;
   private final boolean online;
   private final String username;
   /** Resolved Microsoft account identity; null in offline mode. */
   private final User sessionUser;
   /** The cached Microsoft session that produced {@link #sessionUser}; null in offline mode. */
   private final MicrosoftSession microsoftSession;
   private final ViewDistance viewDistance;
   private final CompletableFuture<Minecraft> clientFuture = new CompletableFuture<>();
   private volatile Thread clientThread;
   /** Renews the Microsoft session on a timer while the bot runs; null in offline mode or when disabled. */
   private MicrosoftSessionRefresher refresher;

   public HeadlessSession(final File gameDirectory, final String username, final MicrosoftSession microsoftSession, final boolean online, final ViewDistance viewDistance, final ConsoleIO io) {
      this.gameDirectory = gameDirectory;
      this.username = username;
      this.microsoftSession = microsoftSession;
      this.sessionUser = microsoftSession != null ? microsoftSession.toUser() : null;
      this.online = online;
      this.viewDistance = viewDistance;
      this.io = io;
   }

   public synchronized void start() {
      if (this.clientThread != null) {
         return;
      }

      installDiagnostics();
      // How to reach the server again after a drop: the reconnect logic lives in the engine hook (it needs
      // tick timing), but only this class knows how to start a connection.
      eml.hooks.BotRuntime.setReconnectHandler(() -> this.connect(eml.hooks.BotRuntime.targetHost(), eml.hooks.BotRuntime.targetPort()));
      this.clientThread = new Thread(this::runClientThread, "Client thread");
      this.clientThread.start();

      if (this.online && this.microsoftSession != null) {
         BotConfig config = BotConfig.instance();
         int minutes = config == null ? 0 : config.sessionRefreshMinutes();
         this.refresher = new MicrosoftSessionRefresher(this, this.microsoftSession, minutes);
         this.refresher.start();
      }
   }

   /**
    * Makes failures visible. A thread that dies quietly is the reason a headless bot can look "connected but
    * idle", so every uncaught exception is logged with its stack; the connection traces themselves live in
    * the patched engine ({@code eml.hooks.BotDebug}) and are switched on by {@code bot.yml}'s debug block.
    */
   private void installDiagnostics() {
      Thread.setDefaultUncaughtExceptionHandler((thread, error) -> LOGGER.error("Uncaught exception on {}", thread.getName(), error));
      LOGGER.info("debug switches: {}", eml.util.Debug.describe());
   }

   private void runClientThread() {
      // Anything thrown before clientFuture completes would leave every caller of client() waiting forever -
      // the console thread would never reach `quit`, and a headless process would just sit there. Completing
      // the future exceptionally turns that into the startup failure it is (BotApplication exits with code 3).
      try {
         User user = this.sessionUser != null ? this.sessionUser : BotActions.offlineUser(this.username);
         GameConfig config = BotActions.gameConfig(
            this.gameDirectory,
            assetDirectory(),
            user,
            net.minecraft.SharedConstants.getCurrentVersion().name(),
            this.online ? "release" : "offline",
            !this.online,
            Proxy.NO_PROXY
         );
         Minecraft client = new Minecraft(config);

         // Headless bots do not render, so the reported view distance decides how many chunks the server
         // streams and decodes on the game thread. In fixed mode it is applied before the first login packet,
         // so the server starts with the right radius; in auto mode the option is left alone here and the
         // server's own radius is adopted once the login packet names it (see BotRuntime.connectionEdge).
         if (this.viewDistance.auto()) {
            LOGGER.info("View distance: auto, following the server (local option left at {})",
               client.options.renderDistance().get());
         } else {
            int applied = eml.action.BotActions.applyViewDistance(client, this.viewDistance.chunks());
            LOGGER.info("View distance: fixed at {} chunk(s){}", applied,
               applied == this.viewDistance.chunks() ? "" : " (requested " + this.viewDistance.chunks() + ")");
         }

         // Chat lines reach the console through the patched ChatListener; cancellation already happened
         // inside eml.hooks.BotChat before this sink runs.
         BotRuntime.setSink(line -> this.io.print("[chat/" + line.sender() + "] " + AnsiComponents.render(line.content())));

         this.clientFuture.complete(client);

         try {
            client.run();
         } finally {
            LOGGER.info("Client thread finished.");
         }
      } catch (Throwable failure) {
         this.clientFuture.completeExceptionally(failure);
         LOGGER.error("Client thread failed during startup", failure);
      }
   }

   /**
    * The asset directory handed to {@code GameConfig}: always inside the game directory. The vanilla
    * launcher's own asset cache is deliberately not consulted - its location is a platform assumption, and
    * this client loads no textures, sounds or models, so an empty directory is enough (and the headless
    * client is started with an empty resource-pack list anyway).
    */
   private File assetDirectory() {
      String override = System.getProperty("eml.assetsDir");
      File base = override != null && !override.isBlank() ? new File(override) : this.gameDirectory;
      return new File(base, "assets");
   }

   /** Waits until the engine exists; only called from console threads after {@link #start()}. */
   public Minecraft client() {
      try {
         return this.clientFuture.get();
      } catch (Exception e) {
         throw new IllegalStateException("Headless client failed to initialise", e);
      }
   }

   /** Non-blocking view of the engine: the {@link Minecraft} instance once it exists, otherwise null. */
   public Minecraft clientNow() {
      return this.clientFuture.getNow(null);
   }

   /** The client level, or null when the bot is not in a world. Client thread only. */
   public net.minecraft.client.multiplayer.ClientLevel currentLevel() {
      Minecraft client = this.clientNow();
      return client == null ? null : client.level;
   }

   public boolean isStarted() {
      return this.clientThread != null;
   }

   public String username() {
      return this.username;
   }

   /**
    * Applies a renewed Microsoft session to the running client. The identity (name/UUID) never changes here,
    * only the short-lived Minecraft access token (and xuid/clientId when the refresh reports them), so the
    * next login handshake - for example after an auto-reconnect - presents a fresh token.
    */
   public void applyRefreshedSession(final MicrosoftSession updated) {
      Minecraft client = this.clientNow();
      if (client == null) {
         return;
      }

      client.execute(() -> {
         User user = client.user;
         if (user != null) {
            user.accessToken = updated.accessToken();
            if (updated.xuid() != null && !updated.xuid().isBlank()) {
               user.xuid = java.util.Optional.of(updated.xuid());
            }
            if (updated.clientId() != null && !updated.clientId().isBlank()) {
               user.clientId = java.util.Optional.of(updated.clientId());
            }
         }
      });
   }

   /** The working directory the bot runs in (logs, resourcepacks, plugins live here). */
   public File gameDirectory() {
      return this.gameDirectory;
   }

   /** Guard so a status probe and an auto-reconnect retry cannot dial the same server twice at once. */
   private volatile boolean dialing;

   public void connect(final String host, final int port) {
      // Persist the target so the next boot can connect automatically (see bot.yml).
      BotConfig config = BotConfig.instance();
      if (config != null) {
         config.updateConnection(this.username, host, port);
      }

      // Recorded before the probe: a server that fails the ping must still be retried by autoReconnect.
      eml.hooks.BotRuntime.markConnectionIntent(host, port);

      if (config != null && config.pingBeforeConnect()) {
         if (this.dialing) {
            this.io.print(BotLang.get("bot.dial_pending", host, port));
            return;
         }

         this.dialing = true;
         this.io.print(BotLang.get("bot.pinging", host, port));
         Thread probe = new Thread(() -> {
            ServerPinger.Result result = ServerPinger.ping(
               host, port, net.minecraft.SharedConstants.getProtocolVersion(), config.pingTimeoutSeconds() * 1000
            );
            this.dialing = false;

            if (result.online()) {
               this.io.print(BotLang.get("bot.ping_ok", host, port, result.latencyMillis(),
                  result.onlinePlayers(), result.maxPlayers(), result.version() == null ? "?" : result.version()));
               this.connectNow(host, port);
            } else {
               this.io.printError(BotLang.get("bot.ping_failed", host, port, result.detail()));
            }
         }, "Server ping");
         probe.setDaemon(true);
         probe.start();
         return;
      }

      this.connectNow(host, port);
   }

   /** The dial itself: queues the work on the client thread, where the connection may be created. */
   private void connectNow(final String host, final int port) {
      Minecraft client = this.client();
      client.execute(() -> {
         Connection stale = BotActions.pendingConnection(client);

         // A login that failed inside the handshake's future chain leaves pendingConnection set while the
         // socket is already gone, because the vanilla cleanup never ran. Without this the bot then refuses
         // every retry with "already connected" and never reconnects.
         if (client.getConnection() == null && stale != null && !stale.isConnected()) {
            LOGGER.info("clearing stale pending connection to {}", stale.getRemoteAddress());
            BotActions.setPendingConnection(client, null);
            stale.disconnect(Component.literal("stale connection after failed login"));
         }

         if (client.getConnection() != null || BotActions.pendingConnection(client) != null) {
            this.io.printError(BotLang.get("bot.already_connected"));
            return;
         }

         try {
            this.io.print(BotLang.get("bot.connecting", host, port));
            ServerData serverData = new ServerData(host, host + ":" + port, ServerData.Type.OTHER);
            Connection connection = Connection.connectToServer(new InetSocketAddress(host, port), EventLoopGroupHolder.remote(true), null);
            connection.initiateServerboundPlayConnection(
               host,
               port,
               new ClientHandshakePacketListenerImpl(
                  connection,
                  client,
                  serverData,
                  null,
                  false,
                  Duration.ZERO,
                  status -> this.io.print("[login] " + renderStatus(status)),
                  new LevelLoadTracker(),
                  null
               )
            );
            connection.send(new net.minecraft.network.protocol.login.ServerboundHelloPacket(client.getUser().getName(), client.getUser().getProfileId()));
            BotActions.setPendingConnection(client, connection);
         } catch (Exception e) {
            this.io.printError(BotLang.get("bot.connect_failed", rootMessage(e)));
         }
      });
   }

   public void disconnect(final String reason) {
      Minecraft client = this.client();
      eml.hooks.BotRuntime.markUserDisconnect();
      client.execute(() -> {
         ClientPacketListener play = client.getConnection();
         Connection pending = BotActions.pendingConnection(client);
         if (play != null) {
            play.getConnection().disconnect(Component.literal(reason));
         } else if (pending != null) {
            pending.disconnect(Component.literal(reason));
         } else {
            this.io.printError(BotLang.get("bot.not_connected"));
         }
      });
   }

   public void shutdown() {
      if (this.refresher != null) {
         this.refresher.stop();
         this.refresher = null;
      }

      eml.hooks.BotRuntime.markUserDisconnect();
      Minecraft client = this.clientFuture.join();
      if (client != null) {
         client.stop();
      }
   }

   /** Blocks until the client tick loop has finished (i.e. after {@link #shutdown()} or a fatal error). */
   public void awaitTermination() throws InterruptedException {
      Thread thread = this.clientThread;
      if (thread != null) {
         thread.join();
      }
   }

   public void sendChat(final String message) {
      Minecraft client = this.client();
      client.execute(() -> {
         if (client.getConnection() == null) {
            this.io.printError(BotLang.get("bot.not_connected"));
            return;
         }

         // Cancellable, so a plugin can veto or replace console/`say` output before it leaves.
         if (top.mcocet.hlbot.plugins.BotPlugins.fire(new top.mcocet.hlbot.plugins.events.chat.SendChatEvent(message)).isCancelled()) {
            return;
         }

         client.getConnection().sendChat(message);
      });
   }

   public void sendCommand(final String command) {
      Minecraft client = this.client();
      client.execute(() -> {
         if (client.getConnection() == null) {
            this.io.printError(BotLang.get("bot.not_connected"));
            return;
         }

         if (top.mcocet.hlbot.plugins.BotPlugins.fire(new top.mcocet.hlbot.plugins.events.chat.SendCommandEvent(command)).isCancelled()) {
            return;
         }

         client.getConnection().sendCommand(command);
      });
   }

   /**
    * Applies a new view distance and tells the server, so it streams the matching chunks (see
    * {@link eml.action.BotActions#applyViewDistance}). In {@code auto} mode this is an override for the
    * current connection: the server's radius is adopted again on the next login.
    */
   public void setViewDistance(final int chunks) {
      Minecraft client = this.client();
      if (client.isSameThread()) {
         // Already on the client thread (a plugin command or a tick): apply now, so a restore followed by a
         // new raise in the same call cannot overtake each other through the task queue.
         eml.action.BotActions.applyViewDistance(client, chunks);
         return;
      }

      client.execute(() -> eml.action.BotActions.applyViewDistance(client, chunks));
   }

   /**
    * The render distance currently in effect (chunks reported to the server), or {@code -1} before the client
    * exists - in {@code auto} mode nothing has been requested yet at that point, and a fixed radius has not
    * been applied either.
    */
   public int viewDistance() {
      Minecraft client = this.clientNow();
      if (client != null) {
         return client.options.renderDistance().get();
      }

      return this.viewDistance.auto() ? -1 : this.viewDistance.chunks();
   }

   /**
    * The configured mode for this session: {@code auto} or a fixed radius, as resolved from bot.yml and the
    * command line. Exposed because {@code bot.yml} alone cannot answer it - a flag overrides the file for
    * one run, and {@code status} must report what the session actually does.
    */
   public ViewDistance viewDistanceMode() {
      return this.viewDistance;
   }

   public boolean isConnected() {
      Minecraft client = this.isStarted() ? this.clientFuture.getNow(null) : null;
      return client != null && BotActions.isConnected(client);
   }

   /** The handshake listener reports its progress as a Component (raw type after decompilation). */
   private static String renderStatus(final Object status) {
      return status instanceof Component component ? component.getString() : String.valueOf(status);
   }

   private static String rootMessage(final Throwable t) {
      Throwable current = t;
      while (current.getCause() != null && current.getCause() != current) {
         current = current.getCause();
      }

      String message = current.getMessage();
      return current.getClass().getSimpleName() + (message == null ? "" : ": " + message);
   }
}
