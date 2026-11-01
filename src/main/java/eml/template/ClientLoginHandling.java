package eml.template;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen.Reason;
import net.minecraft.client.multiplayer.ClientLevel.ClientLevelData;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.LastSeenMessagesTracker;
import net.minecraft.network.chat.MessageSignatureCache;
import net.minecraft.network.chat.SignedMessageChain.Encoder;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * Headless port of {@code ClientPacketListener.handleLogin(ClientboundLoginPacket)}.
 *
 * <p>Replaced wholesale rather than patched statement by statement: the tail of the vanilla method is
 * GUI-only work reached in different ways, and the last crash rounds all came from there -
 * {@code levelRenderer.debugRenderer.refreshRendererList()} and, for an offline server,
 * {@code SystemToast.multiline(...)}, which splits its text with {@code Minecraft.font} while building the
 * argument. A null inside a helper's own argument evaluation is not something statement removal can express.
 *
 * <p>Everything the game depends on is kept, including the parts that look cosmetic but are protocol state:
 * the shuffled dimension set, {@code setServerRenderDistance}, and the whole chat-signing reset (leaving
 * {@code lastSeenMessages} or {@code signedMessageEncoder} stale makes later signed messages diverge from
 * what the server expects).
 *
 * <p>Authoring rules for donors (enforced by {@code Donor}): no lambdas or method references to foreign
 * classes, only target-class internals plus public API of others, and no Guava - it is on the runtime
 * classpath but is not a declared build dependency, so {@code Lists}/{@code Sets} are replaced by JDK
 * collections even though the vanilla source uses them.
 */
public final class ClientLoginHandling {
   private ClientLoginHandling() {
   }

   public static void handleLogin(final ClientPacketListener self, final ClientboundLoginPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, self, self.minecraft.packetProcessor());
      Minecraft client = self.minecraft;
      client.gameMode = new MultiPlayerGameMode(client, self);

      CommonPlayerSpawnInfo spawnInfo = packet.commonPlayerSpawnInfo();
      List<ResourceKey<Level>> levels = new ArrayList<>(packet.levels());
      Collections.shuffle(levels);
      self.levels = new LinkedHashSet<>(levels);
      ResourceKey<Level> dimension = spawnInfo.dimension();
      Holder<DimensionType> dimensionType = spawnInfo.dimensionType();
      self.serverChunkRadius = packet.chunkRadius();
      self.serverSimulationDistance = packet.simulationDistance();
      boolean isDebug = spawnInfo.isDebug();
      boolean isFlat = spawnInfo.isFlat();
      int seaLevel = spawnInfo.seaLevel();
      ClientLevelData levelData = new ClientLevelData(Difficulty.NORMAL, packet.hardcore(), isFlat);
      self.levelData = levelData;
      // The level is handed the (null) levelRenderer and only stores it: nothing here renders the world.
      self.level = new ClientLevel(
         self, levelData, dimension, dimensionType,
         self.serverChunkRadius, self.serverSimulationDistance,
         client.levelRenderer, isDebug, spawnInfo.seed(), seaLevel
      );
      client.setLevel(self.level);

      if (client.player == null) {
         client.player = client.gameMode.createPlayer(self.level, new StatsCounter(), new ClientRecipeBook());
         client.player.setYRot(-180.0F);
         if (client.getSingleplayerServer() != null) {
            client.getSingleplayerServer().setUUID(client.player.getUUID());
         }
      }

      self.setClientLoaded(false);
      self.debugSubscriber.clear();
      // Vanilla refreshes the renderer's debug entity list here; no renderer, and nothing reads it.
      client.player.resetPos();
      client.player.setId(packet.playerId());
      self.level.addEntity(client.player);
      // Input handling still drives movement state, so it is built exactly as vanilla does.
      client.player.input = new KeyboardInput(client.options);
      client.gameMode.adjustPlayer(client.player);
      client.setCameraEntity(client.player);
      self.startWaitingForNewLevel(client.player, self.level, Reason.OTHER);

      client.player.setReducedDebugInfo(packet.reducedDebugInfo());
      client.player.setShowDeathScreen(packet.showDeathScreen());
      client.player.setDoLimitedCrafting(packet.doLimitedCrafting());
      client.player.setLastDeathLocation(spawnInfo.lastDeathLocation());
      client.player.setPortalCooldown(spawnInfo.portalCooldown());
      client.gameMode.setLocalMode(spawnInfo.gameType(), spawnInfo.previousGameType());
      client.options.setServerRenderDistance(packet.chunkRadius());

      self.chatSession = null;
      self.signedMessageEncoder = Encoder.UNSIGNED;
      self.nextChatIndex = 0;
      self.lastSeenMessages = new LastSeenMessagesTracker(20);
      self.messageSignatureCache = MessageSignatureCache.createDefault();

      if (self.connection.isEncrypted()) {
         self.prepareKeyPair();
      }

      self.telemetryManager.onPlayerInfoReceived(spawnInfo.gameType(), packet.hardcore());
      client.quickPlayLog().log(client);

      self.serverEnforcesSecureChat = packet.enforcesSecureChat();
      if (self.serverData != null && !self.seenInsecureChatWarning && !self.enforcesSecureChat()) {
         // Vanilla raises a SystemToast, which needs Minecraft.font; the warning itself is worth keeping.
         eml.hooks.BotRuntime.log().warn("Server {} does not enforce secure chat", self.serverData.ip);
         self.seenInsecureChatWarning = true;
      }

      // The plugin-visible "we are in the game now" signal is NOT fired here: the tick loop's
      // BotRuntime.connectionEdge notices the play connection appearing, enables the loaded plugins and
      // fires LoginSuccessEvent there, so enabling and the event share one thread and one source of truth.
   }
}
