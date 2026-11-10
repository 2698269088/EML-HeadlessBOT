package eml.template;

import java.util.concurrent.CompletableFuture;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import net.minecraft.client.CommandHistory;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.main.GameConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.client.multiplayer.chat.report.ReportEnvironment;
import net.minecraft.client.multiplayer.chat.report.ReportingContext;
import net.minecraft.network.PacketProcessor;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.server.Services;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.util.Util;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.profiling.metrics.profiling.InactiveMetricsRecorder;

/**
 * Donor bodies for the {@code Minecraft} lifecycle. Each method is copied into the matching vanilla
 * method by {@code eml.engine.Donor}; the first parameter is always the instance, and everything
 * below runs as if it had always been inside {@code Minecraft}.
 *
 * <p>Authoring rules (enforced by {@code Donor} failing fast, follow them or the launch dies):
 * <ul>
 *   <li>No lambdas or method references to classes outside the target: a lambda body is a synthetic
 *       method of the DONOR class, and after the copy the caller becomes {@code Minecraft}, which
 *       cannot reach a donor-class synthetic. Method references to the target class itself are fine
 *       (the generated hidden class becomes its nestmate).
 *   <li>Only target-class internals plus public/package API of other classes.
 * </ul>
 */
public final class MinecraftLifecycle {
   private MinecraftLifecycle() {
   }

   /** {@code Minecraft.<init>(GameConfig)}: the vanilla object graph minus window, GL, sound and GUI. */
   public static void init(final Minecraft self, final GameConfig gameConfig) {
      Minecraft.instance = self;
      self.clientStartTimeMs = System.currentTimeMillis();
      self.lastActiveTime = Util.getMillis();
      self.gameDirectory = gameConfig.location.gameDirectory;
      self.resourcePackDirectory = gameConfig.location.resourcePackDirectory.toPath();
      self.launchedVersion = gameConfig.game.launchVersion;
      self.versionType = gameConfig.game.versionType;
      self.proxy = gameConfig.user.proxy;
      self.offlineDeveloperMode = gameConfig.game.offlineDeveloperMode;
      self.demo = gameConfig.game.demo;
      self.allowsMultiplayer = !gameConfig.game.disableMultiplayer;
      self.allowsChat = !gameConfig.game.disableChat;
      self.user = gameConfig.user.user;
      self.fixerUpper = DataFixers.getDataFixer();
      self.gameThread = Thread.currentThread();
      self.running = true;

      // The tick clock vanilla feeds from getTickTargetMillis; kept exactly as the original field
      // initializer would have built it, since replacing the constructor body erases initializers.
      self.deltaTracker = new DeltaTracker.Timer(20.0F, 0L, self::getTickTargetMillis);
      self.metricsRecorder = InactiveMetricsRecorder.INSTANCE;

      YggdrasilAuthenticationService authenticationService = self.offlineDeveloperMode
         ? YggdrasilAuthenticationService.createOffline(self.proxy)
         : new YggdrasilAuthenticationService(self.proxy);
      self.services = Services.create(authenticationService, self.gameDirectory);
      self.profileFuture = CompletableFuture.completedFuture(null);
      self.userApiService = self.offlineDeveloperMode
         ? UserApiService.OFFLINE
         : authenticationService.createUserApiService(self.user.getAccessToken());
      self.userPropertiesFuture = CompletableFuture.completedFuture(UserApiService.OFFLINE_PROPERTIES);

      // Both were originally assumed render-adjacent and left null; the connection trace proved otherwise,
      // because code that has nothing to do with rendering reads them during login:
      //   ClientHandshakePacketListenerImpl.handleLoginFinished -> getTelemetryManager().createWorldSessionManager
      //   ClientCommonPacketListenerImpl -> getDownloadedPackSource().popAll()
      // Constructing them costs one small object each and keeps vanilla behaviour instead of patching call sites.
      self.telemetryManager = new net.minecraft.client.telemetry.ClientTelemetryManager(self, self.userApiService, self.user);
      self.downloadedPackSource = new net.minecraft.client.resources.server.DownloadedPackSource(
         self, self.gameDirectory.toPath().resolve("downloads"), gameConfig.user);

      self.options = new Options(self, self.gameDirectory);
      // The bot's own switch wins over options.txt: auto-jump walks the player up steps on its own, which
      // fights a scripted pathfinder (and makes every planned move unpredictable).
      net.minecraft.client.headless.BotConfig botConfig = net.minecraft.client.headless.BotConfig.instance();
      if (botConfig != null) {
         self.options.autoJump().set(botConfig.autoJump());
      }
      self.commandHistory = new CommandHistory(self.gameDirectory.toPath());
      self.packetProcessor = new PacketProcessor(self.gameThread);
      self.profileKeyPairManager = self.offlineDeveloperMode
         ? ProfileKeyPairManager.EMPTY_KEY_MANAGER
         : ProfileKeyPairManager.create(self.userApiService, self.user, self.gameDirectory.toPath());
      self.chatListener = new ChatListener(self);
      self.playerSocialManager = new net.minecraft.client.gui.screens.social.PlayerSocialManager(self, self.userApiService);
      // Not render code: the play listener's debug overlay asks it for overlay visibility every tick, and
      // LocalPlayer.setReducedDebugInfo rebuilds it. Vanilla builds it from two files in the game directory.
      self.debugEntries = new net.minecraft.client.gui.components.debug.DebugScreenEntryList(self.gameDirectory, self.fixerUpper);
      // Quick play log: a text file of recently joined servers, asked for at the end of handleLogin.
      self.quickPlayLog = net.minecraft.client.quickplay.QuickPlayLog.of(gameConfig.quickPlay.logPath());
      // Tint sources are a CPU-only registry (state -> colour function), but leaf/grass blocks reach it from
      // animateTick when they spawn falling-leaf particles, so it is not render-only in practice: without it
      // the world tick died on Minecraft.getBlockColors() being null one tick after joining.
      self.blockColors = net.minecraft.client.color.block.BlockColors.createDefault();
      self.reportingContext = ReportingContext.create(ReportEnvironment.local(), self.userApiService);
      self.gameLoadFinished = true;
      eml.hooks.BotRuntime.log().info("Headless client initialised for user '{}' (version {})", self.user.getName(), self.launchedVersion);
   }

   /**
    * {@code Minecraft.renderFrame(boolean)}: vanilla's "do one frame while blocked" helper - profiler scope,
    * GPU timer query, window fullscreen update, screen and level rendering. It is reached from code that has
    * nothing else to do with rendering: {@code setScreenAndShow} calls it, and the play listener calls that
    * while handing over the level, so a headless client died on {@code window.isFocused()} there.
    *
    * <p>The time bookkeeping and the task queue are kept because waiting callers depend on them: the tick
    * clock must not look frozen, and queued game-thread tasks must keep draining or those waits deadlock.
    * Level ticking is left to the main loop, which already runs it.
    */
   public static void renderFrame(final Minecraft self, final boolean advanceGameTime) {
      self.deltaTracker.advanceRealTime(Util.getMillis());
      self.runAllTasks();
   }

   /** {@code Minecraft.run()}: the 20 TPS loop, no frame presentation, no window polling. */
   public static void run(final Minecraft self) {
      self.gameThread = Thread.currentThread();
      eml.hooks.BotRuntime.resetPacer();

      while (self.running) {
         try {
            self.runTick(true);
         } catch (ReportedException e) {
            eml.util.CrashReporter.report(Thread.currentThread(), e);
            self.running = false;
         } catch (Throwable t) {
            eml.util.CrashReporter.report(Thread.currentThread(), t);
            self.running = false;
         }

         if (eml.hooks.BotRuntime.tickRateLimit()) {
            self.running = false;
         }
      }

      self.close();
   }

   /** {@code Minecraft.runTick(boolean)}: packet mailbag, task queue and the tick burst. */
   public static void runTick(final Minecraft self, final boolean advanceGameTime) {
      int ticksToDo = advanceGameTime ? self.deltaTracker.advanceGameTime(Util.getMillis()) : 0;
      self.packetProcessor.processQueuedPackets();
      self.runAllTasks();

      for (int i = 0; i < Math.min(10, ticksToDo); i++) {
         self.tick();
      }

      self.deltaTracker.advanceRealTime(Util.getMillis());
      // Headless never opens screens, so pause can only come from the level itself.
      self.pause = false;
      self.deltaTracker.updatePauseState(self.pause);
      boolean levelRunning = self.isLevelRunningNormally();
      self.deltaTracker.updateFrozenState(!levelRunning);
      eml.hooks.BotRuntime.tickClock(levelRunning, self.level != null && self.level.tickRateManager().isFrozen());
   }

   /** {@code Minecraft.tick()}: the vanilla tick minus GUI/screens/input/particles/sound. */
   public static void tick(final Minecraft self) {
      self.clientTickCount++;
      if (self.level != null && !self.pause) {
         self.level.tickRateManager().tick();
      }

      if (self.rightClickDelay > 0) {
         self.rightClickDelay--;
      }

      eml.hooks.BotRuntime.onClientTick(self);
      self.chatListener.tick();
      self.pick(1.0F);

      if (!self.pause && self.level != null && self.gameMode != null && self.player != null) {
         self.gameMode.tick();
      }

      if (self.missTime > 0) {
         self.missTime--;
      }

      if (self.level != null && !self.pause) {
         self.level.tickEntities();
         self.level.tickBlockEntities();

         try {
            self.level.tick(eml.hooks.BotRuntime.alwaysTrue());
         } catch (Throwable t) {
            CrashReport report = CrashReport.forThrowable(t, "Exception in world tick");
            if (self.level == null) {
               report.addCategory("Affected level").setDetail("Problem", "Level is null!");
            } else {
               self.level.fillReportDetails(report);
            }

            throw new ReportedException(report);
         }

         if (self.isLevelRunningNormally() && self.player != null) {
            self.level.animateTick(self.player.getBlockX(), self.player.getBlockY(), self.player.getBlockZ());
         }

         ClientPacketListener connection = self.getConnection();
         if (connection != null) {
            connection.send(ServerboundClientTickEndPacket.INSTANCE);
         }
      } else if (self.pendingConnection != null) {
         self.pendingConnection.tick();
      }
   }

   /** {@code Minecraft.setLevel(ClientLevel)}: unchanged semantics, render engines no longer notified. */
   public static void setLevel(final Minecraft self, final ClientLevel level) {
      self.level = level;
      self.updateLevelInEngines(level);
   }

   /**
    * {@code Minecraft.updateLevelInEngines(ClientLevel, boolean)}: only the level-independent state
    * sync. Registering the local player is NOT done here - vanilla lets the packet listener do it,
    * and doing it early (when {@code player} is still null) crashes handleLogin with a missing entity.
    */
   public static void updateLevelInEnginesWithSound(final Minecraft self, final ClientLevel level, final boolean stopSound) {
      self.setCameraEntity(null);
      self.pendingConnection = null;
   }

   /** {@code Minecraft.updateLevelInEngines(ClientLevel)}: same body, single-argument overload. */
   public static void updateLevelInEngines(final Minecraft self, final ClientLevel level) {
      self.setCameraEntity(null);
      self.pendingConnection = null;
   }

   /**
    * {@code Minecraft.clearClientLevel(Screen)}: the level teardown without the renderer, the narrator and the
    * GUI. Reached from {@code ClientPacketListener.handleConfigurationStart} (server transfer / reconfigure) -
    * vanilla throws there on {@code gameRenderer.resetData()} and {@code narrator.clear()}, both null headless.
    *
    * <p>The screen is still passed to {@code setScreenAndShow}: its donors keep the tick clock and the task
    * queue moving while the screen itself is inert, so queued work does not deadlock.
    */
   public static void clearClientLevel(final Minecraft self, final net.minecraft.client.gui.screens.Screen screen) {
      ClientPacketListener connection = self.getConnection();
      if (connection != null) {
         connection.clearLevel();
      }

      if (self.metricsRecorder.isRecording()) {
         self.debugClientMetricsCancel();
      }

      self.gameMode = null;
      self.clientLevelTeardownInProgress = true;

      try {
         self.setScreenAndShow(screen);
         self.level = null;
         self.updateLevelInEngines(null);
         self.player = null;
      } finally {
         self.clientLevelTeardownInProgress = false;
      }
   }

   /** {@code Minecraft.setScreen(Screen)}: screens are headless no-ops, nothing is constructed here. */
   public static void setScreen(final Minecraft self, final net.minecraft.client.gui.screens.Screen screen) {
      if (self.screen != null) {
         self.screen.removed();
      }

      self.screen = screen;
      if (screen != null) {
         screen.added();
      }
   }

   /** {@code Minecraft.close()}: shuts down what exists, skips every GPU/audio teardown. */
   public static void close(final Minecraft self) {
      try {
         if (self.level != null) {
            self.level.disconnect(ClientLevel.DEFAULT_QUIT_MESSAGE);
         }
      } catch (Throwable t) {
         eml.hooks.BotRuntime.log().warn("Error while disconnecting level", t);
      }

      try {
         ClientPacketListener connection = self.getConnection();
         if (connection != null) {
            connection.close();
         }
      } catch (Throwable t) {
         eml.hooks.BotRuntime.log().warn("Error while closing connection", t);
      }

      self.options.save();
      if (self.resourceManager != null) {
         try {
            self.resourceManager.close();
         } catch (Throwable t) {
            eml.hooks.BotRuntime.log().warn("Error while closing resource manager", t);
         }
      }

      Util.shutdownExecutors();
   }

   /** {@code Minecraft.destroy()}: no screens, no {@code System.exit} - the launcher owns the process. */
   public static void destroy(final Minecraft self) {
      eml.hooks.BotRuntime.log().info("Stopping!");
      self.close();
   }

   /**
    * {@code Minecraft.disconnect(Screen, boolean, boolean)}: identical state teardown, minus the four things
    * a headless client does not have - renderer data, the narrator, the GUI's disconnect bookkeeping, and the
    * modal screen whose vanilla implementation waits by presenting frames.
    *
    * <p>Vanilla's version NPE'd here on {@code playerSocialManager} and {@code gameRenderer}, which mattered
    * because this method runs on every kick and every {@code disconnect} command: after the first failed
    * login it left the client unable to retry.
    */
   public static void disconnect(
      final Minecraft self,
      final net.minecraft.client.gui.screens.Screen screen,
      final boolean keepResourcePacks,
      final boolean stopSound
   ) {
      ClientPacketListener connection = self.getConnection();
      if (connection != null) {
         self.dropAllTasks();
         connection.close();
         if (!keepResourcePacks) {
            self.clearDownloadedResourcePacks();
         }
      }

      self.playerSocialManager.stopOnlineMode();
      if (self.metricsRecorder.isRecording()) {
         self.debugClientMetricsCancel();
      }

      net.minecraft.client.server.IntegratedServer server = self.singleplayerServer;
      self.singleplayerServer = null;
      self.gameMode = null;
      self.clientLevelTeardownInProgress = true;

      try {
         self.level = null;
         if (server != null) {
            // Headless never hosts an integrated server; halt without the render loop vanilla uses to wait.
            server.halt(false);
         }

         self.isLocalServer = false;
         self.updateLevelInEngines(null, stopSound);
         self.player = null;
      } finally {
         // Must run even when a step above throws, or the next level load is refused.
         self.clientLevelTeardownInProgress = false;
      }
   }
}
